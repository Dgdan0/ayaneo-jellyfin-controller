package com.pocketds.hub.ui.glass

import android.content.Context
import com.pocketds.hub.debug.DebugLog
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The one way a screen gets an artwork's Glass colours (GLASS_PLAN.md, #10).
 *
 * Screens [prefetch] what they are about to show and [request] what is in
 * focus; asks made within a few frames of each other go to the hub as one
 * request. Known colours are kept in the app's files, so after a restart a
 * page is in colour before the network answers. Everything here runs on the
 * main thread except the request and the file.
 */
class ArtworkColors private constructor(private val appContext: Context, private val api: HubApi) {

    private val book = ArtworkColorBook()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queued = LinkedHashSet<String>()
    private val listeners = HashMap<String, MutableList<(ArtworkPalette) -> Unit>>()
    private var sending: Job? = null
    private var retrying: Job? = null
    private var saving: Job? = null
    private val file = File(appContext.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        scope.launch {
            val kept = withContext(Dispatchers.IO) { load() }
            book.restore(kept)
        }
    }

    /** Known colours for [src], or null. Never asks. */
    fun peek(src: String?): ArtworkPalette? = src?.takeIf { it.isNotBlank() }?.let(book::palette)

    /** Asks for colours a screen is about to need, without waiting for them. */
    fun prefetch(sources: Collection<String>) {
        var added = false
        for (src in sources) if (src.isNotBlank() && book.palette(src) == null && !book.isMissing(src)) added = queued.add(src) || added
        if (added) schedule(BATCH_DELAY_MS)
    }

    /**
     * Calls [onReady] with [src]'s colours: at once when they are known, or when
     * the hub answers. Missing artwork never calls back; the page stays neutral.
     */
    fun request(src: String?, onReady: (ArtworkPalette) -> Unit) {
        if (src.isNullOrBlank()) return
        book.palette(src)?.let { onReady(it); return }
        if (book.isMissing(src)) return
        listeners.getOrPut(src) { ArrayList() } += onReady
        if (queued.add(src)) schedule(BATCH_DELAY_MS)
    }

    /** Drops [onReady] for [src], for a view that no longer shows it. */
    fun cancel(src: String?, onReady: (ArtworkPalette) -> Unit) {
        if (src == null) return
        listeners[src]?.remove(onReady)
    }

    private fun schedule(afterMs: Long) {
        if (sending?.isActive == true) return
        sending = scope.launch {
            delay(afterMs)
            while (queued.isNotEmpty()) {
                val asked = book.toAsk(queued.toList(), now())
                queued.removeAll(queued.filter { book.palette(it) != null || book.isMissing(it) || it in asked }.toSet())
                if (asked.isEmpty()) break
                send(asked)
            }
            planRetry()
        }
    }

    private suspend fun send(asked: List<String>) {
        when (val result = withContext(Dispatchers.IO) { api.artworkColors(asked) }) {
            is HubResult.Ok -> {
                val colors = result.value.colors.mapNotNull { (src, set) -> ArtworkPalette.from(set)?.let { src to it } }.toMap()
                val got = book.answered(asked, colors, result.value.missing, now())
                for (src in got) {
                    val palette = book.palette(src) ?: continue
                    listeners.remove(src)?.forEach { it(palette) }
                }
                for (src in asked) if (book.isMissing(src)) listeners.remove(src)
                if (got.isNotEmpty()) save()
            }
            is HubResult.Failed -> {
                DebugLog.log("glass", "artwork colours failed: ${result.kind}")
                book.failed(asked, now())
            }
        }
    }

    private fun planRetry() {
        val dueAt = book.nextDueAt() ?: return
        if (retrying?.isActive == true) return
        retrying = scope.launch {
            delay((dueAt - now()).coerceAtLeast(0L))
            val due = book.due(now())
            if (due.isNotEmpty()) {
                queued.addAll(due)
                schedule(0)
            }
        }
    }

    private fun save() {
        if (saving?.isActive == true) return
        saving = scope.launch {
            delay(SAVE_DELAY_MS)
            val rows = book.snapshot().takeLast(KEEP_ON_DEVICE).map { (src, p) ->
                listOf(src, hex(p.dominant), hex(p.dark), hex(p.vivid), hex(p.light))
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val temporary = File(file.path + ".tmp")
                    temporary.writeText(json.encodeToString(rows))
                    if (!temporary.renameTo(file)) {
                        file.delete()
                        temporary.renameTo(file)
                    }
                }.onFailure { DebugLog.log("glass", "artwork colours not saved: ${it.message}") }
            }
        }
    }

    private fun load(): List<Pair<String, ArtworkPalette>> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        json.decodeFromString<List<List<String>>>(file.readText()).mapNotNull { row ->
            if (row.size != 5) return@mapNotNull null
            val palette = ArtworkPalette.from(com.pocketds.hub.model.ArtworkColorSet(row[1], row[2], row[3], row[4]))
            palette?.let { row[0] to it }
        }
    }.getOrElse {
        // A damaged file costs one round of asking again, never a crash.
        DebugLog.log("glass", "artwork colours file unreadable: ${it.message}")
        emptyList()
    }

    private fun now() = System.currentTimeMillis()

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)

    companion object {
        private const val FILE_NAME = "artwork-colors.json"
        /** Long enough for a row of cards binding in one frame to share a request. */
        private const val BATCH_DELAY_MS = 40L
        private const val SAVE_DELAY_MS = 5_000L
        private const val KEEP_ON_DEVICE = 2000

        @Volatile private var shared: ArtworkColors? = null

        fun shared(context: Context, api: HubApi): ArtworkColors = shared ?: synchronized(this) {
            shared ?: ArtworkColors(context.applicationContext, api).also { shared = it }
        }
    }
}

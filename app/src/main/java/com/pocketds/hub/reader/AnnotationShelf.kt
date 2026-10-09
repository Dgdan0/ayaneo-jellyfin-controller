package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.debug.DebugLog
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A highlight's id as the hub asks for it: `an_` and 32 hex digits. */
object AnnotationIds {
    private val random = SecureRandom()
    private val PATTERN = Regex("^an_[0-9a-f]{32}$")

    fun new(): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        return "an_" + bytes.joinToString("") { "%02x".format(it) }
    }

    fun valid(id: String): Boolean = PATTERN.matches(id)
}

/**
 * One book's highlights for the profile in use (#62): what the screens show ([live]), edited at once and kept on disk so a note
 * written on the train is there tomorrow, and sent to the hub whenever it can be. The book is [AnnotationBook]'s; this is its
 * lock, its file and its changes. Safe to edit from any thread, and to be synced while the person edits.
 */
class AnnotationShelf internal constructor(
    private val scope: String,
    val workId: String,
    private val store: AnnotationStore,
    private val requestSync: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : AnnotationLedger {
    private val lock = Any()
    private var book: AnnotationBook
    private val state = MutableStateFlow<List<ReadingAnnotation>>(emptyList())

    /** The file on this device could not be read: it is left as it is, and nothing is written over it. */
    val unreadable: Boolean

    /** The highlights to draw and list, in the order they were made. */
    val live: StateFlow<List<ReadingAnnotation>> get() = state

    init {
        var failed = false
        book = try { store.load(scope, workId) } catch (error: Exception) {
            DebugLog.log("reader", "highlights unreadable: ${error.javaClass.simpleName}")
            failed = true
            AnnotationBook()
        }
        unreadable = failed
        state.value = book.live
    }

    operator fun get(id: String): ReadingAnnotation? = synchronized(lock) { book[id] }

    /** A highlight made or changed. Null where it could not be kept on this device (the file is unreadable, or the disk would not take it). */
    fun save(annotation: ReadingAnnotation): ReadingAnnotation? {
        val kept = synchronized(lock) {
            if (unreadable) return null
            val before = book.snapshot()
            val kept = book.put(annotation, clock())
            if (!persist()) { book = before.book(); return null }
            state.value = book.live
            kept
        }
        requestSync()
        return kept
    }

    /** A highlight taken away: a tombstone, so every device learns of it. */
    fun remove(id: String): ReadingAnnotation? {
        val gone = synchronized(lock) {
            if (unreadable) return null
            val before = book.snapshot()
            val gone = book.remove(id, clock()) ?: return null
            if (!persist()) { book = before.book(); return null }
            state.value = book.live
            gone
        }
        requestSync()
        return gone
    }

    /** Whether any edit is waiting for the hub. */
    val waiting: Boolean get() = synchronized(lock) { book.isDirty }

    suspend fun syncNow(remote: AnnotationRemote): AnnotationSync.Result {
        if (unreadable) return AnnotationSync.Result(stopped = true)
        return AnnotationSync(remote).run(workId, this)
    }

    override fun pending(): List<ReadingAnnotation> = synchronized(lock) { book.pending() }

    override fun sent(id: String, sent: ReadingAnnotation, held: ReadingAnnotation) {
        synchronized(lock) {
            book.sent(id, sent, held)
            persist()
            state.value = book.live
        }
    }

    override fun refused(id: String) {
        synchronized(lock) {
            book.refused(id)
            persist()
        }
    }

    override fun cursor(): Long = synchronized(lock) { book.cursor }

    override fun merged(remote: List<ReadingAnnotation>) {
        synchronized(lock) {
            book.merge(remote)
            persist()
            state.value = book.live
        }
    }

    private fun persist(): Boolean = try {
        store.save(scope, workId, book)
        true
    } catch (error: Exception) {
        DebugLog.log("reader", "highlights not saved: ${error.javaClass.simpleName}")
        false
    }
}

/** The shelves of every book, one per profile and book, and the pass that sends what was left waiting. */
class AnnotationRepository private constructor(private val context: Context) {
    private val store = AnnotationStore(File(context.filesDir, "reading-annotations"))
    private val shelves = HashMap<Pair<String, String>, AnnotationShelf>()

    @Synchronized fun shelf(session: ReadingProgress.Session, workId: String): AnnotationShelf =
        shelves.getOrPut(session.identity to workId) {
            AnnotationShelf(session.identity, workId, store, requestSync = { ReadingProgress.get(context).requestSync() })
        }

    /** Sends the edits of every book left waiting under [session]; true when a failure may pass and another pass is worth making. */
    suspend fun flush(session: ReadingProgress.Session): Boolean {
        var retry = false
        for (workId in store.pendingWorks(session.identity)) {
            if (shelf(session, workId).syncNow(session.api.annotationRemote()).retry) retry = true
        }
        return retry
    }

    companion object {
        @Volatile private var instance: AnnotationRepository? = null
        fun get(context: Context): AnnotationRepository = instance ?: synchronized(this) {
            instance ?: AnnotationRepository(context.applicationContext).also { instance = it }
        }
    }
}

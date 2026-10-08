package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-profile manual reading status: a book marked read here. [RESET] is what Mark unread used to leave, a book that
 * starts at the beginning on this device alone; nothing writes it now (Start over, #60, is the hub's and every
 * device's), and one an earlier build wrote stays visible until a reader saves a new position.
 */
@Serializable
data class ReadingCompletionState(
    val states: Map<String, String> = emptyMap(),
    val updatedAt: Map<String, Long> = emptyMap()
) {
    fun markRead(id: String, at: Long = System.currentTimeMillis()) =
        copy(states = states + (id to READ), updatedAt = updatedAt + (id to at))
    fun reset(id: String, at: Long = System.currentTimeMillis()) =
        copy(states = states + (id to RESET), updatedAt = updatedAt + (id to at))
    fun clear(id: String) = copy(states = states - id, updatedAt = updatedAt - id)
    fun isRead(id: String) = states[id] == READ
    fun shouldStartAtBeginning(id: String) = states[id] == RESET
    fun ebookResume(id: String, resume: ReadingResume): ReadingResume =
        if (shouldStartAtBeginning(id)) ReadingResume(null) else resume
    fun pageResume(id: String, pageIndex: Int): Int =
        if (shouldStartAtBeginning(id)) 0 else pageIndex

    fun project(work: ReadingWork): ReadingWork {
        val projectedSections = work.sections.map { section -> section.copy(items = section.items.map { item ->
            val status = states[item.workId]
            if (status == null) item else item.copy(progress = progress(status, item.progress, updatedAt[item.workId]))
        }) }
        val status = states[work.id]
        val continuation = work.continueAt?.takeUnless { states[it.workId] != null ||
            (work.entityType != "collection" && status != null) }
        return work.copy(
            progress = if (work.entityType != "collection" && status != null) progress(status, work.progress, updatedAt[work.id]) else work.progress,
            continueAt = continuation, sections = projectedSections
        )
    }

    private fun progress(status: String, original: ReadingProgress?, time: Long?): ReadingProgress = when (status) {
        READ -> ReadingProgress(1.0, true, original?.total ?: 0, original?.total ?: 0,
            time?.let { Instant.ofEpochMilli(it).toString() }.orEmpty())
        else -> ReadingProgress(0.0, false, 0, original?.total ?: 0)
    }

    fun encode(): String = json.encodeToString(this)

    companion object {
        private const val READ = "read"
        private const val RESET = "reset"
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String?): ReadingCompletionState = try {
            if (raw.isNullOrBlank()) ReadingCompletionState()
            else json.decodeFromString<ReadingCompletionState>(raw)
        } catch (_: Exception) { ReadingCompletionState() }
    }
}

/** The undo window ends when the book detail is hidden, including when opening the reader. */
class ReadingCompletionSession {
    private val previous = mutableMapOf<String, Pair<String?, Long?>>()

    fun markRead(state: ReadingCompletionState, id: String): ReadingCompletionState {
        if (id !in previous) previous[id] = state.states[id] to state.updatedAt[id]
        return state.markRead(id)
    }

    /**
     * Mark unread: back to what the state was before this visit marked the book read, else the finish is
     * simply gone. It is not a reset any more (#60): taking the finish away leaves the place where it was,
     * and only Start over, which the hub does for every device, takes a place away.
     */
    fun unmark(state: ReadingCompletionState, id: String): ReadingCompletionState {
        if (!previous.containsKey(id)) return state.clear(id)
        val (prior, time) = previous.remove(id) ?: return state.clear(id)
        return if (prior == null) state.clear(id) else state.copy(
            states = state.states + (id to prior),
            updatedAt = if (time == null) state.updatedAt - id else state.updatedAt + (id to time)
        )
    }

    fun leave() = previous.clear()
}

/** Manual state belongs to the selected Jellyfin profile; rotating the Hub token preserves it. */
object ReadingCompletionRepository {
    private fun key(context: Context): String {
        val identity = HubSettings.baseUrl(context).trimEnd('/') + "\u0000" + HubSettings.userId(context)
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return "reading_completion:" + hash.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context): ReadingCompletionState =
        ReadingCompletionState.decode(Prefs.of(context).getString(key(context), null))

    @Synchronized fun update(context: Context, change: (ReadingCompletionState) -> ReadingCompletionState): ReadingCompletionState {
        val current = get(context)
        val next = change(current)
        if (next != current) Prefs.of(context).edit().putString(key(context), next.encode()).commit()
        return next
    }
}

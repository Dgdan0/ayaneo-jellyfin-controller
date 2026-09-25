package com.pocketds.hub.screens.home

import android.content.Context
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** User-chosen reading order. Work IDs are stable Hub IDs; snapshots keep lists usable offline. */
@Serializable
data class ReadingListEntry(
    val workId: String,
    val title: String,
    val artwork: String = "",
    val kind: String = "book",
    val series: String = "",
    val lastProgress: Double? = null,
    val lastReadAt: Long = 0
) {
    companion object {
        fun from(work: ReadingWork) = ReadingListEntry(work.id, work.title, work.artwork, work.kind, work.series,
            lastProgress = if (work.progress?.completed == true) 1.0 else work.progress?.percentage)
    }
}

@Serializable
data class ReadingList(
    val id: String,
    val name: String,
    val items: List<ReadingListEntry> = emptyList(),
    val updatedAt: Long = 0
)

@Serializable
data class ReadingListsState(
    val wantToRead: List<ReadingListEntry> = emptyList(),
    val lists: List<ReadingList> = emptyList()
) {
    fun create(name: String, id: String = UUID.randomUUID().toString(), at: Long = System.currentTimeMillis()): ReadingListsState {
        val clean = name.trim().take(80)
        if (clean.isBlank() || lists.any { it.id == id }) return this
        return copy(lists = lists + ReadingList(id, clean, updatedAt = at))
    }

    fun rename(id: String, name: String, at: Long = System.currentTimeMillis()): ReadingListsState {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return this
        return copy(lists = lists.map { if (it.id == id) it.copy(name = clean, updatedAt = at) else it })
    }

    fun delete(id: String): ReadingListsState = copy(lists = lists.filterNot { it.id == id })

    fun add(id: String, entry: ReadingListEntry, at: Long = System.currentTimeMillis()): ReadingListsState {
        if (entry.workId.isBlank()) return this
        if (id == WANT_TO_READ) return copy(wantToRead = wantToRead.filterNot { it.workId == entry.workId } +
            listOfNotNull(entry.takeUnless { (it.lastProgress ?: 0.0) > 0.0 }))
        return copy(lists = lists.map { list ->
            if (list.id == id) list.copy(items = list.items.filterNot { it.workId == entry.workId } + entry, updatedAt = at)
            else list
        })
    }

    fun remove(id: String, workId: String, at: Long = System.currentTimeMillis()): ReadingListsState {
        if (id == WANT_TO_READ) return copy(wantToRead = wantToRead.filterNot { it.workId == workId })
        return copy(lists = lists.map { list ->
            if (list.id == id) list.copy(items = list.items.filterNot { it.workId == workId }, updatedAt = at) else list
        })
    }

    fun move(id: String, workId: String, delta: Int, at: Long = System.currentTimeMillis()): ReadingListsState {
        fun shifted(items: List<ReadingListEntry>): List<ReadingListEntry> {
            val index = items.indexOfFirst { it.workId == workId }
            val target = index + delta
            if (index < 0 || target !in items.indices) return items
            return items.toMutableList().apply { add(target, removeAt(index)) }
        }
        if (id == WANT_TO_READ) return copy(wantToRead = shifted(wantToRead))
        return copy(lists = lists.map { if (it.id == id) it.copy(items = shifted(it.items), updatedAt = at) else it })
    }

    fun recordProgress(workId: String, percentage: Double, at: Long = System.currentTimeMillis()): ReadingListsState {
        if (!percentage.isFinite() || percentage !in 0.0..1.0) return this
        fun observed(entry: ReadingListEntry): ReadingListEntry {
            if (entry.workId != workId || entry.lastProgress == percentage) return entry
            return entry.copy(lastProgress = percentage,
                lastReadAt = if (percentage > 0 && at > 0) maxOf(entry.lastReadAt, at) else entry.lastReadAt)
        }
        return copy(
            wantToRead = wantToRead.filterNot { it.workId == workId && percentage > 0.0 }.map(::observed),
            lists = lists.map { list -> list.copy(items = list.items.map(::observed)) }
        )
    }

    fun encode(): String = json.encodeToString(this)

    companion object {
        const val WANT_TO_READ = "want-to-read"
        private val json = Json { ignoreUnknownKeys = true }
        fun decode(raw: String?): ReadingListsState = try {
            if (raw.isNullOrBlank()) ReadingListsState() else json.decodeFromString<ReadingListsState>(raw)
        } catch (_: Exception) { ReadingListsState() }
    }
}

/** Profile isolation excludes the bearer token so a token rotation never loses the shelves. */
object ReadingListsRepository {
    private fun key(context: Context): String {
        val identity = HubSettings.baseUrl(context).trimEnd('/') + "\u0000" + HubSettings.userId(context)
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return "reading_lists:" + digest.joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context): ReadingListsState = ReadingListsState.decode(Prefs.of(context).getString(key(context), null))

    @Synchronized fun update(context: Context, change: (ReadingListsState) -> ReadingListsState): ReadingListsState {
        val before = get(context)
        val next = change(before)
        if (next != before) Prefs.of(context).edit().putString(key(context), next.encode()).apply()
        return next
    }
}

data class ReadingShelfRow(
    val id: String,
    val title: String,
    val items: List<ReadingWork>,
    val nextIndex: Int = 0,
    val readCount: Int = 0,
    val activity: Long = 0,
    val hasReadingActivity: Boolean = false
)

object ReadingShelves {
    fun current(works: List<ReadingWork>): List<ReadingWork> = works.flatMap { work ->
        if (work.entityType == "collection") work.sections.flatMap { section -> section.items.mapNotNull { child ->
            if (child.workId.isBlank()) null else ReadingWork(
                id = child.workId, entityType = "work", kind = child.kind, title = child.title,
                series = work.title, artwork = child.artwork.ifBlank { work.artwork }, progress = child.progress,
                libraryId = work.libraryId
            )
        } } else listOf(work)
    }.filter { work -> work.id.isNotBlank() && work.progress?.let { !it.completed && it.percentage > 0.0 } == true }
        .distinctBy { it.id }
        .withIndex()
        .sortedWith(compareByDescending<IndexedValue<ReadingWork>> { timestamp(it.value.progress?.updatedAt) }
            .thenBy { it.index })
        .map { it.value }

    fun listRow(list: ReadingList, resolved: Map<String, ReadingWork>): ReadingShelfRow {
        val items = list.items.map { entry -> resolved[entry.workId] ?: entry.snapshot() }
        val next = items.indexOfFirst { it.progress?.completed != true }.let { if (it < 0) items.lastIndex.coerceAtLeast(0) else it }
        val serverActivity = items.maxOfOrNull { timestamp(it.progress?.updatedAt) } ?: 0
        val localActivity = list.items.maxOfOrNull { it.lastReadAt } ?: 0
        val readActivity = maxOf(serverActivity, localActivity)
        return ReadingShelfRow(list.id, list.name, items, next, items.count { it.progress?.completed == true },
            if (readActivity > 0) readActivity else list.updatedAt, readActivity > 0)
    }

    fun rows(current: List<ReadingWork>, state: ReadingListsState, resolved: Map<String, ReadingWork>): List<ReadingShelfRow> {
        val now = current(current)
        val reading = if (now.isEmpty()) emptyList() else listOf(ReadingShelfRow("currently-reading", "Currently reading", now))
        val wanted = state.wantToRead.map { resolved[it.workId] ?: it.snapshot() }
            .filterNot { work -> work.progress?.let { it.completed || it.percentage > 0.0 } == true || now.any { it.id == work.id } }
        val wantRow = ReadingShelfRow(ReadingListsState.WANT_TO_READ, "Want to Read", wanted)
        return reading + wantRow + state.lists.map { listRow(it, resolved) }
            .sortedWith(compareByDescending<ReadingShelfRow> { it.hasReadingActivity }
                .thenByDescending { it.activity }.thenBy { it.title })
    }

    private fun ReadingListEntry.snapshot() = ReadingWork(
        id = workId, title = title, artwork = artwork, kind = kind, series = series,
        progress = lastProgress?.let { ReadingProgress(it, it >= .999) }
    )

    internal fun timestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        return raw.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            ?: try { Instant.parse(raw).toEpochMilli() } catch (_: Exception) { 0 }
    }
}

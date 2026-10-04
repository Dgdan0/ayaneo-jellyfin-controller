package com.pocketds.hub.screens.home

import android.content.Context
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.settings.Prefs
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
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
    const val CURRENTLY_READING = "currently-reading"
    const val NEXT_IN_SERIES = "next-in-series"
    const val COMICS = "comics"
    const val RECENTLY_ADDED = "recently-added"
    /** Rows the app fills itself; every other row is one of the person's own lists. */
    val BUILT_IN = setOf(CURRENTLY_READING, NEXT_IN_SERIES, COMICS, ReadingListsState.WANT_TO_READ, RECENTLY_ADDED)

    /** Every book being read, newest first: series are opened up into their books. */
    fun current(works: List<ReadingWork>): List<ReadingWork> = works.flatMap { work ->
        if (work.entityType == "collection") work.sections.flatMap { section -> section.items.mapNotNull { child ->
            if (child.workId.isBlank()) null else child.asWork(work)
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

    fun isComic(work: ReadingWork) = work.kind == "comic" || work.kind == "manga"

    /** A series on Home: up to three covers to fan out, front first, and "6 books · on #6". */
    data class SeriesShelfItem(val id: String, val title: String, val covers: List<String>, val line: String)

    /**
     * The series being read, last read first: those with a book started and
     * not every book finished. The front cover is the book you are on.
     */
    /** The number of the book being read in a series, or empty. */
    fun onNumber(series: ReadingWork): String {
        val books = series.sections.flatMap { it.items }
        return series.continueAt?.number?.takeIf(String::isNotBlank)
            ?: books.lastOrNull { (it.progress?.percentage ?: 0.0) > 0.0 && it.progress?.completed != true }?.number.orEmpty()
    }

    /**
     * A series' covers for a fan: the book being read in front, then its first
     * books in order. Up to four, as Glass's fan holds; Classic's shows three.
     */
    fun fanCovers(series: ReadingWork): List<String> {
        val books = series.sections.flatMap { it.items }
        val on = onNumber(series)
        val front = series.continueAt?.artwork?.takeIf(String::isNotBlank)
            ?: books.firstOrNull { it.number == on }?.artwork.orEmpty()
        return (listOf(front) + books.map { it.artwork }).filter(String::isNotBlank).distinct().take(FAN_COVERS)
            .ifEmpty { listOf(series.artwork).filter(String::isNotBlank) }
    }

    /** The most covers a fan holds. */
    const val FAN_COVERS = 4

    fun yourSeries(collections: List<ReadingWork>): List<SeriesShelfItem> = collections
        .filter { it.entityType == "collection" }
        .distinctBy { it.id }
        .mapNotNull { series ->
            val books = series.sections.flatMap { it.items }
            if (books.none { (it.progress?.percentage ?: 0.0) > 0.0 || it.progress?.completed == true }) return@mapNotNull null
            if (books.isNotEmpty() && books.all { it.progress?.completed == true }) return@mapNotNull null
            val on = onNumber(series)
            val covers = fanCovers(series)
            val count = series.bookCount.takeIf { it > 0 } ?: books.size
            val line = listOfNotNull(
                "$count ${if (count == 1) "book" else "books"}".takeIf { count > 0 },
                on.takeIf(String::isNotBlank)?.let { "on #$it" }
            ).joinToString(" · ")
            val last = books.maxOfOrNull { timestamp(it.progress?.updatedAt) } ?: timestamp(series.progress?.updatedAt)
            last to SeriesShelfItem(series.id, series.title, covers, line)
        }
        .sortedByDescending { it.first }
        .map { it.second }

    /**
     * One card per series, the book of it read last. Red Rising read in three
     * places at once filled the row with three Red Rising cards. [reads] is
     * newest first, so the first book seen of a series is the one to keep.
     */
    fun onePerSeries(reads: List<ReadingWork>): List<ReadingWork> {
        val seen = mutableSetOf<String>()
        return reads.filter { it.series.isBlank() || seen.add(it.series) }
    }

    /**
     * For each series with nothing in progress: the first available, unfinished
     * book after the last one finished. Most recently finished series first.
     */
    fun nextInSeries(collections: List<ReadingWork>): List<ReadingWork> = collections
        .filter { it.entityType == "collection" }
        .mapNotNull { series ->
            val books = series.sections.flatMap { it.items }
            if (books.any { it.progress?.let { p -> !p.completed && p.percentage > 0.0 } == true }) return@mapNotNull null
            val last = books.indexOfLast { it.progress?.completed == true }
            if (last < 0) return@mapNotNull null
            val next = books.drop(last + 1).firstOrNull { it.isAvailable && it.progress?.completed != true }
                ?: return@mapNotNull null
            timestamp(books[last].progress?.updatedAt) to next.asWork(series)
        }
        .sortedByDescending { it.first }
        .map { it.second }

    fun rows(
        current: List<ReadingWork>,
        state: ReadingListsState,
        resolved: Map<String, ReadingWork>,
        next: List<ReadingWork> = emptyList(),
        recent: List<ReadingWork> = emptyList()
    ): List<ReadingShelfRow> {
        val now = current(current)
        val comics = now.filter(::isComic)
        val comicsTitle = when {
            comics.all { it.kind == "manga" } -> "Manga"
            comics.all { it.kind == "comic" } -> "Comics"
            else -> "Comics and manga"
        }
        val builtIn = listOf(
            ReadingShelfRow(CURRENTLY_READING, "Currently reading", onePerSeries(now.filterNot(::isComic))),
            ReadingShelfRow(NEXT_IN_SERIES, "Next in series", next.filterNot { book -> now.any { it.id == book.id } }),
            ReadingShelfRow(COMICS, comicsTitle, comics)
        ).filter { it.items.isNotEmpty() }
        val wanted = state.wantToRead.map { resolved[it.workId] ?: it.snapshot() }
            .filterNot { work -> work.progress?.let { it.completed || it.percentage > 0.0 } == true || now.any { it.id == work.id } }
        val wantRow = ReadingShelfRow(ReadingListsState.WANT_TO_READ, "Want to Read", wanted)
        val lists = state.lists.map { listRow(it, resolved) }
            .sortedWith(compareByDescending<ReadingShelfRow> { it.hasReadingActivity }
                .thenByDescending { it.activity }.thenBy { it.title })
        val added = if (recent.isEmpty()) emptyList() else listOf(ReadingShelfRow(RECENTLY_ADDED, "Recently added", recent))
        return builtIn + wantRow + lists + added
    }

    private fun ReadingSectionItem.asWork(series: ReadingWork) = ReadingWork(
        id = workId, entityType = "work", kind = kind, title = title, series = series.title,
        seriesIndex = number.toDoubleOrNull() ?: 0.0, authors = authors,
        artwork = artwork.ifBlank { series.artwork }, progress = progress, libraryId = series.libraryId
    )

    private fun ReadingListEntry.snapshot() = ReadingWork(
        id = workId, title = title, artwork = artwork, kind = kind, series = series,
        progress = lastProgress?.let { ReadingProgress(it, it >= .999) }
    )

    internal fun timestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        return raw.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
            ?: try { Instant.parse(raw).toEpochMilli() } catch (_: Exception) { null }
            // Storyteller writes "2026-09-27 03:16:47": UTC, a space for the T and no zone.
            // Unparsed, every read counted as never and Red Rising showed book 1 over book 6.
            ?: try { LocalDateTime.parse(raw.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli() }
            catch (_: Exception) { 0 }
    }
}

package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioChapter

/**
 * What an audiobook's Parts sheet lists, its steps go through and its line, times
 * and timeline measure (#19, #31): the chapters the hub found, else the tracks.
 *
 * - **The book's own chapters** (`source` "book"): the read-along edition's table of
 *   contents, each where its narration starts, so a chapter lasts from its moment to
 *   the next one's, across the tracks, and the last to the end of the book. The voice
 *   before the first chapter (the credits) belongs to it, so the book's first entry
 *   starts with the book.
 * - **Chapter marks inside the tracks** (two or more of a file's own, never from file
 *   names; an older hub's chapters, which say no source, are these): an entry never
 *   leaves its file. A track without marks stays one entry among chapters, and a track
 *   whose first mark comes after its start keeps that opening as an entry of its own.
 *
 * A book without chapters is its parts, as it always was.
 */
object AudiobookContents {
    /** [chapter] is false for a part's own opening or a whole track among chapters. */
    data class Entry(val title: String, val part: Int, val startMs: Long, val durationMs: Long?, val chapter: Boolean = false)

    /** An opening shorter than this before a track's first mark is not an entry of its own. */
    const val LEAD_MS = 1_000L
    /** Back from further into an entry than this goes to its own start, as a player's Previous does. */
    const val RESTART_MS = 3_000L

    fun entries(parts: List<AudiobookPart>, partsMs: List<Long?>, chapters: List<ReadingAudioChapter>): List<Entry> {
        // The player's measured length counts before the manifest's; a length of nothing is none.
        val lengths = parts.indices.map { part -> partsMs.getOrNull(part)?.takeIf { it > 0 } ?: parts[part].durationMs?.takeIf { it > 0 } }
        val book = chapters.filter { it.fromBook }
        if (book.isNotEmpty()) bookEntries(lengths, book)?.let { return it }
        return markEntries(parts, lengths, chapters.filterNot { it.fromBook })
    }

    /**
     * The book's chapters across the tracks, in the order they are heard (of two at one
     * moment, the one the hub listed first), or null when none is a place in them. Each lasts
     * until the next starts, the last until the book ends: unknown while a track it runs
     * across has no length.
     */
    private fun bookEntries(lengths: List<Long?>, chapters: List<ReadingAudioChapter>): List<Entry>? {
        val marks = chapters.withIndex()
            .filter { (_, chapter) -> chapter.track in lengths.indices && chapter.startMs >= 0 && lengths[chapter.track].let { it == null || chapter.startMs < it } }
            .sortedWith(compareBy({ it.value.track }, { it.value.startMs }, { it.index }))
            .map { it.value }
            .distinctBy { it.track to it.startMs }
        if (marks.isEmpty()) return null
        val last = lengths.lastIndex
        return marks.mapIndexed { index, mark ->
            // The credits before the first chapter are its: the book's first entry starts with the book.
            val (part, startMs) = if (index == 0) 0 to 0L else mark.track to mark.startMs
            val next = marks.getOrNull(index + 1)
            val duration = when {
                next != null -> Listening.distance(part, startMs, next.track, next.startMs, lengths)
                else -> lengths[last]?.let { Listening.distance(part, startMs, last, it, lengths) }
            }
            Entry(name(mark, index), part, startMs, duration, chapter = true)
        }
    }

    /** Marks inside their files: each track on its own, as the parts were before the book's own chapters. */
    private fun markEntries(parts: List<AudiobookPart>, lengths: List<Long?>, chapters: List<ReadingAudioChapter>): List<Entry> =
        parts.indices.flatMap { part ->
            val title = AudiobookArchive.partLabel(parts[part].title)
            val length = lengths[part]
            val marks = chapters.filter { it.track == part && it.startMs >= 0 && (length == null || it.startMs < length) }
                .sortedBy { it.startMs }.distinctBy { it.startMs }
            if (marks.isEmpty()) return@flatMap listOf(Entry(title, part, 0, length))
            buildList {
                if (marks.first().startMs >= LEAD_MS) add(Entry(title, part, 0, marks.first().startMs))
                marks.forEachIndexed { index, mark ->
                    // A first mark a moment in is the track's start: every track's first entry starts at 0.
                    val start = if (index == 0 && mark.startMs < LEAD_MS) 0L else mark.startMs
                    val end = marks.getOrNull(index + 1)?.startMs ?: length
                    add(Entry(name(mark, index), part, start, end?.minus(start), chapter = true))
                }
            }
        }

    private fun name(chapter: ReadingAudioChapter, index: Int) = chapter.title.ifBlank { "Chapter ${index + 1}" }

    /** What an entry is called: "chapter" where the book has chapters, else "part". */
    fun noun(entries: List<Entry>): String = if (entries.any { it.chapter }) "chapter" else "part"

    /** The entry playing at [positionMs] of [part]: the last one begun, wherever its track began. */
    fun current(entries: List<Entry>, part: Int, positionMs: Long): Int =
        entries.indexOfLast { it.part < part || (it.part == part && it.startMs <= positionMs) }.coerceAtLeast(0)

    /**
     * Where the steps go from [part] at [positionMs]: forward, the next entry, or nothing at
     * the end; back, the entry's own start once [RESTART_MS] into it, else the one before.
     * How far into an entry that began in an earlier track is counted across the tracks
     * ([partsMs]), and taken as well in while a length it needs is unknown.
     */
    fun step(entries: List<Entry>, part: Int, positionMs: Long, delta: Int, partsMs: List<Long?> = emptyList()): Entry? {
        if (entries.isEmpty() || delta == 0) return null
        val here = current(entries, part, positionMs)
        if (delta > 0) return entries.getOrNull(here + delta)
        val entry = entries[here]
        val into = Listening.distance(entry.part, entry.startMs, part, positionMs, partsMs) ?: Long.MAX_VALUE
        return if (into > RESTART_MS) entry else entries.getOrNull(here + delta) ?: entry
    }

    /**
     * Whether the entry playing at the end of [part], [lengthMs] in, ends there: the next part
     * begins another. A part always does; a chapter that runs on into the next track does not,
     * so a timer for the end of the chapter goes on through the change of track.
     */
    fun endsWithPart(entries: List<Entry>, part: Int, lengthMs: Long): Boolean =
        entries.isEmpty() || current(entries, part, lengthMs) != current(entries, part + 1, 0)

    /**
     * What the line under the title, its two times, the timeline and the time left measure:
     * the entry playing, a chapter across tracks or a part, while its length is known ([measuresEntry]);
     * else the part playing, by the player's own length, with the entry still named.
     */
    data class Span(
        /** The entry's place in the contents, or -1 when there is none. */
        val entry: Int,
        val title: String,
        /** Where what is measured starts: the entry's, or the part's. */
        val part: Int,
        val startMs: Long,
        /** How far into it, within its length. */
        val positionMs: Long,
        val durationMs: Long,
        val measuresEntry: Boolean
    ) {
        /** What is left of it, of the recording. */
        val leftMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    }

    /**
     * The span at [positionMs] of [part]; [partMs] is the player's length of the part playing,
     * which counts before the manifest's ([partsMs]).
     */
    fun span(entries: List<Entry>, part: Int, positionMs: Long, partMs: Long, partsMs: List<Long?>): Span {
        val position = positionMs.coerceAtLeast(0)
        // The part's own length, as the player has it, or none while the player has none.
        fun ofPart(entry: Int, title: String) = Span(entry, title, part, 0,
            if (partMs > 0) minOf(position, partMs) else position, partMs.coerceAtLeast(0), measuresEntry = false)
        if (entries.isEmpty()) return ofPart(-1, "")
        val lengths = partsMs.toMutableList().also { if (partMs > 0 && part in it.indices) it[part] = partMs }
        val here = current(entries, part, position)
        val entry = entries[here]
        val next = entries.getOrNull(here + 1)
        val total = if (next != null) Listening.distance(entry.part, entry.startMs, next.part, next.startMs, lengths)
            else lengths.lastOrNull()?.let { Listening.distance(entry.part, entry.startMs, lengths.lastIndex, it, lengths) }
        val into = Listening.distance(entry.part, entry.startMs, part, position, lengths)
        if (total == null || total <= 0 || into == null) return ofPart(here, entry.title)
        return Span(here, entry.title, entry.part, entry.startMs, minOf(into, total), total, measuresEntry = true)
    }

    /** Where [atMs] into [span] is: its part and the moment in it, across tracks when the span is a chapter's. */
    fun place(span: Span, atMs: Long, partsMs: List<Long?>): Pair<Int, Long> {
        val into = atMs.coerceIn(0, span.durationMs.coerceAtLeast(0))
        return if (span.measuresEntry) Listening.jump(span.part, span.startMs, into, partsMs) else span.part to into
    }
}

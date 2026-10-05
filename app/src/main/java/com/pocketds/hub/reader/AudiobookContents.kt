package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioChapter

/**
 * What an audiobook's Parts sheet lists and its part steps go through (#19):
 * the chapters the hub found inside the tracks (from a file's own marks, two or
 * more, never from file names), else the tracks themselves. A track without
 * marks stays one entry among chapters, and a track whose first mark comes
 * after its start keeps that opening as an entry of its own.
 */
object AudiobookContents {
    data class Entry(val title: String, val part: Int, val startMs: Long, val durationMs: Long?)

    /** An opening shorter than this before a track's first mark is not an entry of its own. */
    const val LEAD_MS = 1_000L
    /** Back from further into an entry than this goes to its own start, as a player's Previous does. */
    const val RESTART_MS = 3_000L

    fun entries(parts: List<AudiobookPart>, partsMs: List<Long?>, chapters: List<ReadingAudioChapter>): List<Entry> =
        parts.indices.flatMap { part ->
            val title = AudiobookArchive.partLabel(parts[part].title)
            val length = partsMs.getOrNull(part)?.takeIf { it > 0 } ?: parts[part].durationMs
            val marks = chapters.filter { it.track == part && it.startMs >= 0 && (length == null || it.startMs < length) }
                .sortedBy { it.startMs }.distinctBy { it.startMs }
            if (marks.isEmpty()) return@flatMap listOf(Entry(title, part, 0, length))
            buildList {
                if (marks.first().startMs >= LEAD_MS) add(Entry(title, part, 0, marks.first().startMs))
                marks.forEachIndexed { index, mark ->
                    // A first mark a moment in is the track's start: every track's first entry starts at 0.
                    val start = if (index == 0 && mark.startMs < LEAD_MS) 0L else mark.startMs
                    val end = marks.getOrNull(index + 1)?.startMs ?: length
                    add(Entry(mark.title.ifBlank { "Chapter ${index + 1}" }, part, start, end?.minus(start)))
                }
            }
        }

    /** The entry playing at [positionMs] of [part]: the last one begun. */
    fun current(entries: List<Entry>, part: Int, positionMs: Long): Int =
        entries.indexOfLast { it.part < part || (it.part == part && it.startMs <= positionMs) }.coerceAtLeast(0)

    /**
     * Where the part steps go from [part] at [positionMs]: forward, the next
     * entry, or nothing at the end; back, the entry's own start once
     * [RESTART_MS] into it, else the one before.
     */
    fun step(entries: List<Entry>, part: Int, positionMs: Long, delta: Int): Entry? {
        if (entries.isEmpty() || delta == 0) return null
        val here = current(entries, part, positionMs)
        if (delta > 0) return entries.getOrNull(here + delta)
        val entry = entries[here]
        val into = if (entry.part == part) positionMs - entry.startMs else Long.MAX_VALUE
        return if (into > RESTART_MS) entry else entries.getOrNull(here + delta) ?: entry
    }
}

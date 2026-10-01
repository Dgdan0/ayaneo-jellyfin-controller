package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.state.Fmt

/**
 * The line under a book's title: where it sits in its series, how long it is
 * and who reads it. "Book 6 of Red Rising · 2023 · 735 pages". Pure, so tested.
 */
object ReadingBookFacts {
    /** "Book 6 of Red Rising", "Red Rising" without a number, or null outside a series. */
    fun place(work: ReadingWork): String? {
        if (work.series.isBlank()) return null
        if (work.seriesNumber.isBlank()) return work.series
        return "Book ${work.seriesNumber} of ${work.series}"
    }

    /**
     * How far through: "49% · page 363 of 735" when the book's length is
     * known, "49% read" otherwise, "Finished" once done; null before starting.
     */
    fun progress(work: ReadingWork): String? {
        val p = work.progress ?: return null
        if (p.completed) return "Finished"
        if (p.percentage <= 0) return null
        val percent = "${(p.percentage * 100).toInt()}%"
        val pages = work.editions.filter { it.kind != "audiobook" }.maxOfOrNull { it.pageCount } ?: 0
        return if (pages > 0) "$percent · page ${(p.percentage * pages).toInt().coerceIn(1, pages)} of $pages" else "$percent read"
    }

    /** Pages of the longest text edition and the length of the audiobook, with its narrator. */
    fun length(work: ReadingWork): List<String> = buildList {
        work.editions.filter { it.kind != "audiobook" }.maxOfOrNull { it.pageCount }
            ?.takeIf { it > 0 }?.let { add("$it pages") }
        work.editions.firstOrNull { it.kind == "audiobook" }?.let { audio ->
            Fmt.runtime(audio.durationMs / 1000).takeIf { it.isNotBlank() }?.let(::add)
            audio.narrator.takeIf { it.isNotBlank() }?.let { add("read by $it") }
        }
    }

    /**
     * The whole line. The author is named here only when the hub sent no author
     * link (an older hub): otherwise the link under the actions names them.
     */
    fun line(work: ReadingWork, progress: String?): String = buildList {
        if (work.authorRefs.isEmpty() && work.authors.isNotEmpty()) add(work.authors.joinToString(", "))
        place(work)?.let(::add)
        if (work.year > 0) add(work.year.toString())
        addAll(length(work))
        if (work.genres.isNotEmpty()) add(work.genres.joinToString(", "))
        progress?.let(::add)
    }.joinToString(" · ")
}

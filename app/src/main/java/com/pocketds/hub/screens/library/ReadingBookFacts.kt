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

    /** A series page's progress: "On #6 · 1 of 6 finished", or null before any book is started. */
    fun seriesProgress(series: ReadingWork): String? {
        val books = series.sections.flatMap { it.items }
        val started = books.any { (it.progress?.percentage ?: 0.0) > 0 || it.progress?.completed == true } ||
            (series.progress?.percentage ?: 0.0) > 0
        if (!started) return null
        val total = books.size.takeIf { it > 0 } ?: series.bookCount
        val on = com.pocketds.hub.screens.home.ReadingShelves.onNumber(series)
        return listOfNotNull(on.takeIf(String::isNotBlank)?.let { "On #$it" },
            "${books.count { it.progress?.completed == true }} of $total finished").joinToString(" · ")
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
        // A series the hub links to is a chip of its own under the title.
        if (work.seriesId.isBlank()) place(work)?.let(::add)
        if (work.year > 0) add(work.year.toString())
        addAll(length(work))
        if (work.genres.isNotEmpty()) add(work.genres.joinToString(", "))
        progress?.let(::add)
    }.joinToString(" · ")

    /** The pill on a comic's cover: "Comic", "Manga"; null for a book. */
    fun kindTag(kind: String): String? = when (kind) {
        "comic" -> "Comic"
        "manga" -> "Manga"
        else -> null
    }

    /**
     * Under a comic or manga's cover: "Not started", "1% read", "Finished".
     * Its library name said nothing the row heading did not, and a run of
     * hundreds of issues read one page shows as 0% unless rounded up.
     */
    fun comicLine(progress: com.pocketds.hub.model.ReadingProgress?): String = when {
        progress?.completed == true -> "Finished"
        (progress?.percentage ?: 0.0) > 0 -> "${kotlin.math.ceil(progress!!.percentage * 100).toInt().coerceIn(1, 99)}% read"
        else -> "Not started"
    }
}

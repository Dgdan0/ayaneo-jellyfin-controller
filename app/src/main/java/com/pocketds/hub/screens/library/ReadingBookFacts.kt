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
        // "On issue 51 · 1% read": a page count across a whole run says nothing.
        if (kindTag(work.kind) != null) return listOfNotNull(
            work.continueAt?.number?.takeIf(String::isNotBlank)?.let { if (work.kind == "manga") "On chapter $it" else "On issue $it" },
            comicLine(p)
        ).joinToString(" · ")
        val percent = "${(p.percentage * 100).toInt()}%"
        val pages = work.editions.filter { it.kind != "audiobook" }.maxOfOrNull { it.pageCount } ?: 0
        return if (pages > 0) "$percent · page ${(p.percentage * pages).toInt().coerceIn(1, pages)} of $pages" else "$percent read"
    }

    /** Pages of the longest text edition and the length of the audiobook, with its narrator. */
    fun length(work: ReadingWork): List<String> = buildList {
        // A comic run is counted in issues (chapters for manga): 4,437 pages
        // across 147 issues said nothing useful.
        val issues = work.sections.sumOf { it.items.size }
        if (kindTag(work.kind) != null && issues > 0) {
            add(if (work.kind == "manga") plural(issues, "chapter") else plural(issues, "issue"))
            return@buildList
        }
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

    /**
     * An issue's name on its card: Kavita's own title when it has one, else
     * "Issue 7" (or "Chapter 11" for manga). The page used to say "7 · 7".
     */
    fun issueTitle(item: com.pocketds.hub.model.ReadingSectionItem, kind: String): String {
        val title = item.title.trim()
        if (item.number.isBlank()) return title
        return if (title.isEmpty() || title == item.number) (if (kind == "manga") "Chapter " else "Issue ") + item.number else title
    }

    /** Under an issue's cover: "36 pages · Not started". */
    fun issueLine(item: com.pocketds.hub.model.ReadingSectionItem): String = listOfNotNull(
        item.pageCount.takeIf { it > 0 }?.let { "$it pages" },
        comicLine(item.progress)
    ).joinToString(" · ")

    /**
     * Glass: the line over a book page's title. "Book 6 · Red Rising" in a
     * series, "Series · Pierce Brown" on a series' page, "Comic · My Marvelous
     * Year" on a run (its [library], when known), else what it is: "Book",
     * "Audiobook".
     */
    fun eyebrow(work: ReadingWork, library: String = ""): String {
        kindTag(work.kind)?.let { kind -> return listOf(kind, library).filter(String::isNotBlank).joinToString(" · ") }
        if (work.entityType == "collection") return listOf("Series", work.byline).filter(String::isNotBlank).joinToString(" · ")
        if (work.series.isNotBlank()) return if (work.seriesNumber.isNotBlank()) "Book ${work.seriesNumber} · ${work.series}" else work.series
        return if (work.kind == "audiobook") "Audiobook" else "Book"
    }

    /**
     * Under a book you are also reading, on Glass's Books home: "Blake Crouch ·
     * 3%", "Mistborn Original Trilogy #1 · 1%". Started is never 0%.
     */
    fun miniLine(work: ReadingWork): String = listOfNotNull(
        work.cardSubtitle.takeIf(String::isNotBlank),
        work.progress?.let { p ->
            when {
                p.completed -> "Finished"
                p.percentage > 0 -> "${(p.percentage * 100).toInt().coerceAtLeast(1)}%"
                else -> null
            }
        }
    ).joinToString(" · ")

    /**
     * Glass: under "Continue reading · Light Bringer" on a series' page:
     * "Book 6 · 49% · page 363 of 735", where [pages] is that book's length
     * (0 when unknown); "Issue 51 · 1%" in a comic run.
     */
    fun continueLine(point: com.pocketds.hub.model.ReadingContinue, kind: String, pages: Int): String = listOfNotNull(
        point.number.takeIf(String::isNotBlank)?.let { (when (kind) { "comic" -> "Issue "; "manga" -> "Chapter "; else -> "Book " }) + it },
        point.percentage.takeIf { it > 0 }?.let { "${(it * 100).toInt().coerceAtLeast(1)}%" },
        pages.takeIf { it > 0 && point.percentage > 0 }?.let { "page ${(point.percentage * it).toInt().coerceIn(1, it)} of $it" }
    ).joinToString(" · ")

    /**
     * What a book can be opened as, always in this order: ebook, audiobook,
     * read along. From the hub's list, else from the editions it has.
     */
    fun formats(work: ReadingWork): List<String> {
        val known = work.availability.ifEmpty {
            work.editions.filter { it.availability == "available" }.map { if (it.kind == "book") "ebook" else it.kind }
        }
        return FORMATS.filter { it in known }
    }

    private val FORMATS = listOf("ebook", "audiobook", "readaloud")

    private fun plural(n: Int, one: String) = if (n == 1) "1 $one" else "$n ${one}s"
}

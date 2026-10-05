package com.pocketds.hub.reader

enum class PageDirection { LTR, RTL, TOP_TO_BOTTOM }

object ReaderTitleFormatter {
    fun format(seriesTitle: String, publicationTitle: String, fallback: String): String {
        val series = seriesTitle.trim()
        val publication = publicationTitle.trim()
        return when {
            series.isBlank() && publication.isBlank() -> fallback.trim()
            series.isBlank() -> publication
            publication.isBlank() || series.equals(publication, ignoreCase = true) -> series
            else -> "$series · $publication"
        }
    }

    /** The reader's heading: the series, or the publication when it has none. */
    fun heading(seriesTitle: String, publicationTitle: String, fallback: String): String =
        seriesTitle.trim().ifBlank { publicationTitle.trim() }.ifBlank { fallback.trim() }

    /**
     * Under the heading, which issue this is: Kavita names a comic's chapters
     * "Chapter 51", but a comic has issues. A manga keeps chapters. Empty when
     * the publication is the series itself.
     */
    fun issue(kind: String, seriesTitle: String, publicationTitle: String, number: String): String {
        val title = publicationTitle.trim()
        val n = number.trim().ifBlank { CHAPTER.matchEntire(title)?.groupValues?.get(1).orEmpty() }
        val generic = title.isBlank() || title == n || CHAPTER.matches(title)
        return when {
            title.equals(seriesTitle.trim(), ignoreCase = true) && n.isBlank() -> ""
            !generic -> title
            n.isBlank() -> ""
            kind == "manga" -> "Chapter $n"
            else -> "Issue $n"
        }
    }

    /**
     * "Issue 51 · Page 2 of 24", and where on the page when it is read in
     * steps: "Part 2 of 3" ([part] of [parts], from 1; none for a page read whole).
     */
    fun subtitle(issue: String, page: Int, pageCount: Int, part: Int? = null, parts: Int = 0): String = listOfNotNull(
        issue.ifBlank { null },
        "Page $page of $pageCount".takeIf { pageCount > 0 },
        part?.takeIf { parts > 1 }?.let { part(it, parts) }
    ).joinToString(" · ")

    /** "Part 2 of 3": a step down a page read in thirds. */
    fun part(part: Int, parts: Int): String = "Part $part of $parts"

    private val CHAPTER = Regex("""(?i)chapter\s*(\S+)""")
}

object ReaderControlFocusPolicy {
    /** The last registered control is the forward-page action. */
    fun initialIndex(controlCount: Int): Int? = if (controlCount > 0) controlCount - 1 else null
}

data class PageDimension(
    val index: Int,
    val width: Int,
    val height: Int,
    val isWide: Boolean = width > height
)

data class PageSpread(
    val leftPage: Int?,
    val rightPage: Int?,
    val direction: PageDirection
) {
    val readingOrder: List<Int>
        get() = when (direction) {
            PageDirection.RTL -> listOfNotNull(rightPage, leftPage)
            PageDirection.LTR, PageDirection.TOP_TO_BOTTOM -> listOfNotNull(leftPage, rightPage)
        }
}

object SpreadPlanner {
    /**
     * Builds visual spreads while keeping page zero isolated as a cover and
     * wide scans isolated so neither page is shrunk beside one.
     */
    fun plan(
        pages: List<PageDimension>,
        direction: PageDirection,
        coverAlone: Boolean = true
    ): List<PageSpread> {
        if (pages.isEmpty()) return emptyList()
        val ordered = pages.sortedBy { it.index }
        val result = mutableListOf<PageSpread>()
        var cursor = 0
        if (coverAlone) {
            result += single(ordered.first().index, direction)
            cursor = 1
        }
        while (cursor < ordered.size) {
            val current = ordered[cursor]
            val next = ordered.getOrNull(cursor + 1)
            if (current.isWide || next == null || next.isWide) {
                result += single(current.index, direction)
                cursor++
                continue
            }
            result += if (direction == PageDirection.RTL) {
                PageSpread(leftPage = next.index, rightPage = current.index, direction = direction)
            } else {
                PageSpread(leftPage = current.index, rightPage = next.index, direction = direction)
            }
            cursor += 2
        }
        return result
    }

    private fun single(page: Int, direction: PageDirection) = when (direction) {
        PageDirection.RTL -> PageSpread(leftPage = null, rightPage = page, direction = direction)
        PageDirection.LTR, PageDirection.TOP_TO_BOTTOM ->
            PageSpread(leftPage = page, rightPage = null, direction = direction)
    }
}

data class NormalizedViewport(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double
)

/**
 * Reading a page in thirds (#16, C2): the page fitted to the view's width and
 * read down it, every step moving on by at most 1 - [OVERLAP] of a screen, so
 * nothing is cropped and each step shows a little of the one before. How many
 * steps comes from the page's shape and the screen's, not a constant three:
 * on this 16:9 screen a comic or manga page takes 3, a two-page spread 2, and
 * a page whose height fits at that width 1. Steps run top to bottom whatever
 * the reading direction.
 */
object ViewportStepPlanner {
    const val OVERLAP = 0.12
    /** A long strip still ends: past this the steps space out rather than multiply. */
    const val MAX_STEPS = 48

    /** How many steps a [pageWidth] by [pageHeight] page takes in a [viewWidth] by [viewHeight] view. */
    fun count(pageWidth: Int, pageHeight: Int, viewWidth: Int, viewHeight: Int, overlap: Double = OVERLAP): Int {
        if (pageWidth <= 0 || pageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return 1
        val visible = viewHeight.toDouble() * pageWidth / viewWidth
        val ratio = pageHeight / visible
        if (ratio <= 1.0 + 1e-9) return 1
        return kotlin.math.ceil(1.0 + (ratio - 1.0) / (1.0 - overlap) - 1e-9).toInt().coerceIn(2, MAX_STEPS)
    }

    /** The steps down the page, as fractions of it, first to last. */
    fun fitWidth(pageWidth: Int, pageHeight: Int, viewWidth: Int, viewHeight: Int, overlap: Double = OVERLAP): List<NormalizedViewport> {
        val steps = count(pageWidth, pageHeight, viewWidth, viewHeight, overlap)
        if (steps == 1) return listOf(NormalizedViewport(0.0, 0.0, 1.0, 1.0))
        val visible = (viewHeight.toDouble() * pageWidth / viewWidth / pageHeight).coerceAtMost(1.0)
        return List(steps) { index ->
            val top = index * (1.0 - visible) / (steps - 1)
            NormalizedViewport(0.0, top, 1.0, top + visible)
        }
    }
}

/**
 * Source-pixel movement for controller navigation within a comic page. The
 * range moved over is [sourceSize] long from [from]: the whole page, or its
 * content inside the paper border while that is trimmed (#18, C5).
 */
object ComicPanPolicy {
    fun edge(sourceSize: Int, visibleSize: Float, high: Boolean, from: Float = 0f): Float {
        val halfVisible = visibleSize / 2f
        return from + if (sourceSize <= visibleSize) sourceSize / 2f
        else if (high) sourceSize - halfVisible else halfVisible
    }

    fun step(center: Float, sourceSize: Int, visibleSize: Float, direction: Int, distance: Float = 0.82f, from: Float = 0f): Float? {
        require(direction == -1 || direction == 1)
        val halfVisible = visibleSize / 2f
        val lower = from + halfVisible
        val upper = from + sourceSize - halfVisible
        if (upper <= lower + 1f) return null
        val next = (center + direction * visibleSize * distance).coerceIn(lower, upper)
        return next.takeIf { kotlin.math.abs(it - center) > 1f }
    }
}

/**
 * Where in an issue: the page, and the step down it when it is read in steps.
 * Each page takes as many steps as [stepsFor] says (#16, C2: from its shape),
 * so moving back onto a page lands on its last step.
 */
class PagedImageState(
    val pageCount: Int,
    startPage: Int = 0,
    private val stepsFor: (Int) -> Int = { 1 },
    startStep: Int = 0
) {
    /** Every page the same number of steps. */
    constructor(pageCount: Int, startPage: Int, viewportSteps: Int) : this(pageCount, startPage, { viewportSteps })

    init {
        require(pageCount > 0) { "a publication needs at least one page" }
    }

    var pageIndex: Int = startPage.coerceIn(0, pageCount - 1)
        private set
    var viewportIndex: Int = startStep.coerceIn(0, steps(pageIndex) - 1)
        private set

    /** How many steps the current page takes. */
    val viewportSteps: Int get() = steps(pageIndex)

    private fun steps(page: Int): Int = stepsFor(page).coerceAtLeast(1)

    /** The current page's step count changed (it decoded at another size, a zoom began): stay inside it. */
    fun refit() {
        viewportIndex = viewportIndex.coerceIn(0, viewportSteps - 1)
    }

    /** A page and a step on it, as a reading list or a saved place asks. */
    fun jump(page: Int, step: Int) {
        pageIndex = page.coerceIn(0, pageCount - 1)
        viewportIndex = step.coerceIn(0, viewportSteps - 1)
    }

    val progression: Double
        get() = if (pageCount == 1) 1.0 else pageIndex.toDouble() / (pageCount - 1)
    val completed: Boolean get() = pageIndex == pageCount - 1

    fun seek(page: Int) {
        pageIndex = page.coerceIn(0, pageCount - 1)
        viewportIndex = 0
    }

    /** X/Y skip all viewport steps, including when the current page is split into thirds. */
    fun turnPage(delta: Int): Boolean {
        require(delta == -1 || delta == 1)
        val next = pageIndex + delta
        if (next !in 0 until pageCount) return false
        pageIndex = next
        viewportIndex = if (delta > 0) 0 else steps(next) - 1
        return true
    }

    fun advance(): Boolean {
        if (viewportIndex < viewportSteps - 1) {
            viewportIndex++
            return true
        }
        if (pageIndex >= pageCount - 1) return false
        pageIndex++
        viewportIndex = 0
        return true
    }

    fun retreat(): Boolean {
        if (viewportIndex > 0) {
            viewportIndex--
            return true
        }
        if (pageIndex <= 0) return false
        pageIndex--
        viewportIndex = steps(pageIndex) - 1
        return true
    }
}

/**
 * The card at the end of an issue (#16, C6): "End of Fantastic Four #51" and
 * what A goes on to, "Next: #52". Words only, so tested.
 */
object EndOfIssue {
    /** "Fantastic Four #51"; a named issue keeps its name ("Fantastic Four · Annual 1965"). */
    fun name(series: String, title: String, number: String): String {
        val run = series.trim()
        val label = title.trim()
        // The number: given, or read from "Chapter 51", "Issue 51" or a bare "51".
        val n = number.trim().ifBlank {
            CHAPTER.matchEntire(label)?.groupValues?.get(1) ?: label.takeIf { NUMBER.matches(it) }?.removePrefix("#").orEmpty()
        }
        val generic = label.isBlank() || label.removePrefix("#") == n || CHAPTER.matches(label) || label.equals(run, ignoreCase = true)
        return when {
            !generic -> listOf(run, label).filter(String::isNotBlank).joinToString(" · ")
            n.isNotBlank() -> listOf(run, "#$n").filter(String::isNotBlank).joinToString(" ")
            else -> run.ifBlank { "this issue" }
        }
    }

    fun heading(series: String, title: String, number: String): String = "End of " + name(series, title, number)

    /**
     * What comes next: the same run's next issue by its number alone
     * ("Next: #52"), another series' by its name, or why there is none.
     */
    fun next(currentSeries: String, nextSeries: String?, nextTitle: String, nextNumber: String, readingList: Boolean): String {
        if (nextSeries == null) return if (readingList) "End of the reading list" else "That was the last issue"
        val sameRun = nextSeries.trim().equals(currentSeries.trim(), ignoreCase = true)
        val label = name(if (sameRun) "" else nextSeries, nextTitle, nextNumber)
        return "Next: " + label.ifBlank { "the next issue" }
    }

    private val CHAPTER = Regex("""(?i)(?:chapter|issue)\s*#?(\S+)""")
    private val NUMBER = Regex("""#?\d+(?:\.\d+)?[a-zA-Z]?""")
}

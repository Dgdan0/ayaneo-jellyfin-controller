package com.pocketds.hub.reader

enum class PageDirection { LTR, RTL, TOP_TO_BOTTOM }

enum class ViewportAxis { HORIZONTAL, VERTICAL }

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

object ViewportStepPlanner {
    fun steps(
        axis: ViewportAxis,
        direction: PageDirection,
        count: Int = 3,
        overlapFraction: Double = 0.12
    ): List<NormalizedViewport> {
        require(count > 0) { "viewport count must be positive" }
        require(overlapFraction in 0.0..0.5) { "overlap must be between zero and one half" }
        if (count == 1) return listOf(NormalizedViewport(0.0, 0.0, 1.0, 1.0))
        val extent = 1.0 / (count - (count - 1) * overlapFraction)
        val stride = extent * (1.0 - overlapFraction)
        val values = List(count) { index ->
            val start = if (index == count - 1) 1.0 - extent else index * stride
            val end = if (index == count - 1) 1.0 else start + extent
            if (axis == ViewportAxis.HORIZONTAL) {
                NormalizedViewport(start, 0.0, end, 1.0)
            } else {
                NormalizedViewport(0.0, start, 1.0, end)
            }
        }
        return if (axis == ViewportAxis.HORIZONTAL && direction == PageDirection.RTL) {
            values.reversed()
        } else {
            values
        }
    }
}

class PagedImageState(
    val pageCount: Int,
    startPage: Int = 0,
    val viewportSteps: Int = 1
) {
    init {
        require(pageCount > 0) { "a publication needs at least one page" }
        require(viewportSteps > 0) { "viewport step count must be positive" }
    }

    var pageIndex: Int = startPage.coerceIn(0, pageCount - 1)
        private set
    var viewportIndex: Int = 0
        private set

    val progression: Double
        get() = if (pageCount == 1) 1.0 else pageIndex.toDouble() / (pageCount - 1)
    val completed: Boolean get() = pageIndex == pageCount - 1

    fun seek(page: Int) {
        pageIndex = page.coerceIn(0, pageCount - 1)
        viewportIndex = 0
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
        viewportIndex = viewportSteps - 1
        return true
    }
}

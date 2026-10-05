package com.pocketds.hub.reader

import kotlin.math.abs
import kotlin.math.min

/**
 * Where a comic page's content sits inside its paper, as fractions of the
 * page (#18, C5): 0 to 1 across from the left, 0 to 1 down from the top.
 */
data class PageContent(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val trimmed: Boolean get() = left > 0.0 || top > 0.0 || right < 1.0 || bottom < 1.0

    /** [step] (a fraction of the content) as a fraction of the whole page: for the page's map. */
    fun onPage(step: NormalizedViewport): NormalizedViewport = NormalizedViewport(
        left + step.left * width, top + step.top * height, left + step.right * width, top + step.bottom * height)

    companion object {
        val WHOLE = PageContent(0.0, 0.0, 1.0, 1.0)
    }
}

/**
 * Finds the plain paper border round a comic page (#18, C5), on the hub's
 * small thumbnail of it ([THUMB_WIDTH] pixels across, never the scan), so the
 * reader can treat the content as the page: fitted and read in steps without
 * the margin, which is most of a page's white on these scans.
 *
 * Each side on its own: its outermost line must be one colour, and a pale
 * one (paper, from white to yellowed) or a near-black one (a manga's frame);
 * then line after line inward while nearly all of a line stays within
 * [TOLERANCE] of it. Art running to an edge stops that side at once, a flat
 * sky of colour included (a red band at the top of a page is art, not
 * paper), a panel's border or a caption stops it where it starts, and a page
 * that is all paper is left whole. One thumbnail pixel goes back on
 * each side trimmed, since scaling down blurs the content's edge into the
 * paper. However much is found, no more than [MAX_TRIM] of the page goes on
 * either axis, so a wide margin on purpose (a splash, a letter column)
 * cannot blow the page up.
 *
 * Pure: an array of luma values in, fractions out.
 */
object PageBounds {
    /** How wide the thumbnail asked for is: the hub's H1 route, 96 pixels across. */
    const val THUMB_WIDTH = 96
    /** Never more than this of the page across, nor down. */
    const val MAX_TRIM = 0.12
    /** Luma levels from the paper's that still count as paper: JPEG noise and paper grain. */
    const val TOLERANCE = 24
    /** How much of a line must be paper: a few stray pixels (a page number's dot) do not end the margin. */
    const val COVERAGE = 0.96
    /** Less than this on an axis is noise, not a margin. */
    const val MIN_TRIM = 0.012
    /** A border is paper at least this light, or a frame at most [DARKEST_FRAME]; anything between is art. */
    const val PALEST_ART = 185
    const val DARKEST_FRAME = 60

    /** Rec. 601 luma of an ARGB colour, 0 to 255. */
    fun luma(argb: Int): Int = ((argb shr 16 and 0xFF) * 299 + (argb shr 8 and 0xFF) * 587 + (argb and 0xFF) * 114) / 1000

    /**
     * The content inside the border of a [width] by [height] thumbnail whose
     * luma values, row by row, are [luma]. [PageContent.WHOLE] when there is
     * no border to trim.
     */
    fun detect(luma: IntArray, width: Int, height: Int): PageContent {
        if (width < 8 || height < 8 || luma.size < width * height) return PageContent.WHOLE
        fun row(y: Int, from: Int = 0, to: Int = width) = IntArray(to - from) { luma[y * width + from + it] }
        fun column(x: Int, from: Int, to: Int) = IntArray(to - from) { luma[(from + it) * width + x] }

        var top = margin(height) { row(it) }
        var bottom = margin(height) { row(height - 1 - it) }
        if (top + bottom >= height) return PageContent.WHOLE
        // The sides only over the rows left, so a dark band across the top does not hide a white side.
        val firstRow = top
        val endRow = height - bottom
        var left = margin(width) { column(it, firstRow, endRow) }
        var right = margin(width) { column(width - 1 - it, firstRow, endRow) }
        if (left + right >= width) return PageContent.WHOLE
        top = (top - 1).coerceAtLeast(0)
        bottom = (bottom - 1).coerceAtLeast(0)
        left = (left - 1).coerceAtLeast(0)
        right = (right - 1).coerceAtLeast(0)
        val (x0, x1) = axis(left.toDouble() / width, right.toDouble() / width)
        val (y0, y1) = axis(top.toDouble() / height, bottom.toDouble() / height)
        return PageContent(x0, y0, 1.0 - x1, 1.0 - y1)
    }

    /**
     * How much bigger the content reads than the page, at a fit to the width
     * ([fitWidth]) or of the whole page into a [viewWidth] by [viewHeight]
     * view: what trimming gains.
     */
    fun gain(content: PageContent, pageWidth: Int, pageHeight: Int, viewWidth: Int, viewHeight: Int, fitWidth: Boolean): Double {
        if (pageWidth <= 0 || pageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0 || content.width <= 0.0 || content.height <= 0.0) return 1.0
        if (fitWidth) return 1.0 / content.width
        val whole = min(viewWidth.toDouble() / pageWidth, viewHeight.toDouble() / pageHeight)
        val trimmed = min(viewWidth / (pageWidth * content.width), viewHeight / (pageHeight * content.height))
        return trimmed / whole
    }

    /** How many lines from one edge are border: none unless the outermost line is a single colour. */
    private fun margin(count: Int, line: (Int) -> IntArray): Int {
        val first = line(0)
        val paper = median(first)
        if (paper in (DARKEST_FRAME + 1) until PALEST_ART || !paper(first, paper)) return 0
        var n = 1
        while (n < count && paper(line(n), paper)) n++
        return n
    }

    private fun paper(values: IntArray, paper: Int): Boolean =
        values.count { abs(it - paper) <= TOLERANCE } >= values.size * COVERAGE

    private fun median(values: IntArray): Int = values.sortedArray()[values.size / 2]

    /** Two sides' trims on one axis: noise dropped, and at most [MAX_TRIM] between them, shared as found. */
    private fun axis(first: Double, second: Double): Pair<Double, Double> {
        val total = first + second
        if (total < MIN_TRIM) return 0.0 to 0.0
        if (total <= MAX_TRIM) return first to second
        val scale = MAX_TRIM / total
        return first * scale to second * scale
    }
}

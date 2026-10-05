package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint

/**
 * The Pages grid and the scrubber's preview (#16, C4), the arithmetic only:
 * how wide a thumbnail to ask the hub for, how many columns fit, and where the
 * cursor goes. Pure, so a JVM test pins it.
 */
object PageGrid {
    /**
     * One width for the scrubber's preview and the grid's cells, so a page
     * the preview fetched is already in the cache for the grid. 240 pixels
     * is a cell's width on the Pocket (about 107dp at 2.25), so nothing is
     * scaled up.
     */
    const val THUMB_WIDTH = 240
    /** A cell, thumbnail and gap: seven across the Pocket's 853dp. */
    const val CELL_DP = 112
    /** A page's shape until its thumbnail says otherwise: a comic page's 2:3. */
    const val THUMB_ASPECT = 1.5f

    fun columns(widthDp: Int): Int = (widthDp / CELL_DP).coerceIn(3, 9)

    /** The grid's own key row: it covers the reader's while it is open. */
    val HINTS: List<ButtonHint> = listOf(
        ButtonHint(ReaderPadMap.A, "Open page", PadAction.Activate),
        ButtonHint(ReaderPadMap.B, "Close", PadAction.Back),
        ButtonHint(ReaderPadMap.L2, "Earlier pages", PadAction.Page(Direction.UP)),
        ButtonHint(ReaderPadMap.R2, "Later pages", PadAction.Page(Direction.DOWN))
    )

    /**
     * The cell [direction] reaches from [index] in a grid of [columns] and
     * [count] cells: left and right run on across rows, up and down keep the
     * column, and the edges hold.
     */
    fun move(index: Int, direction: Direction, columns: Int, count: Int): Int {
        if (count <= 0) return 0
        val from = index.coerceIn(0, count - 1)
        val to = when (direction) {
            Direction.LEFT -> from - 1
            Direction.RIGHT -> from + 1
            Direction.UP -> if (from - columns >= 0) from - columns else from
            Direction.DOWN -> if (from + columns <= count - 1) from + columns else if (from / columns < (count - 1) / columns) count - 1 else from
        }
        return to.coerceIn(0, count - 1)
    }

    /** L2 and R2: a screenful of [rows] rows up or down, keeping the column. */
    fun page(index: Int, delta: Int, columns: Int, rows: Int, count: Int): Int {
        if (count <= 0) return 0
        val target = index + delta * columns * rows.coerceAtLeast(1)
        return when {
            target < 0 -> index % columns
            target > count - 1 -> count - 1
            else -> target
        }
    }
}

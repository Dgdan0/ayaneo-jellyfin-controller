package com.pocketds.hub.state

import kotlin.math.floor

/** Chooses poster columns from the content width left after the navigation rail. */
object LibraryGridSizing {
    private const val TARGET_CELL_DP = 108f

    fun columns(
        widthPx: Int,
        horizontalPaddingPx: Int,
        density: Float,
        maxColumns: Int = 7
    ): Int {
        if (widthPx <= 0 || density <= 0f) return maxColumns.coerceAtLeast(1)
        val maximum = maxColumns.coerceAtLeast(1)
        val minimum = minOf(2, maximum)
        val availablePx = (widthPx - horizontalPaddingPx).coerceAtLeast(1)
        val measured = floor(availablePx / (TARGET_CELL_DP * density)).toInt()
        return measured.coerceIn(minimum, maximum)
    }
}

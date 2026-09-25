package com.pocketds.hub.ui

import kotlin.math.roundToInt

/** Shared selector geometry; margins also leave room for focused cards to grow. */
object LibraryTileSizing {
    private const val OUTER_PADDING = 16
    private const val ITEM_SPACING = 24

    fun columnsFor(availableWidthDp: Int): Int = when {
        availableWidthDp >= 650 -> 3
        availableWidthDp >= 400 -> 2
        else -> 1
    }

    fun cardWidthFor(availableWidthDp: Int): Int {
        val count = columnsFor(availableWidthDp)
        return ((availableWidthDp - 2 * OUTER_PADDING - count * ITEM_SPACING) / count)
            .coerceAtLeast(1)
    }

    fun heightForWidth(width: Int): Int = (width * 9f / 16f).roundToInt().coerceAtLeast(1)
}

/** Preserve explicit folder banners when a deployed Hub predates imageStyle. */
object LibraryArtworkStyle {
    fun media(style: String, libraryId: String, image: String): String = when {
        style.isNotBlank() -> style
        libraryId.isNotBlank() && image.contains("/$libraryId/Primary") -> "banner"
        else -> "poster"
    }
}

object LibraryArtworkRefresh {
    fun needed(lastLoadedDay: String, currentDay: String): Boolean = lastLoadedDay != currentDay
}

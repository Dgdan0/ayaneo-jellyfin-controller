package com.pocketds.hub.reader

/**
 * Where the dictionary card or the selection bar goes (#62): beside the words, never on them. Below the selection when the room
 * under it holds the card, else above it; centred on the selection and kept inside the page. Plain numbers, so a JVM test pins it.
 */
object CardPlacement {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2
    }

    /** [x] and [y] are the card's top-left corner; [above] says it is over the selection, [beside] at its side; [covers] that there was no room for it anywhere. */
    data class Place(val x: Float, val y: Float, val above: Boolean, val covers: Boolean, val beside: Boolean = false)

    /**
     * [anchor] is the selection's rectangle, [bounds] the part of the screen the card may use (clear of the bars), [gap] the room
     * left between them. Below the words when the card fits there, else above them, else at their side (a word is narrow, and a page
     * is wide), and only then over them: a card taller than every side has the larger of the vertical ones and covers as little as it must.
     */
    fun place(anchor: Box, cardWidth: Float, cardHeight: Float, bounds: Box, gap: Float): Place {
        val x = (anchor.centerX - cardWidth / 2).coerceIn(bounds.left, maxOf(bounds.left, bounds.right - cardWidth))
        val roomBelow = bounds.bottom - anchor.bottom - gap
        val roomAbove = anchor.top - gap - bounds.top
        val side = (((anchor.top + anchor.bottom) / 2) - cardHeight / 2).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - cardHeight))
        return when {
            roomBelow >= cardHeight -> Place(x, anchor.bottom + gap, above = false, covers = false)
            roomAbove >= cardHeight -> Place(x, anchor.top - gap - cardHeight, above = true, covers = false)
            cardHeight <= bounds.height && bounds.right - anchor.right - gap >= cardWidth ->
                Place(anchor.right + gap, side, above = false, covers = false, beside = true)
            cardHeight <= bounds.height && anchor.left - gap - bounds.left >= cardWidth ->
                Place(anchor.left - gap - cardWidth, side, above = false, covers = false, beside = true)
            roomBelow >= roomAbove -> Place(x, (bounds.bottom - cardHeight).coerceAtLeast(bounds.top), above = false, covers = true)
            else -> Place(x, bounds.top, above = true, covers = true)
        }
    }
}

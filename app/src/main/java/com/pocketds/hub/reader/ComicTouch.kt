package com.pocketds.hub.reader

import kotlin.math.abs

/**
 * What a finger does on a comic's page (#18, C7), as the keys do it: a tap
 * in the outer third reads on or back (Ⓐ and Ⓑ), a tap in the middle shows
 * or hides the controls (Start), and a swipe across turns the page while the
 * page is not zoomed.
 *
 * The page view keeps everything else: a drag pans, a pinch and a double tap
 * zoom, and the right stick pans as it always has. So a swipe turns nothing
 * while the page is zoomed past its fit (it pans there), nothing that was
 * part of a pinch, and nothing mostly up or down (that scrolls down a page
 * read at its width). Right to left, the sides swap: the next page is on the
 * left.
 *
 * Pure: positions and speeds in pixels, a decision out.
 */
object ComicTouch {
    enum class Tap { BACK, CONTROLS, FORWARD }

    /** A swipe must cross this much of the view, and be this much more across than down. */
    const val SWIPE_SHARE = 0.12f
    const val SWIPE_SLOPE = 1.5f

    /** A tap at [x] across a view [width] wide. With the controls showing, any tap on the page hides them. */
    fun tap(x: Float, width: Int, rtl: Boolean, controlsVisible: Boolean): Tap {
        if (controlsVisible || width <= 0) return Tap.CONTROLS
        val third = width / 3f
        return when {
            x < third -> if (rtl) Tap.FORWARD else Tap.BACK
            x > width - third -> if (rtl) Tap.BACK else Tap.FORWARD
            else -> Tap.CONTROLS
        }
    }

    /**
     * A fling that moved [dx] by [dy] across a view [width] wide: +1 for the
     * next page, -1 for the one before, 0 to leave it to the page view.
     * [zoomed]: the page is past its fit, so a drag pans it. [pinched]: a
     * second finger was down during the gesture, or the scale changed in it.
     */
    fun swipe(dx: Float, dy: Float, width: Int, rtl: Boolean, zoomed: Boolean, pinched: Boolean): Int {
        if (zoomed || pinched || width <= 0) return 0
        if (abs(dx) < width * SWIPE_SHARE || abs(dx) < abs(dy) * SWIPE_SLOPE) return 0
        // A finger moving left brings the next page in from the right, reading left to right.
        val towardLeft = dx < 0f
        return if (towardLeft != rtl) 1 else -1
    }
}

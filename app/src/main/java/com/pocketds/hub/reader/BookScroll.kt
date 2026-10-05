package com.pocketds.hub.reader

import kotlin.math.abs

/**
 * Reading a scrolling book with the D-pad and the right stick (#18, E1), in
 * the whole pixels a web view scrolls by.
 *
 * The right stick asks for a little each frame ([com.pocketds.hub.input.AnalogPan]):
 * at a gentle push that is less than a pixel, which rounded down to nothing,
 * so the text stood still until the stick was pushed hard. The part of a pixel
 * left over is carried into the next frame instead.
 *
 * Readium scrolls each part of a book (each of its files) in a web view of its
 * own, so scrolling stopped dead at the foot of a chapter. Scrolling on goes
 * into the next part now: the D-pad at once, the stick once it has pushed on
 * past the end by [edgePx], so a glide that only reaches the end does not tip
 * into the next chapter. Past the top goes back a part.
 *
 * Pure: amounts and whether the view can scroll in, a move out.
 */
class BookScroll(private val edgePx: Float) {
    sealed interface Move {
        /** Scroll the part on screen by [px], down when positive. */
        data class By(val px: Int) : Move
        data object NextPart : Move
        data object PreviousPart : Move
        data object Stay : Move
    }

    private var carry = 0f
    private var pushed = 0f

    /**
     * The right stick: [px] this frame, down when positive. [canDown] and
     * [canUp] say whether the part on screen can scroll that way.
     */
    fun glide(px: Float, canDown: Boolean, canUp: Boolean): Move {
        if (px == 0f || px.isNaN()) return Move.Stay
        val blocked = if (px > 0f) !canDown else !canUp
        if (blocked) {
            carry = 0f
            // A push against the end builds up in one direction; turning back starts again.
            pushed = if (pushed != 0f && (pushed > 0f) == (px > 0f)) pushed + px else px
            if (abs(pushed) < edgePx) return Move.Stay
            pushed = 0f
            return if (px > 0f) Move.NextPart else Move.PreviousPart
        }
        pushed = 0f
        val total = carry + px
        val whole = total.toInt()
        carry = total - whole
        return if (whole == 0) Move.Stay else Move.By(whole)
    }

    /** The D-pad: [px] at once, down when positive; at the end of a part, the part after (or before). */
    fun step(px: Int, canDown: Boolean, canUp: Boolean): Move {
        reset()
        return when {
            px == 0 -> Move.Stay
            px > 0 && !canDown -> Move.NextPart
            px < 0 && !canUp -> Move.PreviousPart
            else -> Move.By(px)
        }
    }

    /** A new part on screen, or the reader moved some other way: nothing carried, no push built up. */
    fun reset() {
        carry = 0f
        pushed = 0f
    }
}

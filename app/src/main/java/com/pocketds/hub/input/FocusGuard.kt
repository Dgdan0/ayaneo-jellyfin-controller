package com.pocketds.hub.input

/**
 * A rectangle on screen, in pixels. Plain values so this stays JVM-testable.
 */
data class FocusRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Whether a directional press should be allowed to land where the framework
 * wants to put it.
 *
 * Android's `FocusFinder` is a *global* search: when nothing lies to the right
 * of the last card in a row, it happily returns the leftmost card instead, and
 * the selection appears to teleport back to the start of the row. On a
 * horizontally paging row that is actively fetching more items, this is worse
 * than doing nothing — you press right expecting the next poster and get thrown
 * back to the beginning.
 *
 * So the rule is: a press only moves the selection if the candidate genuinely
 * lies in the pressed direction. Refusing the move leaves the cursor where it
 * was, which is exactly the right behaviour while more content is loading, and
 * an honest "that is the end" when there is none.
 *
 * Kept pure and separate from the view code because it is easy to get subtly
 * wrong: a strict inequality alone rejects a legitimately-next card that starts
 * a pixel to the left, and allowing any off-row candidate let the selection
 * escape upward into the tab bar.
 */

/**
 * How left and right should behave on a screen.
 */
enum class HorizontalMode {
    /**
     * Left and right stay inside the band they started in.
     *
     * The right answer for a row of posters, a cast strip, or a tab bar: rows
     * are navigated with up and down, so a sideways press that runs out of
     * items should do nothing at all.
     */
    CONFINED,

    /**
     * Left and right may fall onto the next or previous line.
     *
     * The right answer for a grid, where reading order continues on the line
     * below and stopping dead at the right-hand edge would be wrong.
     */
    GRID
}

object FocusGuard {

    /**
     * How much of the candidate may sit "behind" the current position before
     * the move counts as backwards.
     *
     * Not zero. Cards in a row are not pixel-aligned with the row above, and a
     * focused card is scaled up 8%, so the rect of the thing you are on is a
     * few pixels wider than its neighbours. A fraction of the current size
     * absorbs that without letting a genuine wrap-around through.
     */
    private const val SLACK_FRACTION = 0.5f

    fun accepts(
        direction: Direction,
        from: FocusRect,
        to: FocusRect,
        mode: HorizontalMode = HorizontalMode.CONFINED
    ): Boolean = when (direction) {
        // Vertical moves are left alone. Focus search up and down behaves
        // itself here -- rows are stacked, so there is always something in the
        // right direction or nothing at all -- and second-guessing it would
        // break the seams between the tab bar, the content and the hint bar.
        Direction.UP, Direction.DOWN -> true

        Direction.RIGHT -> if (overlapsVertically(from, to)) {
            to.left > from.left - slack(from)
        } else {
            // Leaving the band. Allowed only on a grid, and only downward:
            // without the direction check, running off the end of a row found
            // the tab bar *above* it and the selection left the content
            // entirely. Measured on the device, pressing right past the last
            // loaded poster.
            mode == HorizontalMode.GRID && to.top > from.top
        }

        Direction.LEFT -> if (overlapsVertically(from, to)) {
            to.left < from.left + slack(from)
        } else {
            mode == HorizontalMode.GRID && to.top < from.top
        }
    }

    private fun slack(from: FocusRect): Int = (from.width * SLACK_FRACTION).toInt()

    /**
     * Whether two rects share any vertical extent, which is how "the same row"
     * is decided without either of them knowing what a row is.
     */
    fun overlapsVertically(a: FocusRect, b: FocusRect): Boolean =
        a.top < b.bottom && b.top < a.bottom
}

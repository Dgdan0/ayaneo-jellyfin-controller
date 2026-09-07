package com.pocketds.hub.state

import com.pocketds.hub.input.Direction

/** Where a floating window sits. Four positions, not free pixels. */
enum class Corner {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

    val isTop: Boolean get() = this == TOP_LEFT || this == TOP_RIGHT
    val isLeft: Boolean get() = this == TOP_LEFT || this == BOTTOM_LEFT
}

data class WindowBounds(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2
}

/**
 * The position and size of the floating trailer window.
 *
 * **Corners, not free dragging, for the gamepad.** Nudging a window pixel by
 * pixel with a D-pad is miserable; four snap positions are one press each and
 * always land somewhere sensible. A pointer still drags freely and snaps to the
 * nearest corner on release, so both input paths get the behaviour that suits
 * them and there is only one piece of state.
 *
 * Pure, so the awkward parts — clamping to the parent, keeping a 16:9 shape,
 * what "down" means when you are already at the bottom — are tested on the JVM
 * rather than discovered by nudging a real window into a corner it cannot
 * escape.
 */
class FloatingWindow(
    /** Widths as a percentage of the parent, smallest first. */
    private val widthPercents: IntArray = intArrayOf(28, 40, 55)
) {

    var corner: Corner = Corner.BOTTOM_RIGHT
        private set

    var sizeStep: Int = 1
        private set

    var fullscreen: Boolean = false
        private set

    /**
     * @param marginPx gap from the parent's edges. Zero in fullscreen, because
     *   a video letterboxes itself and a border around black is just a smaller
     *   picture.
     */
    fun bounds(parentWidth: Int, parentHeight: Int, marginPx: Int): WindowBounds {
        if (parentWidth <= 0 || parentHeight <= 0) return WindowBounds(0, 0, 0, 0)
        if (fullscreen) return WindowBounds(0, 0, parentWidth, parentHeight)

        val percent = widthPercents[sizeStep.coerceIn(0, widthPercents.lastIndex)]
        var width = parentWidth * percent / 100
        var height = width * 9 / 16

        // A 16:9 window can be too tall for a short parent long before it is too
        // wide -- this screen is 853x456dp, so the height is the binding
        // constraint at the larger sizes.
        val maxHeight = parentHeight - marginPx * 2
        if (height > maxHeight && maxHeight > 0) {
            height = maxHeight
            width = height * 16 / 9
        }
        width = width.coerceAtMost(parentWidth - marginPx * 2).coerceAtLeast(1)
        height = height.coerceAtLeast(1)

        val left = if (corner.isLeft) marginPx else parentWidth - width - marginPx
        val top = if (corner.isTop) marginPx else parentHeight - height - marginPx
        return WindowBounds(left.coerceAtLeast(0), top.coerceAtLeast(0), width, height)
    }

    /**
     * Move between corners.
     *
     * @return true if it moved. False at an edge is deliberate: the caller can
     *   then let the press mean something else, or simply do nothing, rather
     *   than the window wrapping to the far side of the screen.
     */
    fun nudge(direction: Direction): Boolean {
        if (fullscreen) return false
        val next = when (direction) {
            Direction.LEFT -> if (corner.isLeft) return false else leftOf(corner)
            Direction.RIGHT -> if (!corner.isLeft) return false else rightOf(corner)
            Direction.UP -> if (corner.isTop) return false else topOf(corner)
            Direction.DOWN -> if (!corner.isTop) return false else bottomOf(corner)
        }
        corner = next
        return true
    }

    fun cycleSize(delta: Int): Boolean {
        if (fullscreen) return false
        val next = (sizeStep + delta).coerceIn(0, widthPercents.lastIndex)
        if (next == sizeStep) return false
        sizeStep = next
        return true
    }

    fun toggleFullscreen(): Boolean {
        fullscreen = !fullscreen
        return true
    }

    /** Snap to whichever corner a dragged position is nearest. */
    fun snapTo(centerX: Int, centerY: Int, parentWidth: Int, parentHeight: Int): Boolean {
        if (fullscreen || parentWidth <= 0 || parentHeight <= 0) return false
        val left = centerX < parentWidth / 2
        val top = centerY < parentHeight / 2
        val next = when {
            top && left -> Corner.TOP_LEFT
            top -> Corner.TOP_RIGHT
            left -> Corner.BOTTOM_LEFT
            else -> Corner.BOTTOM_RIGHT
        }
        if (next == corner) return false
        corner = next
        return true
    }

    /** For a label: "small", "medium", "large", or "full". */
    fun sizeLabel(): String = when {
        fullscreen -> "Full"
        sizeStep == 0 -> "Small"
        sizeStep >= widthPercents.lastIndex -> "Large"
        else -> "Medium"
    }

    private fun leftOf(c: Corner) = if (c == Corner.TOP_RIGHT) Corner.TOP_LEFT else Corner.BOTTOM_LEFT
    private fun rightOf(c: Corner) = if (c == Corner.TOP_LEFT) Corner.TOP_RIGHT else Corner.BOTTOM_RIGHT
    private fun topOf(c: Corner) = if (c == Corner.BOTTOM_LEFT) Corner.TOP_LEFT else Corner.TOP_RIGHT
    private fun bottomOf(c: Corner) =
        if (c == Corner.TOP_LEFT) Corner.BOTTOM_LEFT else Corner.BOTTOM_RIGHT
}

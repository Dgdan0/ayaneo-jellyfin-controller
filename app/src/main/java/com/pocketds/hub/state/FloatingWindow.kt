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
 * Pure, so the awkward parts — clamping to the safe content area, keeping the
 * video viewport at 16:9 while the toolbar sits above it, and what "down"
 * means at the bottom — are tested on the JVM rather than discovered by
 * nudging a real window into a corner it cannot escape.
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
    fun bounds(
        parentWidth: Int,
        parentHeight: Int,
        marginPx: Int,
        chromeHeightPx: Int = 0,
        safeLeftPx: Int = 0,
        safeTopPx: Int = 0,
        safeRightPx: Int = 0,
        safeBottomPx: Int = 0
    ): WindowBounds {
        if (parentWidth <= 0 || parentHeight <= 0) return WindowBounds(0, 0, 0, 0)
        if (fullscreen) return WindowBounds(0, 0, parentWidth, parentHeight)

        val percent = widthPercents[sizeStep.coerceIn(0, widthPercents.lastIndex)]
        val availableWidth = (parentWidth - safeLeftPx - safeRightPx).coerceAtLeast(1)
        val availableHeight = (parentHeight - safeTopPx - safeBottomPx).coerceAtLeast(1)
        // A tiny host may not have room for two full margins. Reduce them
        // symmetrically so the returned rectangle still stays inside its safe
        // area rather than making a negative maximum size.
        val marginX = marginPx.coerceAtMost((availableWidth - 1) / 2)
        val marginY = marginPx.coerceAtMost((availableHeight - 1) / 2)
        val maxWidth = (availableWidth - marginX * 2).coerceAtLeast(1)
        val maxHeight = (availableHeight - marginY * 2).coerceAtLeast(1)
        var width = (availableWidth * percent / 100).coerceIn(1, maxWidth)
        var videoHeight = (width * 9 / 16).coerceAtLeast(1)
        var height = videoHeight + chromeHeightPx

        // A 16:9 window can be too tall for a short parent long before it is too
        // wide -- this screen is 853x456dp, so the height is the binding
        // constraint at the larger sizes.
        if (height > maxHeight) {
            videoHeight = (maxHeight - chromeHeightPx).coerceAtLeast(1)
            width = (videoHeight * 16 / 9).coerceIn(1, maxWidth)
            height = maxHeight
        }

        val left = if (corner.isLeft) safeLeftPx + marginX
            else parentWidth - safeRightPx - width - marginX
        val top = if (corner.isTop) safeTopPx + marginY
            else parentHeight - safeBottomPx - height - marginY
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
    fun snapTo(
        centerX: Int,
        centerY: Int,
        parentWidth: Int,
        parentHeight: Int,
        safeLeftPx: Int = 0,
        safeTopPx: Int = 0,
        safeRightPx: Int = 0,
        safeBottomPx: Int = 0
    ): Boolean {
        if (fullscreen || parentWidth <= 0 || parentHeight <= 0) return false
        val left = centerX < safeLeftPx + (parentWidth - safeLeftPx - safeRightPx) / 2
        val top = centerY < safeTopPx + (parentHeight - safeTopPx - safeBottomPx) / 2
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

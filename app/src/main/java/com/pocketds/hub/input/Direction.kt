package com.pocketds.hub.input

/**
 * One directional step. Deliberately not Android's focus-search constants: this
 * is produced by pure logic that must be testable off-device, and the View layer
 * translates it at the boundary.
 */
enum class Direction {
    UP, DOWN, LEFT, RIGHT;

    val isHorizontal: Boolean get() = this == LEFT || this == RIGHT
    val isVertical: Boolean get() = this == UP || this == DOWN

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }
}

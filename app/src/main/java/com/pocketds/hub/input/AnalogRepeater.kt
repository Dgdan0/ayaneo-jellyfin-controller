package com.pocketds.hub.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns a held stick -- or a held D-pad -- into a stream of directional steps.
 *
 * Two mechanical facts drive the whole design:
 *
 *  * **A held stick stops producing events.** Once it is parked at full
 *    deflection the driver has nothing new to report, so repeat cannot be
 *    event-driven. [update] is meant to be called every frame from a
 *    `Choreographer` callback holding the last known axis values, and it
 *    decides from the clock whether a step is due.
 *  * **The D-pad on this device is hat axes with no auto-repeat.** Key events
 *    carry `repeatCount` and the platform repeats them for you; `ABS_HAT0X/Y`
 *    delivers one event on press and one on release and nothing in between. So
 *    the same engine drives both -- the hat simply feeds in at magnitude 1.0,
 *    with its own instance tuned to a fixed rate (see [forHat]).
 *
 * Pure: floats and a clock reading in, a [Direction] or null out. No Android
 * types, so the feel is tuned against unit tests rather than by redeploying.
 */
class AnalogRepeater(
    private val deadZone: Float = DEFAULT_DEAD_ZONE,
    private val initialDelayMs: Long = 380L,
    /** Repeat interval at the dead-zone edge -- a gentle push. */
    private val slowIntervalMs: Long = 220L,
    /** Repeat interval at full deflection -- shoved hard. */
    private val fastIntervalMs: Long = 70L,
    /**
     * How much the other axis must beat the current one by before the direction
     * flips. Without it, a stick held at 45 degrees alternates between the two
     * axes at random and the grid walks diagonally.
     */
    private val axisSwapMargin: Float = 1.30f
) {

    private var active: Direction? = null
    private var lastEmitMs = 0L
    private var awaitingInitialDelay = false

    /**
     * @param x -1..1, positive right.
     * @param y -1..1, **positive down** -- Android's convention for AXIS_Y.
     * @param nowMs a monotonic clock; only differences matter.
     * @return the step to act on now, or null.
     */
    fun update(x: Float, y: Float, nowMs: Long): Direction? {
        val magnitude = hypot(x, y)
        if (magnitude < deadZone) {
            reset()
            return null
        }

        val direction = resolve(x, y)
        if (direction != active) {
            // A new direction always steps at once, including a reversal: when
            // you have overshot by one, waiting 380ms to come back feels broken.
            active = direction
            lastEmitMs = nowMs
            awaitingInitialDelay = true
            return direction
        }

        val interval = if (awaitingInitialDelay) initialDelayMs else intervalFor(magnitude)
        if (nowMs - lastEmitMs < interval) return null

        awaitingInitialDelay = false
        // Assignment, not `lastEmitMs += interval`: after a dropped frame or a
        // GC pause the accumulating form owes several steps and pays them out in
        // a burst, flinging the list. A temporal stepper must not accumulate --
        // which is the exact inverse of the sibling project's HorizontalStepper,
        // where a fast drag must report every step it passed over.
        lastEmitMs = nowMs
        return direction
    }

    /** True when the stick is inside the dead zone, so the ticker can stop. */
    fun idle(): Boolean = active == null

    fun reset() {
        active = null
        awaitingInitialDelay = false
    }

    /** Harder push, faster repeat. Monotonic, which is the point of an analog stick. */
    private fun intervalFor(magnitude: Float): Long {
        val span = 1f - deadZone
        val t = if (span <= 0f) 1f else ((magnitude - deadZone) / span).coerceIn(0f, 1f)
        return (slowIntervalMs + (fastIntervalMs - slowIntervalMs) * t).toLong()
    }

    private fun resolve(x: Float, y: Float): Direction {
        val ax = abs(x)
        val ay = abs(y)
        val current = active
        val horizontal = when {
            // Sticky: keep the axis we are already on unless the other clearly wins.
            current != null && current.isHorizontal -> ax * axisSwapMargin >= ay
            current != null && current.isVertical -> ax >= ay * axisSwapMargin
            else -> ax >= ay
        }
        return if (horizontal) {
            if (x > 0f) Direction.RIGHT else Direction.LEFT
        } else {
            if (y > 0f) Direction.DOWN else Direction.UP
        }
    }

    companion object {
        /**
         * Measured on the hardware: the sticks drift by 0.004 at rest and the
         * driver declares a flat of 0.125. This clears both without throwing
         * away usable travel.
         */
        const val DEFAULT_DEAD_ZONE = 0.12f

        /**
         * A D-pad has no analog magnitude to map a rate from, so it repeats at
         * one fixed speed. Slower than a shoved stick on purpose: the hat is
         * what you use to step precisely through a list.
         */
        fun forHat(): AnalogRepeater = AnalogRepeater(
            deadZone = 0.5f,          // the hat is digital: -1, 0 or +1
            initialDelayMs = 400L,
            slowIntervalMs = 140L,
            fastIntervalMs = 140L
        )
    }
}

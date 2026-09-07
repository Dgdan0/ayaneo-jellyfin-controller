package com.pocketds.hub.input

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * One axis, as observed so far.
 *
 * [maxAbs] is the number the dead zone actually depends on: with the sticks
 * untouched it is how far the hardware wanders from centre on its own.
 */
data class AxisReading(
    val axis: Int,
    val current: Float,
    val min: Float,
    val max: Float,
    val samples: Int
) {
    val span: Float get() = max - min
    val maxAbs: Float get() = max(abs(min), abs(max))
    val name: String get() = PadNames.axisName(axis)
}

/**
 * Accumulates what each axis has actually reported.
 *
 * This exists to settle a specific unknown: Hall-effect sticks have no
 * mechanical slop to declare, so they frequently report a driver dead zone
 * (MotionRange.flat) of exactly 0 while still drifting. Believing that number
 * gives a cursor that creeps on its own. Leaving the sticks alone for half a
 * minute and reading [AxisReading.maxAbs] gives the real figure.
 *
 * Pure: takes floats, returns data. No Android types, so it is unit-tested on
 * the JVM without a device.
 */
class AxisStats {

    private val readings = LinkedHashMap<Int, AxisReading>()

    fun record(axis: Int, value: Float) {
        val existing = readings[axis]
        readings[axis] = if (existing == null) {
            AxisReading(axis, value, value, value, 1)
        } else {
            existing.copy(
                current = value,
                min = minOf(existing.min, value),
                max = maxOf(existing.max, value),
                samples = existing.samples + 1
            )
        }
    }

    /** Only axes that have actually reported, in first-seen order. */
    fun snapshot(): List<AxisReading> = readings.values.toList()

    fun reading(axis: Int): AxisReading? = readings[axis]

    /**
     * The largest deflection seen on any of [axes], 0 if none have reported.
     *
     * This is the widest the stick has ever swung, NOT drift: it includes every
     * deliberate push. Use [RestDriftTracker] for the at-rest figure that a dead
     * zone should be sized from.
     */
    fun widestSpan(axes: IntArray): Float =
        axes.asIterable().mapNotNull { readings[it]?.maxAbs }.maxOrNull() ?: 0f

    fun reset() = readings.clear()

    companion object {
        /** Never trust a driver-reported dead zone below this. */
        const val FLOOR = 0.12f

        /**
         * Shaved off before rounding up, to stop a value that lands exactly on
         * a boundary being pushed a whole step by float noise: 0.2f is not
         * representable, so a 0.20 drift produces 30.0000004 rather than 30 and
         * would round to 0.31 instead of 0.30. Far smaller than one step, so it
         * can never round down past the drift it has to clear.
         */
        private const val ROUND_EPSILON = 1e-3f

        /**
         * A dead zone that clears the drift measured at rest, with headroom,
         * rounded to something a human would type into a settings field.
         *
         * The 1.5x margin is because drift is temperature- and wear-dependent:
         * a value that exactly clears today's worst sample will not clear
         * tomorrow's.
         */
        fun suggestDeadZone(maxAbsAtRest: Float): Float {
            val withMargin = maxAbsAtRest * 1.5f
            val rounded = ceil(withMargin * 100f - ROUND_EPSILON) / 100f
            return rounded.coerceIn(FLOOR, 0.50f)
        }
    }
}

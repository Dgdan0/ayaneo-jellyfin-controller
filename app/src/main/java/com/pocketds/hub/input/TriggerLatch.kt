package com.pocketds.hub.input

/**
 * An analog trigger reduced to one discrete fire per pull.
 *
 * A Schmitt trigger rather than a single threshold: with one threshold, a finger
 * resting near it produces a stream of fires as the value dithers across. The
 * gap between [fireAt] and [rearmAt] is what makes a slow squeeze fire exactly
 * once.
 *
 * Measured on this hardware, `ABS_BRAKE` and `ABS_GAS` run 0..1 with a declared
 * flat of 0.059, so both thresholds sit well clear of the noise floor.
 */
class TriggerLatch(
    private val fireAt: Float = 0.60f,
    private val rearmAt: Float = 0.35f
) {

    private var armed = true

    /** @return true exactly once per pull, on the way past [fireAt]. */
    fun update(value: Float): Boolean {
        if (armed && value >= fireAt) {
            armed = false
            return true
        }
        if (!armed && value <= rearmAt) {
            armed = true
        }
        return false
    }

    fun reset() {
        armed = true
    }
}

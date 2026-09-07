package com.pocketds.hub.input

/**
 * How far the sticks wander **while nobody is touching them**.
 *
 * The naive version of this -- take the largest magnitude ever seen -- is
 * useless, because the first thing anyone does with a probe screen is push the
 * sticks to their limits, and it then reports a drift of 1.0 and suggests a
 * dead zone that swallows the entire range. That is not a hypothetical; it is
 * what the first run on the hardware produced.
 *
 * So deflection past [activeThreshold] is treated as deliberate: it does not
 * count, and it starts a settle window afterwards, because a released stick
 * takes a moment to come back. Only samples taken [settleMs] after the last
 * deliberate movement count toward the figure.
 *
 * Pure: takes a magnitude and a clock reading, returns data.
 */
class RestDriftTracker(
    private val activeThreshold: Float = 0.5f,
    private val settleMs: Long = 1_500L
) {

    private var lastActiveMs: Long? = null
    private var worst = 0f
    private var samples = 0

    /**
     * @param magnitude the largest absolute stick-axis value in this sample.
     * @param nowMs an event timestamp; only differences matter.
     */
    fun update(magnitude: Float, nowMs: Long) {
        if (magnitude > activeThreshold) {
            lastActiveMs = nowMs
            return
        }
        val last = lastActiveMs
        // Never touched at all counts as resting from the start; otherwise wait
        // for the stick to physically come back before believing the reading.
        if (last != null && nowMs - last < settleMs) return
        if (magnitude > worst) worst = magnitude
        samples++
    }

    /** Largest deflection seen while at rest. */
    fun drift(): Float = worst

    /** How many resting samples that figure is based on. Zero means "no data". */
    fun samples(): Int = samples

    /** True while waiting for a released stick to come back to centre. */
    fun settling(nowMs: Long): Boolean {
        val last = lastActiveMs ?: return false
        return nowMs - last < settleMs
    }

    fun reset() {
        lastActiveMs = null
        worst = 0f
        samples = 0
    }
}

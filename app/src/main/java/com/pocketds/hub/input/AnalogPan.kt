package com.pocketds.hub.input

import kotlin.math.hypot
import kotlin.math.pow

/**
 * Turns the held right stick into a smooth pan, one amount per frame (#16).
 *
 * The left stick steps ([AnalogRepeater]); the right one moves the view
 * continuously: a comic page pans under it, a scrolling book scrolls. Each
 * call returns how far to move this frame, as the stick's deflection past the
 * dead zone times the seconds since the last call, so a screen multiplies by
 * its own speed and the feel is the same at 60 Hz and at 165 Hz.
 *
 * Like the repeater it is clock-driven: a held stick sends no new events, so
 * [update] runs every frame with the last known axis values, and frame and
 * event clocks may interleave. A long gap (a paused frame, a GC) moves no more
 * than [maxFrameMs] would, and a clock that steps backwards moves nothing.
 *
 * Pure: floats and a clock reading in, an amount or null out.
 */
class AnalogPan(
    private val deadZone: Float = AnalogRepeater.DEFAULT_DEAD_ZONE,
    /** Above 1 the first part of the travel is finer, so a nudge moves a little and a shove a lot. */
    private val curve: Float = 1.6f,
    private val maxFrameMs: Long = 50L
) {
    private var lastMs = 0L
    private var active = false

    /**
     * @param x -1..1, positive right.
     * @param y -1..1, positive down, as Android's axes are.
     * @return this frame's pan, or null inside the dead zone. The first
     *   frame of a push only starts the clock.
     */
    fun update(x: Float, y: Float, nowMs: Long): Amount? {
        val magnitude = hypot(x, y)
        if (magnitude < deadZone) {
            reset()
            return null
        }
        if (!active) {
            active = true
            lastMs = nowMs
            return null
        }
        val elapsed = (nowMs - lastMs).coerceIn(0L, maxFrameMs)
        if (nowMs > lastMs) lastMs = nowMs
        if (elapsed == 0L) return null
        val span = 1f - deadZone
        val travel = if (span <= 0f) 1f else ((magnitude - deadZone) / span).coerceIn(0f, 1f)
        val speed = travel.pow(curve) * elapsed / 1000f
        return Amount(x / magnitude * speed, y / magnitude * speed)
    }

    /** True while the stick rests, so the frame ticker can stop. */
    fun idle(): Boolean = !active

    fun reset() {
        active = false
    }

    /** How far to move this frame, in stick-seconds on each axis. */
    data class Amount(val dx: Float, val dy: Float)
}

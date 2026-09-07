package com.pocketds.hub.ui

import kotlin.math.roundToInt

/**
 * The colour a key takes while it is held down.
 *
 * There was no pressed state at all — only a ripple — so at speed nothing told
 * you which key had registered. The brief is a good physical keyboard rather
 * than a light show: clearly different when you look at it, never something
 * that draws the eye by itself.
 *
 * Dark keys lift and light keys sink, because a white key has nowhere brighter
 * to go. Both move by the same amount, so the two themes feel alike.
 */
object KeyPressTint {

    /**
     * Enough to read as pressed, little enough not to flash.
     *
     * Nudged up once the ripple was removed: this is now the only thing saying
     * a key went down, so it has to carry that on its own.
     */
    private const val SHIFT = 0.16f

    fun pressed(color: Int, dark: Boolean): Int {
        val alpha = (color ushr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        // Toward white on a dark keyboard, toward black on a light one. Moving
        // a fraction of the *remaining* distance keeps every channel in range
        // without clamping, which is where a flat offset wraps to the opposite
        // colour on near-black and near-white keys.
        val target = if (dark) 255 else 0
        return (alpha shl 24) or
            (towards(r, target) shl 16) or
            (towards(g, target) shl 8) or
            towards(b, target)
    }

    private fun towards(channel: Int, target: Int): Int =
        (channel + (target - channel) * SHIFT).roundToInt().coerceIn(0, 255)

    /**
     * Whether a surface counts as dark, which decides which way a press moves.
     * Weighted for how the eye reads the channels rather than a flat average.
     */
    fun isDarkSurface(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 30 + g * 59 + b * 11) / 100 < 128
    }
}

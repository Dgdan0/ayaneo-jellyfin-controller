package com.pocketds.hub.ui

import kotlin.math.roundToInt

/**
 * Comfort in a long session (#16, X3): how bright and how warm this app draws
 * a reader, and whether the screen stays on while narration plays. (A book's
 * black page is the Dark theme now, #47.) One owner for the player's brightness drag and
 * every reader's Comfort sheet; the readers keep theirs in ComfortSettings.
 *
 * Software only. The Pocket has two backlights and this never touches either:
 * it lays black over what this app draws ([dimAlpha]) and multiplies a warm
 * colour into it ([warmColor]). Multiplying turns white amber while black stays
 * black, so the OLED's black page stays off at any warmth. Pure, so a JVM test
 * pins the arithmetic.
 */
data class ScreenComfort(
    /** 1 is as drawn; [MIN_BRIGHTNESS] is the dimmest a reader goes. */
    val brightness: Float = 1f,
    /** 0 is as drawn; 1 is candlelight. */
    val warmth: Float = 0f,
    /** The screen stays on while a read-along book is narrating. */
    val awakeWhileNarrating: Boolean = true
) {
    val dimAlpha: Float get() = dimAlpha(brightness)
    val warmColor: Int get() = warmColor(warmth)

    /** Nothing to draw over the page. */
    val drawsNothing: Boolean get() = dimAlpha <= 0f && warmColor == WHITE

    fun keepsScreenOn(narrating: Boolean): Boolean = narrating && awakeWhileNarrating

    companion object {
        const val MIN_BRIGHTNESS = 0.1f
        /** How dark the dimmest is: the player's measure, which lets a little of the picture through. */
        const val MAX_DIM = 0.85f
        const val WHITE: Int = -1
        /** White at full warmth, about 2,800 K: the warm end of a reading lamp. */
        const val CANDLE: Int = 0xFFFFB46B.toInt()

        /** The black laid over the picture: none at full brightness, [MAX_DIM] at none. */
        fun dimAlpha(brightness: Float): Float = (1f - brightness.coerceIn(0f, 1f)) * MAX_DIM

        /** What white becomes at [warmth], multiplied into the page: from white to [CANDLE]. */
        fun warmColor(warmth: Float): Int {
            val k = warmth.coerceIn(0f, 1f)
            fun channel(shift: Int): Int {
                val to = (CANDLE shr shift) and 0xFF
                return (255 + (to - 255) * k).roundToInt()
            }
            return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }

        /** "60%". */
        fun brightnessLabel(brightness: Float): String = "${(brightness.coerceIn(0f, 1f) * 100).roundToInt()}%"

        /** "Off", or how warm: "40%". */
        fun warmthLabel(warmth: Float): String =
            if (warmth <= 0.001f) "Off" else "${(warmth.coerceIn(0f, 1f) * 100).roundToInt()}%"
    }
}

package com.pocketds.hub.ui

import android.view.HapticFeedbackConstants
import android.view.View

enum class HapticStrength(val stored: String, val label: String) {
    OFF("off", "Off"),
    LIGHT("light", "Light"),
    STRONG("strong", "Strong");

    companion object {
        /** Subtle by default: it is a keyboard, not a game controller. */
        val DEFAULT = LIGHT

        fun fromStored(value: String?): HapticStrength =
            entries.firstOrNull { it.stored == value } ?: DEFAULT
    }
}

/**
 * Key feedback, through the system's own haptic constants.
 *
 * Deliberately not the Vibrator API, which would want the VIBRATE permission
 * for a keyboard — and would also bypass the device-wide touch-feedback
 * setting, which a user who has turned haptics off meant to apply here too.
 */
object KeyHaptics {

    /**
     * The constant to feed [View.performHapticFeedback], or null for silence.
     *
     * Chosen by measuring what this device actually does with each, rather than
     * by their names — on a handheld the feedback is forwarded to the rumble
     * motors, so the difference is large:
     *
     *  * CLOCK_TICK   → a 10ms pulse. A tick.
     *  * KEYBOARD_TAP → 50ms at 86% amplitude. A buzz.
     *
     * The keyboard-named one is the heavier of the two here, so light is the
     * clock tick: 50ms under the palm on every letter is not what a good
     * keyboard feels like.
     */
    fun effectFor(strength: HapticStrength): Int? = when (strength) {
        HapticStrength.OFF -> null
        HapticStrength.LIGHT -> HapticFeedbackConstants.CLOCK_TICK
        HapticStrength.STRONG -> HapticFeedbackConstants.KEYBOARD_TAP
    }

    fun perform(view: View, strength: HapticStrength) {
        val effect = effectFor(strength) ?: return
        view.performHapticFeedback(effect)
    }

    /**
     * Keeps a held key from rattling.
     *
     * Backspace repeats several times a second. Buzzing on every repeat is a
     * continuous vibration against the palm rather than feedback, so repeats
     * are spaced out — while two deliberate presses, however fast, both count,
     * because that is typing rather than a key repeating.
     */
    class RepeatGate(private val minGapMs: Long = 120L) {
        private var lastAtMs: Long? = null

        fun allow(atMs: Long): Boolean {
            val last = lastAtMs
            if (last != null && atMs - last < minGapMs) return false
            lastAtMs = atMs
            return true
        }

        /** The key came up, so the next press starts over. */
        fun release() {
            lastAtMs = null
        }
    }
}

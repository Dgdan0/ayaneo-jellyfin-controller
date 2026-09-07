package com.pocketds.hub.input

/**
 * First source to report a given logical input wins; every other source for it
 * is ignored from then on.
 *
 * Two real cases on this hardware, both of which would otherwise fire twice per
 * press:
 *
 *  * **The triggers.** Confirmed by measurement: one pull of L2 produces both an
 *    analog `ABS_BRAKE` movement *and* a `BUTTON_L2` key event.
 *  * **Direction.** This unit sends only hat axes, but plenty of controllers
 *    send hat axes *and* synthesised `KEYCODE_DPAD_*` for the same press.
 *
 * Latching rather than picking a preferred source in advance means whichever
 * one the hardware actually uses is the one that works, with no per-device
 * table to maintain.
 */
class SourceLatch {

    private var winner: String? = null

    /** @return true if this source owns the input and its event should be acted on. */
    fun accept(source: String): Boolean {
        val current = winner
        if (current == null) {
            winner = source
            return true
        }
        return current == source
    }

    /** Which source won, or null if nothing has reported yet. */
    fun winner(): String? = winner

    /** Re-open the latch. Used by the probe screen, and after a device change. */
    fun reset() {
        winner = null
    }

    companion object {
        const val SOURCE_HAT = "hat axes"
        const val SOURCE_KEYS = "key codes"
        const val SOURCE_ANALOG = "analog axis"
    }
}

package com.pocketds.hub.input

enum class InputMode { DIRECTIONAL, POINTER }

/**
 * Which way the user is currently driving the app.
 *
 * The bottom screen runs the sibling keyboard project as a trackpad, so a cursor
 * and a gamepad are both first-class here and both get used in the same sitting.
 * A focus ring chasing the D-pad while someone is pointing at things is noise;
 * no focus ring at all when they pick the pad back up is worse. So the ring
 * follows the last input seen.
 *
 * Starts in [InputMode.DIRECTIONAL]: on a handheld the pad is the default
 * assumption, and showing the ring at launch is how the app says it can be
 * driven without touching the screen.
 */
class InputModeTracker {

    var mode: InputMode = InputMode.DIRECTIONAL
        private set

    /** A stick, D-pad or face-button event arrived. @return true if the mode changed. */
    fun onDirectional(): Boolean = switchTo(InputMode.DIRECTIONAL)

    /** A tap or click arrived. @return true if the mode changed. */
    fun onPointer(): Boolean = switchTo(InputMode.POINTER)

    val showFocusRing: Boolean get() = mode == InputMode.DIRECTIONAL

    private fun switchTo(next: InputMode): Boolean {
        if (mode == next) return false
        mode = next
        return true
    }
}

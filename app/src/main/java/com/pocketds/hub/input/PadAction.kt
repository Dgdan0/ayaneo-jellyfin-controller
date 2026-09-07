package com.pocketds.hub.input

/**
 * What the user asked for, with the hardware already forgotten.
 *
 * Screens react to these, never to key codes or axis values, so the same action
 * can arrive from the stick, the D-pad, a face button, or a tap on the hint bar
 * without any screen knowing the difference. That is what makes "every action
 * reachable by gamepad and by trackpad" a property of one translation layer
 * rather than of every screen.
 */
sealed interface PadAction {

    /** Move the selection. From the left stick or the D-pad hat. */
    data class Step(val direction: Direction) : PadAction

    /** A, or a tap. */
    data object Activate : PadAction

    /** B, the system back gesture, or the Back chip. */
    data object Back : PadAction

    /** X. Request / Play / Pause -- whatever the screen says it is right now. */
    data object Primary : PadAction

    /** Y, a long press, or a right-click. Details / manual search / delete. */
    data object Secondary : PadAction

    /** L1 and R1. -1 is previous, +1 is next. */
    data class Section(val delta: Int) : PadAction

    /** L2 and R2. A screenful at a time. */
    data class Page(val direction: Direction) : PadAction

    /** Start. */
    data object Menu : PadAction

    /** Select. */
    data object Refresh : PadAction
}

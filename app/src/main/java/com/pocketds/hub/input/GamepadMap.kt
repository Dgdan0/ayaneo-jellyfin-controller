package com.pocketds.hub.input

/**
 * Key code to intent.
 *
 * Only DOWN events should be fed here; UP is not an action in this app.
 *
 * The D-pad key codes are mapped even though this device never sends them --
 * it drives the hat axes instead. They cost nothing, they make the app work on
 * a plugged-in controller that does use them, and the router gates them behind
 * a [SourceLatch] so a device that sends *both* still steps only once.
 */
class GamepadMap(
    /**
     * Some firmware wires the physically bottom face button to the right-hand
     * key code. Measured on this unit it does not -- bottom is `BUTTON_A` and
     * the printed labels match -- so this stays off and exists as a setting for
     * the day a firmware update changes its mind.
     */
    private val swapAcceptCancel: Boolean = false
) {

    fun actionFor(keyCode: Int): PadAction? = when (keyCode) {
        PadNames.KEYCODE_BUTTON_A -> if (swapAcceptCancel) PadAction.Back else PadAction.Activate
        PadNames.KEYCODE_BUTTON_B -> if (swapAcceptCancel) PadAction.Activate else PadAction.Back
        PadNames.KEYCODE_BACK -> PadAction.Back

        PadNames.KEYCODE_BUTTON_X -> PadAction.Primary
        PadNames.KEYCODE_BUTTON_Y -> PadAction.Secondary

        PadNames.KEYCODE_BUTTON_L1 -> PadAction.Section(-1)
        PadNames.KEYCODE_BUTTON_R1 -> PadAction.Section(+1)

        PadNames.KEYCODE_BUTTON_L2 -> PadAction.Page(Direction.UP)
        PadNames.KEYCODE_BUTTON_R2 -> PadAction.Page(Direction.DOWN)

        PadNames.KEYCODE_BUTTON_START -> PadAction.Menu
        PadNames.KEYCODE_BUTTON_SELECT -> PadAction.Refresh

        PadNames.KEYCODE_DPAD_UP -> PadAction.Step(Direction.UP)
        PadNames.KEYCODE_DPAD_DOWN -> PadAction.Step(Direction.DOWN)
        PadNames.KEYCODE_DPAD_LEFT -> PadAction.Step(Direction.LEFT)
        PadNames.KEYCODE_DPAD_RIGHT -> PadAction.Step(Direction.RIGHT)
        PadNames.KEYCODE_DPAD_CENTER -> PadAction.Activate

        else -> null
    }

    /** True for the key codes that move the selection, whatever they map to. */
    fun isDirectional(keyCode: Int): Boolean = actionFor(keyCode) is PadAction.Step
}

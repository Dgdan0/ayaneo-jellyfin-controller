package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMapTest {

    private val map = GamepadMap()

    @Test
    fun `the face buttons map to the four core intents`() {
        assertEquals(PadAction.Activate, map.actionFor(PadNames.KEYCODE_BUTTON_A))
        assertEquals(PadAction.Back, map.actionFor(PadNames.KEYCODE_BUTTON_B))
        assertEquals(PadAction.Primary, map.actionFor(PadNames.KEYCODE_BUTTON_X))
        assertEquals(PadAction.Secondary, map.actionFor(PadNames.KEYCODE_BUTTON_Y))
    }

    @Test
    fun `the system back key is back too`() {
        // It arrives from the virtual device rather than the controller, but the
        // user meant the same thing by it.
        assertEquals(PadAction.Back, map.actionFor(PadNames.KEYCODE_BACK))
    }

    @Test
    fun `shoulders switch section and triggers page`() {
        assertEquals(PadAction.Section(-1), map.actionFor(PadNames.KEYCODE_BUTTON_L1))
        assertEquals(PadAction.Section(+1), map.actionFor(PadNames.KEYCODE_BUTTON_R1))
        assertEquals(PadAction.Page(Direction.UP), map.actionFor(PadNames.KEYCODE_BUTTON_L2))
        assertEquals(PadAction.Page(Direction.DOWN), map.actionFor(PadNames.KEYCODE_BUTTON_R2))
    }

    @Test
    fun `start opens the menu and select refreshes`() {
        assertEquals(PadAction.Menu, map.actionFor(PadNames.KEYCODE_BUTTON_START))
        assertEquals(PadAction.Refresh, map.actionFor(PadNames.KEYCODE_BUTTON_SELECT))
    }

    @Test
    fun `dpad key codes step, for controllers that send them`() {
        // This handheld sends hat axes instead, but a plugged-in pad may not.
        assertEquals(PadAction.Step(Direction.UP), map.actionFor(PadNames.KEYCODE_DPAD_UP))
        assertEquals(PadAction.Step(Direction.DOWN), map.actionFor(PadNames.KEYCODE_DPAD_DOWN))
        assertEquals(PadAction.Step(Direction.LEFT), map.actionFor(PadNames.KEYCODE_DPAD_LEFT))
        assertEquals(PadAction.Step(Direction.RIGHT), map.actionFor(PadNames.KEYCODE_DPAD_RIGHT))
    }

    @Test
    fun `an unmapped key is ignored rather than guessed at`() {
        assertNull(map.actionFor(PadNames.KEYCODE_BUTTON_MODE))   // the AYA button
        assertNull(map.actionFor(24))                             // volume up
        assertNull(map.actionFor(9001))
    }

    @Test
    fun `the swap inverts accept and cancel and nothing else`() {
        val swapped = GamepadMap(swapAcceptCancel = true)
        assertEquals(PadAction.Back, swapped.actionFor(PadNames.KEYCODE_BUTTON_A))
        assertEquals(PadAction.Activate, swapped.actionFor(PadNames.KEYCODE_BUTTON_B))
        // X, Y and everything else stay put.
        assertEquals(PadAction.Primary, swapped.actionFor(PadNames.KEYCODE_BUTTON_X))
        assertEquals(PadAction.Secondary, swapped.actionFor(PadNames.KEYCODE_BUTTON_Y))
        assertEquals(PadAction.Menu, swapped.actionFor(PadNames.KEYCODE_BUTTON_START))
    }

    @Test
    fun `the swap leaves the system back key alone`() {
        // Swapping the face buttons must not make the hardware back gesture
        // start activating things.
        assertEquals(PadAction.Back, GamepadMap(swapAcceptCancel = true)
            .actionFor(PadNames.KEYCODE_BACK))
    }

    @Test
    fun `isDirectional picks out exactly the stepping keys`() {
        assertTrue(map.isDirectional(PadNames.KEYCODE_DPAD_LEFT))
        assertFalse(map.isDirectional(PadNames.KEYCODE_BUTTON_A))
        assertFalse(map.isDirectional(PadNames.KEYCODE_BUTTON_L1))
        assertFalse(map.isDirectional(9001))
    }
}

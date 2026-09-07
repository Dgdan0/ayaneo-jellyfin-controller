package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadEventRouterTest {

    private val emitted = mutableListOf<PadAction>()
    private fun router() = PadEventRouter(emit = { emitted.add(it) })

    private fun PadEventRouter.motion(
        x: Float = 0f, y: Float = 0f,
        hatX: Float = 0f, hatY: Float = 0f,
        left: Float = 0f, right: Float = 0f,
        now: Long = 0L
    ) = onMotion(x, y, hatX, hatY, left, right, now)

    // --- buttons -----------------------------------------------------------

    @Test
    fun `face buttons emit their intents`() {
        val r = router()
        assertTrue(r.onKeyDown(PadNames.KEYCODE_BUTTON_A))
        r.onKeyDown(PadNames.KEYCODE_BUTTON_B)
        r.onKeyDown(PadNames.KEYCODE_BUTTON_X)
        r.onKeyDown(PadNames.KEYCODE_BUTTON_Y)
        assertEquals(
            listOf(PadAction.Activate, PadAction.Back, PadAction.Primary, PadAction.Secondary),
            emitted
        )
    }

    @Test
    fun `an unmapped key is not consumed, so the system still gets it`() {
        // Volume must keep working while the app is foreground.
        assertFalse(router().onKeyDown(24))
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `shoulders switch section`() {
        val r = router()
        r.onKeyDown(PadNames.KEYCODE_BUTTON_L1)
        r.onKeyDown(PadNames.KEYCODE_BUTTON_R1)
        assertEquals(listOf(PadAction.Section(-1), PadAction.Section(+1)), emitted)
    }

    // --- the stick ---------------------------------------------------------

    @Test
    fun `the stick steps and keeps stepping while held`() {
        val r = router()
        r.motion(y = 1f, now = 0L)
        assertEquals(listOf<PadAction>(PadAction.Step(Direction.DOWN)), emitted)
        // Held: no new events arrive from the driver, so the ticker drives it.
        r.onTick(500L)
        r.onTick(1_000L)
        assertEquals(3, emitted.size)
        assertTrue(emitted.all { it == PadAction.Step(Direction.DOWN) })
    }

    @Test
    fun `a centred stick leaves the router idle so the ticker can stop`() {
        val r = router()
        assertTrue(r.idle())
        r.motion(x = 1f, now = 0L)
        assertFalse(r.idle())
        r.motion(x = 0f, now = 16L)
        assertTrue(r.idle())
    }

    // --- the hat -----------------------------------------------------------

    @Test
    fun `the hat steps and repeats, since the platform will not repeat it`() {
        val r = router()
        r.motion(hatX = -1f, now = 0L)
        assertEquals(listOf<PadAction>(PadAction.Step(Direction.LEFT)), emitted)
        r.onTick(400L)
        r.onTick(540L)
        assertEquals(3, emitted.size)
    }

    @Test
    fun `the stick and the hat both work -- they are not latched against each other`() {
        // Two legitimate ways to move that the same person uses in one sitting.
        val r = router()
        r.motion(hatY = 1f, now = 0L)
        r.motion(hatY = 0f, now = 100L)
        r.motion(y = -1f, now = 200L)
        assertEquals(
            listOf(PadAction.Step(Direction.DOWN), PadAction.Step(Direction.UP)),
            emitted
        )
    }

    @Test
    fun `dpad key codes are dropped once the hat has claimed direction`() {
        // A controller reporting both would otherwise move two items per press.
        val r = router()
        r.motion(hatX = 1f, now = 0L)
        emitted.clear()
        assertTrue("still consumed", r.onKeyDown(PadNames.KEYCODE_DPAD_RIGHT))
        assertTrue("but ignored", emitted.isEmpty())
    }

    @Test
    fun `a different device is not latched out by the controller`() {
        // The handheld exposes a virtual keyboard+dpad device beside the real
        // controller. One stray key code from it must not silence the pad's hat,
        // and vice versa -- they are two input methods, not two reports of one
        // press. A global latch gets this wrong and the D-pad dies for good.
        val r = router()
        r.onMotion(0f, 0f, 1f, 0f, 0f, 0f, 0L, deviceId = 55)   // controller hat
        emitted.clear()
        r.onKeyDown(PadNames.KEYCODE_DPAD_LEFT, deviceId = -1)  // virtual device
        assertEquals(listOf<PadAction>(PadAction.Step(Direction.LEFT)), emitted)
    }

    @Test
    fun `one device reporting a press twice still steps once`() {
        val r = router()
        r.onMotion(0f, 0f, 1f, 0f, 0f, 0f, 0L, deviceId = 55)
        emitted.clear()
        r.onKeyDown(PadNames.KEYCODE_DPAD_RIGHT, deviceId = 55)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `dpad key codes work when no hat ever reports`() {
        val r = router()
        r.onKeyDown(PadNames.KEYCODE_DPAD_UP)
        assertEquals(listOf<PadAction>(PadAction.Step(Direction.UP)), emitted)
    }

    // --- the triggers ------------------------------------------------------

    @Test
    fun `an analog trigger pull pages once`() {
        val r = router()
        r.motion(left = 0.9f, now = 0L)
        r.motion(left = 1.0f, now = 16L)
        r.motion(left = 0.95f, now = 32L)
        assertEquals(listOf<PadAction>(PadAction.Page(Direction.UP)), emitted)
    }

    @Test
    fun `the L2 key code is dropped once the analog axis has claimed the trigger`() {
        // Measured on this hardware: one pull produces ABS_BRAKE movement AND a
        // BUTTON_L2 key event. Acting on both pages twice.
        val r = router()
        r.motion(left = 0.9f, now = 0L)
        emitted.clear()
        assertTrue("consumed", r.onKeyDown(PadNames.KEYCODE_BUTTON_L2))
        assertTrue("but ignored", emitted.isEmpty())
    }

    @Test
    fun `the L2 key code works on hardware with no analog trigger`() {
        val r = router()
        r.onKeyDown(PadNames.KEYCODE_BUTTON_L2)
        assertEquals(listOf<PadAction>(PadAction.Page(Direction.UP)), emitted)
    }

    @Test
    fun `the two triggers latch independently`() {
        val r = router()
        r.motion(left = 0.9f, now = 0L)        // left claims analog
        emitted.clear()
        r.onKeyDown(PadNames.KEYCODE_BUTTON_R2)  // right is still unclaimed
        assertEquals(listOf<PadAction>(PadAction.Page(Direction.DOWN)), emitted)
    }

    @Test
    fun `a held trigger does not page every frame`() {
        val r = router()
        r.motion(left = 1f, now = 0L)
        repeat(60) { r.motion(left = 1f, now = (it + 1) * 16L) }
        assertEquals(1, emitted.size)
    }

    // --- input mode --------------------------------------------------------

    @Test
    fun `a tap hides the focus ring and the pad brings it back`() {
        val r = router()
        assertTrue(r.inputMode.showFocusRing)
        r.onPointer()
        assertFalse(r.inputMode.showFocusRing)
        r.onKeyDown(PadNames.KEYCODE_BUTTON_A)
        assertTrue(r.inputMode.showFocusRing)
    }

    @Test
    fun `stick movement counts as directional input`() {
        val r = router()
        r.onPointer()
        r.motion(x = 1f, now = 0L)
        assertTrue(r.inputMode.showFocusRing)
    }

    @Test
    fun `an unmapped key does not claim directional mode`() {
        // Pressing the AYA button while using the trackpad should not make the
        // focus ring reappear.
        val r = router()
        r.onPointer()
        r.onKeyDown(PadNames.KEYCODE_BUTTON_MODE)
        assertFalse(r.inputMode.showFocusRing)
    }

    // --- reset -------------------------------------------------------------

    @Test
    fun `reset drops the held state but keeps the source latches`() {
        val r = router()
        r.motion(hatX = 1f, now = 0L)
        r.reset()
        assertTrue(r.idle())
        emitted.clear()
        // The latch is a fact about the hardware, not about this gesture, so a
        // reset must not let the duplicate source back in.
        r.onKeyDown(PadNames.KEYCODE_DPAD_RIGHT)
        assertTrue(emitted.isEmpty())
    }
}

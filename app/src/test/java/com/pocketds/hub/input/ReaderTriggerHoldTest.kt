package com.pocketds.hub.input

import org.junit.Assert.*
import org.junit.Test

class ReaderTriggerHoldTest {
    private var context: Any? = Any()
    private val actions = mutableListOf<PadAction>()
    private val router = PadEventRouter(triggerHoldContext = { context }, emit = { actions += it })
    private fun analog(left: Float = 0f, right: Float = 0f, now: Long = 0L) =
        router.onMotion(0f, 0f, 0f, 0f, left, right, now, deviceId = 7)

    @Test fun quickAnalogPullIsCancelledButHoldFiresOnceAndRearms() {
        analog(right = 1f)
        assertFalse(router.idle())
        analog(right = 0f, now = 200L)
        router.onTick(700L)
        assertTrue(actions.isEmpty())
        analog(right = 1f, now = 800L)
        router.onTick(1399L); assertTrue(actions.isEmpty())
        router.onTick(1400L); router.onTick(2400L)
        assertEquals(listOf(PadAction.Page(Direction.DOWN)), actions)
        assertTrue(router.idle())
        analog(now = 2500L); analog(right = 1f, now = 2600L); router.onTick(3200L)
        assertEquals(2, actions.size)
    }

    @Test fun digitalReleaseAndRepeatDoNotCreateExtraJumps() {
        router.onKeyDown(PadNames.KEYCODE_BUTTON_L2, nowMs = 0L)
        router.onKeyUp(PadNames.KEYCODE_BUTTON_L2)
        router.onTick(800L); assertTrue(actions.isEmpty())
        router.onKeyDown(PadNames.KEYCODE_BUTTON_L2, nowMs = 900L)
        router.onKeyDown(PadNames.KEYCODE_BUTTON_L2, nowMs = 1000L, repeatCount = 1)
        router.onTick(1500L); router.onTick(3000L)
        assertEquals(listOf(PadAction.Page(Direction.UP)), actions)
    }

    @Test fun analogWinnerIgnoresDuplicateKeyRelease() {
        analog(left = 1f)
        router.onKeyDown(PadNames.KEYCODE_BUTTON_L2, deviceId = 7, nowMs = 10L)
        router.onKeyUp(PadNames.KEYCODE_BUTTON_L2, deviceId = 7)
        router.onTick(600L)
        assertEquals(listOf(PadAction.Page(Direction.UP)), actions)
    }

    @Test fun neutralAxesDoNotClaimDigitalTriggerAndCannotReleaseIt() {
        analog()
        router.onKeyDown(PadNames.KEYCODE_BUTTON_R2, deviceId = 7, nowMs = 20L)
        analog(now = 100L)
        router.onTick(620L)
        assertEquals(listOf(PadAction.Page(Direction.DOWN)), actions)
    }

    @Test fun screenOrPanelChangeCancelsHoldUntilReleased() {
        analog(right = 1f)
        context = Any()
        router.onTick(600L)
        analog(right = 1f, now = 1000L)
        router.onTick(2000L)
        assertTrue(actions.isEmpty())
        analog(now = 2100L); analog(right = 1f, now = 2200L); router.onTick(2800L)
        assertEquals(1, actions.size)
    }

    @Test fun backgroundResetAndPointerCancelPendingJumps() {
        analog(left = 1f); router.reset(); router.onTick(1000L)
        assertTrue(actions.isEmpty()); assertTrue(router.idle())
        analog(left = 1f, now = 1100L); router.onPointer(); router.onTick(2000L)
        assertTrue(actions.isEmpty())
    }

    @Test fun otherScreensStillReceiveImmediateTriggerActions() {
        context = null
        analog(left = 1f)
        assertEquals(listOf(PadAction.Page(Direction.UP)), actions)
    }

    @Test fun holdingBackIsOnePressAndCancelsPendingChapterJump() {
        analog(right = 1f)
        router.onKeyDown(PadNames.KEYCODE_BUTTON_B)
        router.onKeyDown(PadNames.KEYCODE_BUTTON_B, repeatCount = 1)
        router.onTick(1000L)
        assertEquals(listOf(PadAction.Back), actions)
        router.onKeyDown(PadNames.KEYCODE_BUTTON_B)
        assertEquals(2, actions.size)
    }
}

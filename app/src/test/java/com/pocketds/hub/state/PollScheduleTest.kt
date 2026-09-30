package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PollScheduleTest {

    @Test
    fun `hidden means stopped, not merely slower`() {
        // A backgrounded screen polling every ten seconds is the easiest way
        // there is to flatten a handheld's battery.
        assertNull(PollSchedule.nextDelayMs(visible = false, anyActive = true, consecutiveFailures = 0))
        assertNull(PollSchedule.nextDelayMs(visible = false, anyActive = false, consecutiveFailures = 0))
        assertNull(PollSchedule.nextDelayMs(visible = false, anyActive = true, consecutiveFailures = 3))
    }

    @Test
    fun `fast while something is actually moving`() {
        assertEquals(
            PollSchedule.ACTIVE_MS,
            PollSchedule.nextDelayMs(visible = true, anyActive = true, consecutiveFailures = 0)
        )
    }

    @Test
    fun `slow when nothing is moving`() {
        // A queue of stalled torrents does not need a two-second refresh.
        assertEquals(
            PollSchedule.IDLE_MS,
            PollSchedule.nextDelayMs(visible = true, anyActive = false, consecutiveFailures = 0)
        )
    }

    @Test
    fun `failures back off, and stay backed off`() {
        val first = PollSchedule.nextDelayMs(true, true, 1)!!
        val second = PollSchedule.nextDelayMs(true, true, 2)!!
        val third = PollSchedule.nextDelayMs(true, true, 3)!!
        assertTrue("$first < $second", first < second)
        assertTrue("$second < $third", second < third)
        // And it holds rather than growing without bound.
        assertEquals(third, PollSchedule.nextDelayMs(true, true, 50))
    }

    @Test
    fun `backing off ignores whether anything is active`() {
        // The hub being unreachable is the fact that matters; what it was doing
        // before it went away is not.
        assertEquals(
            PollSchedule.nextDelayMs(true, anyActive = true, consecutiveFailures = 2),
            PollSchedule.nextDelayMs(true, anyActive = false, consecutiveFailures = 2)
        )
    }

    @Test
    fun `the backoff never reaches the hub's ban window`() {
        // Five auth failures in a minute earns this device a fifteen-minute ban.
        // The slowest backoff must stay comfortably inside a sane request rate.
        val slowest = PollSchedule.nextDelayMs(true, true, 99)!!
        assertTrue("$slowest should be at least 5s", slowest >= 5_000L)
        assertTrue("$slowest should not be a minute", slowest <= 60_000L)
    }

    @Test
    fun `settling polls fast even with nothing moving`() {
        // Pressing stop on a queued torrent leaves nothing "active", but the
        // state change still has to be picked up promptly or the button reads
        // as broken.
        assertEquals(
            PollSchedule.ACTIVE_MS,
            PollSchedule.nextDelayMs(
                visible = true, anyActive = false, consecutiveFailures = 0, settling = true
            )
        )
    }

    @Test
    fun `settling does not override a backoff`() {
        // An unreachable hub will not answer faster because a button was just
        // pressed, and hammering it is how this device earns a ban.
        assertEquals(
            PollSchedule.nextDelayMs(true, anyActive = false, consecutiveFailures = 2),
            PollSchedule.nextDelayMs(true, false, 2, settling = true)
        )
    }

    @Test
    fun `settling does not resurrect a hidden screen`() {
        assertNull(PollSchedule.nextDelayMs(false, false, 0, settling = true))
    }

    @Test
    fun `the settle window outlasts a slow service`() {
        // Long enough to cover several fast polls, so a client that takes a
        // second or two to reflect the change is still caught.
        assertTrue(PollSchedule.SETTLE_MS >= PollSchedule.ACTIVE_MS * 3)
    }

    @Test
    fun `a cadence without an idle pace stops when nothing moves`() {
        assertNull(PollSchedule.nextDelayMs(true, false, 0, cadence = PollCadence.PIPELINE))
        assertEquals(4_000L, PollSchedule.nextDelayMs(true, true, 0, cadence = PollCadence.PIPELINE))
    }

    @Test
    fun `a failure retries even where idle would stop, never faster than the screen's pace`() {
        assertEquals(5_000L, PollSchedule.nextDelayMs(true, false, 1, cadence = PollCadence.PIPELINE))
        assertEquals(60_000L, PollSchedule.nextDelayMs(true, false, 1, cadence = PollCadence.BADGE))
    }

    @Test
    fun `success resumes immediately rather than serving out the backoff`() {
        assertEquals(
            PollSchedule.ACTIVE_MS,
            PollSchedule.nextDelayMs(visible = true, anyActive = true, consecutiveFailures = 0)
        )
    }
}

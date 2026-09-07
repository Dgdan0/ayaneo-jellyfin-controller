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
    fun `success resumes immediately rather than serving out the backoff`() {
        assertEquals(
            PollSchedule.ACTIVE_MS,
            PollSchedule.nextDelayMs(visible = true, anyActive = true, consecutiveFailures = 0)
        )
    }
}

class FmtTest {

    @Test
    fun `bytes use binary units`() {
        assertEquals("0 B", Fmt.bytes(0))
        assertEquals("512 B", Fmt.bytes(512))
        assertEquals("1.0 KB", Fmt.bytes(1024))
        assertEquals("1.0 MB", Fmt.bytes(1024L * 1024))
        assertEquals("1.5 GB", Fmt.bytes(1024L * 1024 * 1536))
    }

    @Test
    fun `a negative size does not produce nonsense`() {
        assertEquals("0 B", Fmt.bytes(-1))
    }

    @Test
    fun `large values drop the decimal rather than reading as noise`() {
        // "1023.7 MB" is harder to read at a glance than "1023 MB".
        assertEquals("1023 MB", Fmt.bytes(1024L * 1024 * 1023))
    }

    @Test
    fun `zero speed is a dash, not zero`() {
        // "0 B/s" next to a queued torrent reads as broken; a dash reads as idle.
        assertEquals("—", Fmt.speed(0))
        assertEquals("—", Fmt.speed(-5))
        assertEquals("4.0 MB/s", Fmt.speed(4L * 1024 * 1024))
    }

    @Test
    fun `an unknown eta is a dash`() {
        // The hub sends -1 when qBittorrent had nothing to estimate from.
        assertEquals("—", Fmt.eta(-1))
    }

    @Test
    fun `eta reads in the largest sensible unit`() {
        assertEquals("45s", Fmt.eta(45))
        assertEquals("8m", Fmt.eta(8 * 60))
        assertEquals("2h 30m", Fmt.eta(150 * 60))
        assertEquals("3d 2h", Fmt.eta((3 * 24 + 2) * 3600L))
    }

    @Test
    fun `eta of zero is zero seconds, not a dash`() {
        assertEquals("0s", Fmt.eta(0))
    }

    @Test
    fun `percent clamps nothing but refuses to invent a number`() {
        assertEquals("0%", Fmt.percent(0.0))
        assertEquals("63%", Fmt.percent(0.6312))
        assertEquals("100%", Fmt.percent(1.0))
        assertEquals("—", Fmt.percent(-1.0))
    }
}

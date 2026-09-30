package com.pocketds.hub.state

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PollerTest {

    private fun TestScope.poller(cadence: PollCadence) = Poller(cadence) { testScheduler.currentTime }

    @Test
    fun `a failed refresh retries instead of freezing the screen`() = runTest {
        // Media detail used to reschedule only after a success, so one failure
        // stopped its pipeline strip updating for good.
        val times = mutableListOf<Long>()
        val outcomes = ArrayDeque(listOf(PollOutcome(true, active = true), PollOutcome(false), PollOutcome(true, active = true)))
        val poller = poller(PollCadence.PIPELINE)
        poller.start(backgroundScope, { true }) {
            times += testScheduler.currentTime
            outcomes.removeFirstOrNull() ?: PollOutcome(true, active = false)
        }
        advanceTimeBy(60_000); runCurrent()
        // 0, +4s active, +5s backoff, +4s active again, then idle stops it.
        assertEquals(listOf(0L, 4_000L, 9_000L, 13_000L), times)
        assertFalse(poller.isRunning)
    }

    @Test
    fun `nothing moving stops a cadence without an idle pace`() = runTest {
        var calls = 0
        val poller = poller(PollCadence.PIPELINE)
        poller.start(backgroundScope, { true }) { calls++; PollOutcome(true, active = false) }
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, calls)
        assertFalse(poller.isRunning)
    }

    @Test
    fun `a hidden screen stops`() = runTest {
        var visible = true
        var calls = 0
        val poller = poller(PollCadence.TRANSFERS)
        poller.start(backgroundScope, { visible }) { calls++; PollOutcome(true, active = true) }
        advanceTimeBy(2_500); runCurrent()
        visible = false
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, calls)
        assertFalse(poller.isRunning)
    }

    @Test
    fun `a skipped round keeps the pace without counting as a failure`() = runTest {
        var calls = 0
        val poller = poller(PollCadence.TRANSFERS)
        poller.start(backgroundScope, { true }) { calls++; null }
        advanceTimeBy(25_000); runCurrent()
        assertEquals(0, poller.consecutiveFailures)
        assertEquals(3, calls) // 0, 10s, 20s at the idle pace
    }

    @Test
    fun `settling polls fast after an action`() = runTest {
        val times = mutableListOf<Long>()
        val poller = poller(PollCadence.TRANSFERS)
        poller.settle()
        poller.start(backgroundScope, { true }) { times += testScheduler.currentTime; PollOutcome(true) }
        advanceTimeBy(20_000); runCurrent()
        assertEquals(listOf(0L, 2_000L, 4_000L, 6_000L, 8_000L, 18_000L), times)
    }

    @Test
    fun `poll now restarts the loop immediately`() = runTest {
        var calls = 0
        val poller = poller(PollCadence.BADGE)
        poller.start(backgroundScope, { true }) { calls++; PollOutcome(true) }
        runCurrent()
        poller.pollNow()
        runCurrent()
        assertEquals(2, calls)
        assertTrue(poller.isRunning)
    }
}

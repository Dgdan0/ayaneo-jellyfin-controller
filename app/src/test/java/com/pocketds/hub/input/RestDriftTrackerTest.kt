package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestDriftTrackerTest {

    private val eps = 1e-6f

    @Test
    fun `an untouched stick is resting from the very first sample`() {
        val t = RestDriftTracker()
        t.update(0.03f, 0L)
        assertEquals(0.03f, t.drift(), eps)
        assertEquals(1, t.samples())
    }

    @Test
    fun `deliberate deflection never counts as drift`() {
        // The bug this class exists to fix: pushing the stick to the limit once
        // made the probe report a drift of 1.0 and suggest a 0.50 dead zone.
        val t = RestDriftTracker()
        t.update(1.0f, 0L)
        assertEquals(0f, t.drift(), eps)
        assertEquals(0, t.samples())
    }

    @Test
    fun `samples during the settle window after a push are ignored`() {
        val t = RestDriftTracker(settleMs = 1_500L)
        t.update(0.9f, 0L)        // pushed
        t.update(0.30f, 200L)     // springing back, not yet trustworthy
        t.update(0.10f, 900L)
        assertEquals(0f, t.drift(), eps)
        assertEquals(0, t.samples())
    }

    @Test
    fun `samples after the settle window count`() {
        val t = RestDriftTracker(settleMs = 1_500L)
        t.update(0.9f, 0L)
        t.update(0.02f, 2_000L)
        assertEquals(0.02f, t.drift(), eps)
        assertEquals(1, t.samples())
    }

    @Test
    fun `drift is the worst resting sample, not the latest`() {
        val t = RestDriftTracker()
        t.update(0.01f, 0L)
        t.update(0.06f, 100L)
        t.update(0.02f, 200L)
        assertEquals(0.06f, t.drift(), eps)
    }

    @Test
    fun `a later push restarts the settle window`() {
        val t = RestDriftTracker(settleMs = 1_000L)
        t.update(0.02f, 0L)       // resting, counts
        t.update(0.8f, 5_000L)    // pushed again
        t.update(0.40f, 5_500L)   // still settling, must not count
        assertEquals(0.02f, t.drift(), eps)
        assertEquals(1, t.samples())
    }

    @Test
    fun `settling reports whether the figure can be trusted yet`() {
        val t = RestDriftTracker(settleMs = 1_000L)
        assertFalse("untouched is not settling", t.settling(0L))
        t.update(0.9f, 0L)
        assertTrue(t.settling(500L))
        assertFalse(t.settling(1_500L))
    }

    @Test
    fun `a value exactly at the threshold is treated as resting`() {
        // The boundary has to fall somewhere; resting is the safe side, because
        // a stick that genuinely sits at 0.5 is broken and should be reported.
        val t = RestDriftTracker(activeThreshold = 0.5f)
        t.update(0.5f, 0L)
        assertEquals(0.5f, t.drift(), eps)
    }

    @Test
    fun `reset clears the figure and the settle window`() {
        val t = RestDriftTracker()
        t.update(0.9f, 0L)
        t.update(0.04f, 5_000L)
        t.reset()
        assertEquals(0f, t.drift(), eps)
        assertEquals(0, t.samples())
        assertFalse(t.settling(5_100L))
    }
}

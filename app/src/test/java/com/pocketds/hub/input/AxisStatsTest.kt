package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisStatsTest {

    private val eps = 1e-6f

    @Test
    fun `an axis that never reported is absent`() {
        val stats = AxisStats()
        assertNull(stats.reading(PadNames.AXIS_X))
        assertTrue(stats.snapshot().isEmpty())
    }

    @Test
    fun `tracks current min and max independently`() {
        val stats = AxisStats()
        listOf(0.0f, 0.9f, -0.4f, 0.2f).forEach { stats.record(PadNames.AXIS_X, it) }
        val r = stats.reading(PadNames.AXIS_X)!!
        assertEquals(0.2f, r.current, eps)   // the last value, not the largest
        assertEquals(-0.4f, r.min, eps)
        assertEquals(0.9f, r.max, eps)
        assertEquals(4, r.samples)
    }

    @Test
    fun `maxAbs takes the larger side regardless of sign`() {
        val stats = AxisStats()
        stats.record(PadNames.AXIS_Y, -0.8f)
        stats.record(PadNames.AXIS_Y, 0.3f)
        // A stick that drifts hard negative needs a dead zone sized for 0.8,
        // not for the 0.3 the positive side happened to reach.
        assertEquals(0.8f, stats.reading(PadNames.AXIS_Y)!!.maxAbs, eps)
    }

    @Test
    fun `span is the full travel seen`() {
        val stats = AxisStats()
        stats.record(PadNames.AXIS_X, -1.0f)
        stats.record(PadNames.AXIS_X, 1.0f)
        assertEquals(2.0f, stats.reading(PadNames.AXIS_X)!!.span, eps)
    }

    @Test
    fun `axes are kept separate and in first-seen order`() {
        val stats = AxisStats()
        stats.record(PadNames.AXIS_Y, 0.5f)
        stats.record(PadNames.AXIS_X, 0.1f)
        stats.record(PadNames.AXIS_Y, 0.7f)
        assertEquals(listOf(PadNames.AXIS_Y, PadNames.AXIS_X), stats.snapshot().map { it.axis })
        assertEquals(0.7f, stats.reading(PadNames.AXIS_Y)!!.max, eps)
        assertEquals(0.1f, stats.reading(PadNames.AXIS_X)!!.max, eps)
    }

    @Test
    fun `widestSpan reports the largest of the axes asked about and ignores others`() {
        val stats = AxisStats()
        stats.record(PadNames.AXIS_X, 0.04f)
        stats.record(PadNames.AXIS_Y, -0.07f)
        stats.record(PadNames.AXIS_RZ, 0.9f)   // a trigger being held, not drift
        val sticks = intArrayOf(PadNames.AXIS_X, PadNames.AXIS_Y)
        assertEquals(0.07f, stats.widestSpan(sticks), eps)
    }

    @Test
    fun `widestSpan is zero when nothing has reported`() {
        assertEquals(0f, AxisStats().widestSpan(PadNames.WATCHED_AXES), eps)
    }

    @Test
    fun `reset forgets everything`() {
        val stats = AxisStats()
        stats.record(PadNames.AXIS_X, 0.5f)
        stats.reset()
        assertNull(stats.reading(PadNames.AXIS_X))
    }

    @Test
    fun `a clean stick still gets the floor dead zone`() {
        // Believing a reported 0 gives a cursor that creeps, so there is a floor.
        assertEquals(AxisStats.FLOOR, AxisStats.suggestDeadZone(0f), eps)
        assertEquals(AxisStats.FLOOR, AxisStats.suggestDeadZone(0.01f), eps)
    }

    @Test
    fun `a drifting stick gets a dead zone above its measured drift`() {
        val suggested = AxisStats.suggestDeadZone(0.20f)
        assertTrue("$suggested must clear the measured drift", suggested > 0.20f)
        assertEquals(0.30f, suggested, eps)
    }

    @Test
    fun `the suggestion rounds up so it never lands under the drift`() {
        // 0.111 * 1.5 = 0.1665; rounding to 0.16 would sit under a 1.5x margin.
        assertEquals(0.17f, AxisStats.suggestDeadZone(0.111f), eps)
    }

    @Test
    fun `a wildly broken stick is capped rather than swallowing the whole range`() {
        // A dead zone of 1.0 would mean the stick never registers at all; better
        // to cap and let the reading on screen say the hardware is the problem.
        assertEquals(0.50f, AxisStats.suggestDeadZone(0.9f), eps)
    }
}

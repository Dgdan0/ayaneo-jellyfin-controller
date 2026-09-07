package com.pocketds.hub.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerLatchTest {

    private fun latch() = TriggerLatch(fireAt = 0.60f, rearmAt = 0.35f)

    @Test
    fun `fires once on the way past the threshold`() {
        val t = latch()
        assertFalse(t.update(0.10f))
        assertFalse(t.update(0.55f))
        assertTrue(t.update(0.62f))
    }

    @Test
    fun `does not fire again while held down`() {
        val t = latch()
        assertTrue(t.update(0.70f))
        assertFalse(t.update(0.85f))
        assertFalse(t.update(1.00f))
        assertFalse(t.update(0.95f))
    }

    @Test
    fun `a partial release does not re-arm`() {
        // The gap between the thresholds is the whole point: releasing to 0.40
        // and squeezing again is one continuous pull, not two.
        val t = latch()
        assertTrue(t.update(0.90f))
        assertFalse(t.update(0.40f))
        assertFalse(t.update(0.90f))
    }

    @Test
    fun `a full release re-arms`() {
        val t = latch()
        assertTrue(t.update(0.90f))
        assertFalse(t.update(0.30f))
        assertTrue(t.update(0.90f))
    }

    @Test
    fun `a slow squeeze across the band fires exactly once`() {
        // With a single threshold, a finger dithering around it produces a
        // stream of fires and the list pages away from under you.
        val t = latch()
        var fires = 0
        var v = 0f
        while (v <= 1.0f) {
            if (t.update(v)) fires++
            v += 0.01f
        }
        assertTrue("fired $fires times", fires == 1)
    }

    @Test
    fun `a value sitting exactly on the threshold fires`() {
        assertTrue(latch().update(0.60f))
    }

    @Test
    fun `a value sitting exactly on the rearm point re-arms`() {
        val t = latch()
        t.update(1.0f)
        assertFalse(t.update(0.35f))
        assertTrue(t.update(1.0f))
    }

    @Test
    fun `reset re-arms a held trigger`() {
        val t = latch()
        assertTrue(t.update(0.90f))
        t.reset()
        assertTrue(t.update(0.90f))
    }

    @Test
    fun `noise below the rearm point never fires`() {
        // The declared flat on this hardware is 0.059, well under rearmAt.
        val t = latch()
        repeat(100) { assertFalse(t.update(0.05f)) }
    }
}

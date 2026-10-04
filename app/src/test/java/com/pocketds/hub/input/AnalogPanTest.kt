package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogPanTest {

    @Test
    fun `a resting stick moves nothing and lets the ticker stop`() {
        val pan = AnalogPan()
        assertNull(pan.update(0.004f, -0.003f, 0L))
        assertNull(pan.update(0.1f, 0f, 16L))
        assertTrue(pan.idle())
    }

    @Test
    fun `a push starts the clock, then moves by deflection times the frame`() {
        val pan = AnalogPan()
        assertNull(pan.update(1f, 0f, 0L))
        assertFalse(pan.idle())
        val frame = pan.update(1f, 0f, 16L)!!
        // Full deflection: one stick-second per second, so 16 ms moves 0.016.
        assertEquals(0.016f, frame.dx, 0.0001f)
        assertEquals(0f, frame.dy, 0.0001f)
    }

    @Test
    fun `the same push moves the same distance at any frame rate`() {
        fun travel(frameMs: Long): Float {
            val pan = AnalogPan()
            pan.update(0f, 0.8f, 0L)
            var total = 0f
            var now = 0L
            while (now < 1_000L) {
                now += frameMs
                total += pan.update(0f, 0.8f, now)?.dy ?: 0f
            }
            return total
        }
        assertEquals(travel(16L), travel(6L), 0.01f)
    }

    @Test
    fun `a nudge moves much less than a shove`() {
        fun speed(deflection: Float): Float {
            val pan = AnalogPan()
            pan.update(deflection, 0f, 0L)
            return pan.update(deflection, 0f, 100L)!!.dx
        }
        assertTrue(speed(0.3f) < speed(1f) * 0.25f)
        assertTrue(speed(0.3f) > 0f)
    }

    @Test
    fun `a long gap moves no more than a capped frame, and a clock going back moves nothing`() {
        val pan = AnalogPan(maxFrameMs = 50L)
        pan.update(-1f, 0f, 0L)
        assertEquals(-0.05f, pan.update(-1f, 0f, 2_000L)!!.dx, 0.0001f)
        // A frame clock a little behind the last event: nothing to add.
        assertNull(pan.update(-1f, 0f, 1_990L))
        assertEquals(-0.01f, pan.update(-1f, 0f, 2_010L)!!.dx, 0.0001f)
    }

    @Test
    fun `a diagonal keeps its direction`() {
        val pan = AnalogPan()
        pan.update(0.7f, -0.7f, 0L)
        val frame = pan.update(0.7f, -0.7f, 20L)!!
        assertEquals(frame.dx, -frame.dy, 0.0001f)
        assertTrue(frame.dx > 0f)
    }
}

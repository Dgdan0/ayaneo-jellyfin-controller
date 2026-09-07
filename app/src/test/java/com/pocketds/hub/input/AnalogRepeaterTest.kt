package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogRepeaterTest {

    private fun repeater() = AnalogRepeater(
        deadZone = 0.20f,
        initialDelayMs = 400L,
        slowIntervalMs = 200L,
        fastIntervalMs = 50L,
        axisSwapMargin = 1.30f
    )

    // --- the dead zone -----------------------------------------------------

    @Test
    fun `a resting thumb never steps, however long it rests there`() {
        val r = repeater()
        var t = 0L
        repeat(200) {
            assertNull("drift must never scroll", r.update(0.05f, -0.04f, t))
            t += 16
        }
        assertTrue(r.idle())
    }

    @Test
    fun `magnitude is the vector, not either axis alone`() {
        // 0.15 on each axis is under the 0.20 dead zone per-axis, but the vector
        // is 0.212, so a genuine 45-degree push must register. Which way a dead
        // tie resolves is arbitrary; that it registers at all is the point.
        assertNotNull(repeater().update(0.15f, 0.15f, 0L))
    }

    @Test
    fun `a dead tie breaks the same way every time`() {
        // Not because horizontal is more correct, but because a tie resolving
        // differently run to run would make diagonal pushes feel broken.
        repeat(5) { assertEquals(Direction.RIGHT, repeater().update(0.5f, 0.5f, 0L)) }
    }

    // --- the first step ----------------------------------------------------

    @Test
    fun `crossing the dead zone steps once, immediately`() {
        val r = repeater()
        assertEquals(Direction.RIGHT, r.update(0.9f, 0f, 0L))
        // ...and does not step again on the very next frame.
        assertNull(r.update(0.9f, 0f, 16L))
    }

    @Test
    fun `a nudge moves exactly one item`() {
        val r = repeater()
        assertEquals(Direction.DOWN, r.update(0f, 0.5f, 0L))
        var steps = 0
        var t = 16L
        while (t < 300L) {                    // released before the initial delay
            if (r.update(0f, 0.5f, t) != null) steps++
            t += 16
        }
        assertEquals("a tap of the stick must not overshoot", 0, steps)
    }

    // --- repeat ------------------------------------------------------------

    @Test
    fun `holding waits out the initial delay before repeating`() {
        val r = repeater()
        r.update(0f, -1f, 0L)
        assertNull(r.update(0f, -1f, 399L))
        assertEquals(Direction.UP, r.update(0f, -1f, 400L))
    }

    @Test
    fun `after the initial delay it repeats at the mapped interval`() {
        val r = repeater()
        r.update(1f, 0f, 0L)                  // step 1
        r.update(1f, 0f, 400L)                // step 2, initial delay served
        assertNull(r.update(1f, 0f, 449L))    // full deflection maps to 50ms
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 450L))
    }

    @Test
    fun `a harder push repeats faster`() {
        fun stepsIn(magnitude: Float): Int {
            val r = repeater()
            var steps = 0
            var t = 0L
            while (t <= 2_000L) {
                if (r.update(magnitude, 0f, t) != null) steps++
                t += 8
            }
            return steps
        }
        val gentle = stepsIn(0.25f)
        val medium = stepsIn(0.6f)
        val hard = stepsIn(1.0f)
        assertTrue("$gentle < $medium", gentle < medium)
        assertTrue("$medium < $hard", medium < hard)
    }

    @Test
    fun `a dropped frame emits one step, never a catch-up burst`() {
        // The bug this guards: accumulating lastEmit by the interval leaves the
        // repeater owing several steps after a GC pause, and it pays them out at
        // once -- the list visibly jumps. A temporal stepper must not accumulate.
        val r = repeater()
        r.update(1f, 0f, 0L)
        r.update(1f, 0f, 400L)
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 1_300L))  // 900ms gap
        assertNull("owes nothing after the gap", r.update(1f, 0f, 1_310L))
    }

    // --- direction changes -------------------------------------------------

    @Test
    fun `reversing steps immediately and re-arms the initial delay`() {
        val r = repeater()
        r.update(1f, 0f, 0L)
        r.update(1f, 0f, 400L)
        // Correcting an overshoot must be instant, not 400ms later.
        assertEquals(Direction.LEFT, r.update(-1f, 0f, 410L))
        assertNull(r.update(-1f, 0f, 700L))
        assertEquals(Direction.LEFT, r.update(-1f, 0f, 810L))
    }

    @Test
    fun `returning to centre and pushing again steps immediately`() {
        val r = repeater()
        r.update(0f, 1f, 0L)
        assertNull(r.update(0f, 0f, 100L))          // released
        assertTrue(r.idle())
        assertEquals(Direction.DOWN, r.update(0f, 1f, 110L))
    }

    // --- diagonal hysteresis -----------------------------------------------

    @Test
    fun `an exact diagonal keeps the axis it started on`() {
        val r = repeater()
        assertEquals(Direction.RIGHT, r.update(1f, 0.2f, 0L))
        // Now a true 45 degrees. Without hysteresis this flips to DOWN and the
        // grid walks diagonally at random.
        r.update(0.7f, 0.7f, 400L)
        assertEquals(Direction.RIGHT, r.update(0.7f, 0.7f, 800L))
    }

    @Test
    fun `the other axis wins once it clearly beats the margin`() {
        val r = repeater()
        assertEquals(Direction.RIGHT, r.update(1f, 0.2f, 0L))
        // 0.5 vs 1.0 clears the 1.30 margin comfortably.
        assertEquals(Direction.DOWN, r.update(0.5f, 1.0f, 16L))
    }

    @Test
    fun `a fresh push with no history picks the dominant axis`() {
        assertEquals(Direction.DOWN, repeater().update(0.3f, 0.9f, 0L))
        assertEquals(Direction.LEFT, repeater().update(-0.9f, 0.3f, 0L))
    }

    @Test
    fun `positive y is down, matching Android AXIS_Y`() {
        // Getting this backwards inverts the whole UI and is invisible in review.
        assertEquals(Direction.DOWN, repeater().update(0f, 1f, 0L))
        assertEquals(Direction.UP, repeater().update(0f, -1f, 0L))
    }

    // --- idle / reset ------------------------------------------------------

    @Test
    fun `idle reports whether the frame ticker still has work`() {
        val r = repeater()
        assertTrue(r.idle())
        r.update(1f, 0f, 0L)
        assertFalse(r.idle())
        r.update(0f, 0f, 16L)
        assertTrue(r.idle())
    }

    @Test
    fun `reset forgets the held direction`() {
        val r = repeater()
        r.update(1f, 0f, 0L)
        r.reset()
        assertTrue(r.idle())
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 16L))
    }

    // --- the hat -----------------------------------------------------------

    @Test
    fun `the hat steps once on press and repeats at a fixed rate`() {
        // ABS_HAT0X gives one event at 1.0 and one at 0.0 on release, with no
        // repeatCount and no OS auto-repeat, so all of the repeating is ours.
        val r = AnalogRepeater.forHat()
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 0L))
        assertNull(r.update(1f, 0f, 399L))
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 400L))
        assertNull(r.update(1f, 0f, 539L))
        assertEquals(Direction.RIGHT, r.update(1f, 0f, 540L))
    }

    @Test
    fun `the hat repeats at one speed regardless of magnitude`() {
        fun intervalAt(value: Float): Long {
            val r = AnalogRepeater.forHat()
            r.update(value, 0f, 0L)
            r.update(value, 0f, 400L)
            var t = 401L
            while (r.update(value, 0f, t) == null) t += 1
            return t - 400L
        }
        assertEquals(intervalAt(1.0f), intervalAt(0.75f))
    }

    @Test
    fun `a released hat is idle`() {
        val r = AnalogRepeater.forHat()
        r.update(0f, -1f, 0L)
        assertNull(r.update(0f, 0f, 16L))
        assertTrue(r.idle())
    }
}

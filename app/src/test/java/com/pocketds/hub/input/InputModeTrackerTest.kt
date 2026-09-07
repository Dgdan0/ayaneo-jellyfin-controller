package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputModeTrackerTest {

    @Test
    fun `starts directional so the focus ring is visible at launch`() {
        val t = InputModeTracker()
        assertEquals(InputMode.DIRECTIONAL, t.mode)
        assertTrue(t.showFocusRing)
    }

    @Test
    fun `a tap hides the focus ring`() {
        val t = InputModeTracker()
        assertTrue("mode changed", t.onPointer())
        assertEquals(InputMode.POINTER, t.mode)
        assertFalse(t.showFocusRing)
    }

    @Test
    fun `picking the pad back up brings the ring back`() {
        val t = InputModeTracker()
        t.onPointer()
        assertTrue("mode changed", t.onDirectional())
        assertTrue(t.showFocusRing)
    }

    @Test
    fun `repeated input in the same mode reports no change`() {
        // The caller repaints on a change, so a stream of stick events must not
        // mean a repaint per event.
        val t = InputModeTracker()
        assertFalse(t.onDirectional())
        assertFalse(t.onDirectional())
        assertTrue(t.onPointer())
        assertFalse(t.onPointer())
    }

    @Test
    fun `the last input always wins`() {
        val t = InputModeTracker()
        t.onPointer()
        t.onDirectional()
        t.onPointer()
        assertEquals(InputMode.POINTER, t.mode)
        t.onDirectional()
        assertEquals(InputMode.DIRECTIONAL, t.mode)
    }
}

package com.pocketds.hub.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLatchTest {

    @Test
    fun `the first source to report wins`() {
        val latch = SourceLatch()
        assertTrue(latch.accept(SourceLatch.SOURCE_HAT))
        assertEquals(SourceLatch.SOURCE_HAT, latch.winner())
    }

    @Test
    fun `the winner keeps working forever`() {
        val latch = SourceLatch()
        latch.accept(SourceLatch.SOURCE_HAT)
        repeat(100) { assertTrue(latch.accept(SourceLatch.SOURCE_HAT)) }
    }

    @Test
    fun `a second source for the same input is ignored`() {
        // The measured case: one L2 pull produces an analog BRAKE movement and a
        // BUTTON_L2 key event. Acting on both pages the list twice.
        val latch = SourceLatch()
        assertTrue(latch.accept(SourceLatch.SOURCE_ANALOG))
        assertFalse(latch.accept(SourceLatch.SOURCE_KEYS))
        assertFalse(latch.accept(SourceLatch.SOURCE_KEYS))
    }

    @Test
    fun `whichever source arrives first is the one that works`() {
        // Latching rather than preferring a source in advance is what makes this
        // work on hardware we have not measured.
        val keysFirst = SourceLatch()
        assertTrue(keysFirst.accept(SourceLatch.SOURCE_KEYS))
        assertFalse(keysFirst.accept(SourceLatch.SOURCE_HAT))

        val hatFirst = SourceLatch()
        assertTrue(hatFirst.accept(SourceLatch.SOURCE_HAT))
        assertFalse(hatFirst.accept(SourceLatch.SOURCE_KEYS))
    }

    @Test
    fun `nothing has won before anything reports`() {
        assertNull(SourceLatch().winner())
    }

    @Test
    fun `reset re-opens the latch`() {
        val latch = SourceLatch()
        latch.accept(SourceLatch.SOURCE_HAT)
        latch.reset()
        assertNull(latch.winner())
        assertTrue(latch.accept(SourceLatch.SOURCE_KEYS))
    }

    @Test
    fun `separate inputs latch independently`() {
        // Left and right triggers each get their own latch; one settling on
        // analog must not decide for the other.
        val left = SourceLatch()
        val right = SourceLatch()
        left.accept(SourceLatch.SOURCE_ANALOG)
        assertTrue(right.accept(SourceLatch.SOURCE_KEYS))
        assertEquals(SourceLatch.SOURCE_ANALOG, left.winner())
        assertEquals(SourceLatch.SOURCE_KEYS, right.winner())
    }
}

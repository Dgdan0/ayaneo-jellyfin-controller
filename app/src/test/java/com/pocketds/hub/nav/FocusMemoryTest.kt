package com.pocketds.hub.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusMemoryTest {

    @Test
    fun `an unknown list starts at the top`() {
        assertEquals(0, FocusMemory().restore("library", 20))
    }

    @Test
    fun `it comes back to where you were`() {
        val m = FocusMemory()
        m.remember("library", 7)
        assertEquals(7, m.restore("library", 20))
    }

    @Test
    fun `a shorter list clamps to the last item rather than crashing`() {
        // The real case: a download finishes and drops out of the queue while
        // you are two screens deep, and index 40 no longer exists.
        val m = FocusMemory()
        m.remember("downloads", 40)
        assertEquals(11, m.restore("downloads", 12))
    }

    @Test
    fun `an empty list has nothing to focus`() {
        val m = FocusMemory()
        m.remember("search", 3)
        assertEquals(-1, m.restore("search", 0))
    }

    @Test
    fun `lists are remembered independently`() {
        val m = FocusMemory()
        m.remember("library", 4)
        m.remember("downloads", 9)
        assertEquals(4, m.restore("library", 50))
        assertEquals(9, m.restore("downloads", 50))
    }

    @Test
    fun `forget resets a list to the top, for a new query`() {
        val m = FocusMemory()
        m.remember("search", 12)
        m.forget("search")
        assertEquals(0, m.restore("search", 50))
    }

    @Test
    fun `remembering a negative index forgets instead of storing nonsense`() {
        val m = FocusMemory()
        m.remember("library", 5)
        m.remember("library", -1)
        assertEquals(0, m.restore("library", 50))
    }

    @Test
    fun `clear forgets everything`() {
        val m = FocusMemory()
        m.remember("a", 3)
        m.remember("b", 4)
        m.clear()
        assertEquals(0, m.restore("a", 50))
        assertEquals(0, m.restore("b", 50))
    }
}

package com.pocketds.hub.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionStacksTest {

    private val log = mutableListOf<String>()
    private fun screen(name: String) = FakeScreen(name, log)

    @Test
    fun `starts on the first section`() {
        assertEquals(0, SectionStacks(4).current)
    }

    @Test
    fun `each section keeps its own depth`() {
        val s = SectionStacks(3)
        s.push(screen("lib"))
        s.push(screen("series"))
        s.push(screen("season"))
        assertEquals(3, s.depth)

        s.switch(+1)
        assertEquals("a fresh section starts empty", 0, s.depth)

        s.switch(-1)
        assertEquals("and the first one is still three deep", 3, s.depth)
    }

    @Test
    fun `switching away hides the top and switching back shows it, without destroying`() {
        val s = SectionStacks(2)
        s.push(screen("lib"))
        s.push(screen("series"))
        log.clear()
        s.switch(+1)
        s.switch(-1)
        // No destroy anywhere: coming back must not re-fetch over a slow link.
        assertEquals(listOf("series.hide", "series.show"), log)
    }

    @Test
    fun `switching wraps at both ends`() {
        val s = SectionStacks(4)
        s.switch(-1)
        assertEquals("L1 from the first wraps to the last", 3, s.current)
        s.switch(+1)
        assertEquals(0, s.current)
    }

    @Test
    fun `switching reports whether anything changed`() {
        val s = SectionStacks(4)
        assertTrue(s.switch(+1))
        assertFalse("a delta of zero is not a change", s.switch(0))
    }

    @Test
    fun `a single-section app cannot switch`() {
        val s = SectionStacks(1)
        assertFalse(s.switch(+1))
        assertEquals(0, s.current)
    }

    @Test
    fun `select jumps straight to a section, for tapping a tab`() {
        val s = SectionStacks(4)
        s.push(screen("a"))
        log.clear()
        assertTrue(s.select(2))
        assertEquals(2, s.current)
        assertEquals(listOf("a.hide"), log)
        assertFalse("selecting the current section is not a change", s.select(2))
    }

    @Test
    fun `back pops within the current section only`() {
        val s = SectionStacks(2)
        s.push(screen("lib"))
        s.push(screen("series"))
        assertTrue(s.back())
        assertEquals(1, s.depth)
        assertFalse("at a section root, B does nothing", s.back())
    }

    @Test
    fun `back at a root does not fall through into another section`() {
        val s = SectionStacks(2)
        s.push(screen("lib"))
        s.switch(+1)
        s.push(screen("downloads"))
        assertFalse(s.back())
        assertEquals("still on the second section", 1, s.current)
        assertEquals(1, s.depth)
    }

    @Test
    fun `zero sections is rejected rather than failing later`() {
        try {
            SectionStacks(0)
            throw AssertionError("should have refused")
        } catch (expected: IllegalArgumentException) {
            // as intended
        }
    }
}

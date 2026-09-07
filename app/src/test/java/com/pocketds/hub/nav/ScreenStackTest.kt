package com.pocketds.hub.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records the callbacks it receives, so ordering can be asserted. */
class FakeScreen(val name: String, private val log: MutableList<String>) : StackScreen {
    override fun onShow() { log.add("$name.show") }
    override fun onHide() { log.add("$name.hide") }
    override fun onDestroyView() { log.add("$name.destroy") }
}

class ScreenStackTest {

    private val log = mutableListOf<String>()
    private fun screen(name: String) = FakeScreen(name, log)

    @Test
    fun `a new stack is empty`() {
        val stack = ScreenStack()
        assertTrue(stack.isEmpty)
        assertEquals(0, stack.depth)
        assertNull(stack.peek())
    }

    @Test
    fun `pushing the first screen shows it`() {
        val stack = ScreenStack()
        val a = screen("a")
        stack.push(a)
        assertSame(a, stack.peek())
        assertEquals(listOf("a.show"), log)
    }

    @Test
    fun `pushing hides the old top before showing the new one`() {
        val stack = ScreenStack()
        stack.push(screen("a"))
        log.clear()
        stack.push(screen("b"))
        assertEquals(listOf("a.hide", "b.show"), log)
        assertEquals(2, stack.depth)
    }

    @Test
    fun `popping hides then destroys the leaver before showing what it revealed`() {
        // Order matters: showing the revealed screen first would have it start
        // loading while the departing one is still tearing down its requests.
        val stack = ScreenStack()
        stack.push(screen("a"))
        stack.push(screen("b"))
        log.clear()
        assertTrue(stack.pop())
        assertEquals(listOf("b.hide", "b.destroy", "a.show"), log)
    }

    @Test
    fun `popping the root is refused so back can fall through`() {
        val stack = ScreenStack()
        stack.push(screen("a"))
        log.clear()
        assertFalse(stack.pop())
        assertEquals("nothing happened", emptyList<String>(), log)
        assertEquals(1, stack.depth)
    }

    @Test
    fun `popping an empty stack is refused rather than crashing`() {
        assertFalse(ScreenStack().pop())
    }

    @Test
    fun `hideTop and showTop switch away without tearing down`() {
        val stack = ScreenStack()
        stack.push(screen("a"))
        stack.push(screen("b"))
        log.clear()
        stack.hideTop()
        stack.showTop()
        // No destroy: the screen and its scroll position survive the trip.
        assertEquals(listOf("b.hide", "b.show"), log)
        assertEquals(2, stack.depth)
    }

    @Test
    fun `hideTop on an empty stack does nothing`() {
        ScreenStack().hideTop()
        ScreenStack().showTop()
        assertTrue(log.isEmpty())
    }

    @Test
    fun `clear tears everything down from the top down`() {
        val stack = ScreenStack()
        stack.push(screen("a"))
        stack.push(screen("b"))
        log.clear()
        stack.clear()
        assertEquals(
            listOf("b.hide", "b.destroy", "a.hide", "a.destroy"),
            log
        )
        assertTrue(stack.isEmpty)
    }
}

package com.pocketds.hub.screens.library

import com.pocketds.hub.input.Direction
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryOrderTest {
    private val five = listOf("a", "b", "c", "d", "e")

    @Test fun `each side is named as the hub names it`() {
        assertEquals("media", LibraryOrder.side(ContentMode.MEDIA))
        assertEquals("books", LibraryOrder.side(ContentMode.BOOKS))
        assertTrue(LibraryOrder.isCustom("custom"))
        assertFalse(LibraryOrder.isCustom("name"))
        assertFalse(LibraryOrder.isCustom(""))
    }

    @Test fun `a move takes one library out and puts it back where it lands`() {
        assertEquals(listOf("b", "c", "a", "d", "e"), LibraryOrder.move(five, 0, 2))
        assertEquals(listOf("d", "a", "b", "c", "e"), LibraryOrder.move(five, 3, 0))
        assertEquals(five, LibraryOrder.move(five, 2, 2))
        // Out of range lands at the nearest end; a bad source changes nothing.
        assertEquals(listOf("b", "c", "d", "e", "a"), LibraryOrder.move(five, 0, 9))
        assertEquals(five, LibraryOrder.move(five, 7, 0))
    }

    @Test fun `a lifted tile steps along its row and a whole row up or down, never past the ends`() {
        // Three across: a b c / d e
        assertEquals(1, LibraryOrder.step(0, 5, 3, Direction.RIGHT))
        assertEquals(0, LibraryOrder.step(0, 5, 3, Direction.LEFT))
        assertEquals(3, LibraryOrder.step(0, 5, 3, Direction.DOWN))
        assertEquals(1, LibraryOrder.step(4, 5, 3, Direction.UP))
        assertEquals(4, LibraryOrder.step(4, 5, 3, Direction.RIGHT))
        // Down from c, over the short last row: the last place there is.
        assertEquals(4, LibraryOrder.step(2, 5, 3, Direction.DOWN))
        // Right from the end of a row carries on to the next row's start.
        assertEquals(3, LibraryOrder.step(2, 5, 3, Direction.RIGHT))
        assertEquals(2, LibraryOrder.step(2, 3, 3, Direction.DOWN))
    }

    @Test fun `a dragged tile takes the place under its middle`() {
        // 100 wide, 60 tall, 10 apart, three across, five places.
        assertEquals(0, LibraryOrder.slotAt(20f, 20f, 100f, 60f, 10f, 3, 5))
        assertEquals(2, LibraryOrder.slotAt(260f, 10f, 100f, 60f, 10f, 3, 5))
        assertEquals(4, LibraryOrder.slotAt(150f, 100f, 100f, 60f, 10f, 3, 5))
        // Past the last place: the last one; above or left of the grid: the first.
        assertEquals(4, LibraryOrder.slotAt(300f, 100f, 100f, 60f, 10f, 3, 5))
        assertEquals(0, LibraryOrder.slotAt(-40f, -30f, 100f, 60f, 10f, 3, 5))
    }

    @Test fun `picking up, moving and dropping saves only a changed order`() {
        val session = LibraryArrangeSession(listOf("a", "b", "c"))
        assertTrue(session.pickUp(0))
        assertEquals(1, session.step(Direction.RIGHT, 3))
        assertEquals(listOf("b", "a", "c"), session.ids)
        assertEquals(listOf("b", "a", "c"), session.drop())
        assertFalse(session.isLifted)
        // Picked up and put down where it was: nothing to save.
        session.pickUp(1)
        assertNull(session.drop())
    }

    @Test fun `a save that fails puts the last saved order back`() {
        val session = LibraryArrangeSession(listOf("a", "b", "c"))
        session.pickUp(2)
        session.step(Direction.LEFT, 3)
        val sent = session.drop()
        assertEquals(listOf("a", "c", "b"), sent)
        assertEquals(listOf("a", "b", "c"), session.rollback())
        assertEquals(listOf("a", "b", "c"), session.ids)
    }

    @Test fun `a save that lands becomes what a later failure goes back to`() {
        val session = LibraryArrangeSession(listOf("a", "b", "c"))
        session.pickUp(0)
        session.step(Direction.RIGHT, 3)
        session.saved(session.drop()!!)
        session.pickUp(2)
        session.step(Direction.LEFT, 3)
        assertEquals(listOf("b", "c", "a"), session.drop())
        assertEquals(listOf("b", "a", "c"), session.rollback())
    }

    @Test fun `a drag moves the lifted library to the place it is over`() {
        val session = LibraryArrangeSession(listOf("a", "b", "c", "d"))
        session.pickUp(3)
        assertTrue(session.moveLiftedTo(0))
        assertEquals(listOf("d", "a", "b", "c"), session.ids)
        assertFalse(session.moveLiftedTo(0))
        assertEquals(0, session.lifted)
    }

    @Test fun `the order the hub sends replaces the session's, lifted or not`() {
        val session = LibraryArrangeSession(listOf("a", "b"))
        session.pickUp(0)
        session.reset(listOf("b", "a", "c"))
        assertFalse(session.isLifted)
        assertEquals(listOf("b", "a", "c"), session.ids)
        assertEquals(listOf("b", "a", "c"), session.rollback())
    }
}

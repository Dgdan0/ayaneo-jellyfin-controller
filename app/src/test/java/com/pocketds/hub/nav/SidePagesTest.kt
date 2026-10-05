package com.pocketds.hub.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media and Books keep their own pages (#18): each content tab has a stack per side. */
class SidePagesTest {
    private val log = mutableListOf<String>()
    private val sides = HashMap<StackScreen, String?>()

    private fun page(name: String, side: String?): FakeScreen = FakeScreen(name, log).also { sides[it] = side }

    /** Three content tabs and a utility page; each tab's root shows either side. */
    private fun tabs(): SectionStacks = SectionStacks(4).apply {
        for (index in 0 until 4) {
            select(index)
            push(page("root$index", null))
        }
        select(0)
    }

    /** A tab's stack, root first, read by parking every page and putting them back. */
    private fun pages(stacks: SectionStacks, index: Int): List<String> {
        val stack = stacks.stack(index)
        val taken = mutableListOf<StackScreen>()
        while (stack.depth > 1) taken += stack.park()!!
        val root = stack.peek() as FakeScreen
        taken.asReversed().forEach(stack::restore)
        return listOf(root.name) + taken.asReversed().map { (it as FakeScreen).name }
    }

    @Test fun `switching sides keeps each side's pages and switching back returns to them`() {
        val stacks = tabs()
        val pages = SidePages(stacks, 3) { sides[it] }
        // Media: a film's page on Library (tab 2), a title on Discover (tab 1).
        stacks.select(2); stacks.push(page("film", "media"))
        stacks.select(1); stacks.push(page("title", "media")); stacks.push(page("cast", "media"))
        stacks.select(0)
        log.clear()

        assertTrue(pages.show("books").isEmpty())
        assertEquals(listOf("root1"), pages(stacks, 1))
        assertEquals(listOf("root2"), pages(stacks, 2))
        // The tab on screen kept its root, and the hidden tabs' pages were only put aside.
        assertTrue(log.isEmpty())

        // Books: a book's page on Library.
        stacks.select(2); stacks.push(page("book", "books")); stacks.select(0)
        log.clear()
        assertTrue(pages.show("media").isEmpty())
        assertEquals(listOf("root1", "title", "cast"), pages(stacks, 1))
        assertEquals(listOf("root2", "film"), pages(stacks, 2))
        assertEquals(listOf("book"), pages.parkedFor(2, "books").map { (it as FakeScreen).name })
        assertTrue("Nothing was torn down: $log", log.none { it.endsWith(".destroy") })

        pages.show("books")
        assertEquals(listOf("root2", "book"), pages(stacks, 2))
    }

    @Test fun `on the tab showing, the page that leaves is hidden and the one put back shown`() {
        val stacks = tabs()
        val pages = SidePages(stacks, 3) { sides[it] }
        val book = page("book", "books")
        stacks.select(1); stacks.push(book)
        pages.show("media")
        // Back at Discover's root, on Media: Books again brings the book's page back here.
        log.clear()
        pages.show("books")
        assertSame(book, stacks.stack(1).peek())
        assertEquals(listOf("root1.hide", "book.show"), log)
        log.clear()
        pages.show("media")
        assertEquals(listOf("book.hide", "root1.show"), log)
    }

    @Test fun `a page of both sides stays, and so does everything under it`() {
        val stacks = tabs()
        val pages = SidePages(stacks, 3) { sides[it] }
        stacks.select(1)
        stacks.push(page("detail", "media"))
        stacks.push(page("downloads", null))
        stacks.push(page("transfer", "media"))
        stacks.select(0)
        pages.show("books")
        assertEquals(listOf("root1", "detail", "downloads"), pages(stacks, 1))
        assertEquals(listOf("transfer"), pages.parkedFor(1, "media").map { (it as FakeScreen).name })
    }

    @Test fun `pages whose tab moved on are let go of, not put back somewhere else`() {
        val stacks = tabs()
        val pages = SidePages(stacks, 3) { sides[it] }
        stacks.select(1)
        stacks.push(page("detail", "media"))
        stacks.push(page("downloads", null))
        stacks.push(page("transfer", "media"))
        stacks.select(0)
        pages.show("books")
        // On Books, Discover is taken back to its root, under the page the transfer was kept over.
        stacks.select(1); stacks.back(); stacks.back(); stacks.select(0)
        log.clear()
        val dropped = pages.show("media")
        assertEquals(listOf("transfer"), dropped.map { (it as FakeScreen).name })
        assertEquals(listOf("transfer.destroy"), log)
        assertEquals(listOf("root1"), pages(stacks, 1))
    }

    @Test fun `utility pages are left alone and clearing lets go of every kept page`() {
        val stacks = tabs()
        val pages = SidePages(stacks, 3) { sides[it] }
        stacks.select(3); stacks.push(page("about", "media"))
        stacks.select(2); stacks.push(page("film", "media"))
        stacks.select(0)
        pages.show("books")
        assertEquals(listOf("root3", "about"), pages(stacks, 3))
        log.clear()
        assertEquals(listOf("film"), pages.clear().map { (it as FakeScreen).name })
        assertEquals(listOf("film.destroy"), log)
        pages.show("media")
        assertEquals(listOf("root2"), pages(stacks, 2))
    }
}

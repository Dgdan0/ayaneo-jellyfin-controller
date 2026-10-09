package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contents' page numbers for one open book (#55): what is known at once, what waits for an anchor, what never has one. */
class ContentsPagesTest {
    private val hrefs = listOf("OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml", "OEBPS/ch3.xhtml")
    private val sizes = listOf(10, 20, 30)
    private val starts = listOf(0.0, 0.25, 0.5)

    private fun pages(bookPages: Int) = ContentsPages(bookPages, hrefs, sizes, starts)

    @Test fun `an entry that names a file has its number at once`() {
        val book = pages(400)
        assertEquals(1, book.page("OEBPS/ch1.xhtml"))
        assertEquals(100, book.page("OEBPS/ch2.xhtml"))
        assertEquals(200, book.page("OEBPS/ch3.xhtml"))
        // Without the hub's count, Readium's positions.
        val positions = pages(0)
        assertEquals(listOf(1, 11, 31), hrefs.map { positions.page(it) })
        // An empty fragment is no fragment.
        assertEquals(100, book.page("OEBPS/ch2.xhtml#"))
    }

    @Test fun `an entry into a file waits for its anchor and the rest do not wait for it`() {
        val book = pages(400)
        assertNull(book.page("OEBPS/ch2.xhtml#part2"))
        // The whole-file entries beside it are not held up.
        assertEquals(100, book.page("OEBPS/ch2.xhtml"))
        book.learn(mapOf(1 to mapOf("part2" to 0.5, "part3" to 0.75)))
        assertEquals(150, book.page("OEBPS/ch2.xhtml#part2"))
        assertEquals(175, book.page("OEBPS/ch2.xhtml#part3"))
    }

    @Test fun `several entries in one file get their own pages`() {
        val book = pages(400)
        book.learn(mapOf(1 to mapOf("a" to 0.0, "b" to 0.25, "c" to 0.5, "d" to 0.875)))
        val pages = listOf("a", "b", "c", "d").map { book.page("OEBPS/ch2.xhtml#$it") }
        assertEquals(listOf(100, 125, 150, 187), pages)
        assertEquals(pages.distinct(), pages)
        // An anchor at the very top of the file is the page the file starts on.
        assertEquals(book.page("OEBPS/ch2.xhtml"), book.page("OEBPS/ch2.xhtml#a"))
    }

    @Test fun `an entry whose file is not in the reading order has no number`() {
        val book = pages(400)
        book.learn(mapOf(1 to mapOf("x" to 0.5)))
        assertNull(book.page("OEBPS/notes.xhtml"))
        assertNull(book.page("OEBPS/notes.xhtml#x"))
        assertNull(pages(0).page("OEBPS/notes.xhtml"))
        // Nor is anything looked for in it.
        assertEquals(emptyMap<Int, Set<String>>(), book.wanted(listOf("OEBPS/notes.xhtml#x", "OEBPS/notes.xhtml")))
    }

    @Test fun `an anchor that was not found leaves the entry without a number, never a guess`() {
        val book = pages(400)
        book.learn(mapOf(1 to mapOf("here" to 0.5)))
        assertNull(book.page("OEBPS/ch2.xhtml#nowhere"))
        assertEquals(150, book.page("OEBPS/ch2.xhtml#here"))
        // A file that could not be read has nothing at all learnt about it.
        book.learn(emptyMap())
        assertNull(book.page("OEBPS/ch2.xhtml#here"))
    }

    @Test fun `only anchors in the book's files are looked for, each once`() {
        val book = pages(400)
        val wanted = book.wanted(listOf(
            "OEBPS/ch1.xhtml", "OEBPS/ch2.xhtml#a", "OEBPS/ch2.xhtml#b", "OEBPS/ch2.xhtml#a", "OEBPS/ch3.xhtml#",
            "OEBPS/ch3.xhtml#z%20y", "OEBPS/gone.xhtml#q"))
        assertEquals(mapOf(1 to setOf("a", "b"), 2 to setOf("z y")), wanted)
        assertEquals(emptyMap<Int, Set<String>>(), book.wanted(emptyList()))
    }

    @Test fun `what is learnt replaces what was`() {
        val book = pages(400)
        book.learn(mapOf(1 to mapOf("a" to 0.5)))
        val first = book.page("OEBPS/ch2.xhtml#a")
        book.learn(mapOf(1 to mapOf("a" to 0.9)))
        assertNotEquals(first, book.page("OEBPS/ch2.xhtml#a"))
    }

    /** #61: a Contents link and the reading order may spell a file with a space or a bracket in its name two ways. */
    @Test fun `an entry is found whichever way the file's name is spelled`() {
        val spaced = ContentsPages(400, listOf("OEBPS/Text/Author%20-%20%5BSeries%2001%5D_split_000.htm", "OEBPS/Text/Caf%C3%A9.htm"), listOf(10, 20), listOf(0.0, 0.5))
        assertEquals(1, spaced.page("OEBPS/Text/Author - [Series 01]_split_000.htm"))
        assertEquals(1, spaced.page("OEBPS/Text/Author%20-%20%5BSeries%2001%5D_split_000.htm"))
        assertEquals(200, spaced.page("OEBPS/Text/Café.htm"))
        assertEquals(mapOf(1 to setOf("c1")), spaced.wanted(listOf("OEBPS/Text/Caf%C3%A9.htm#c1", "OEBPS/Text/Author - [Series 01]_split_000.htm")))
        spaced.learn(mapOf(1 to mapOf("c1" to 0.5)))
        assertEquals(300, spaced.page("OEBPS/Text/Caf%C3%A9.htm#c1"))
    }

    @Test fun `a book with no positions yet has no numbers`() {
        val book = ContentsPages(0, emptyList(), emptyList(), emptyList())
        assertNull(book.page("OEBPS/ch1.xhtml"))
    }
}

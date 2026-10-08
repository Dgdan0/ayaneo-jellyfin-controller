package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the page in the web view tells us (#49); the script only reports offsets and the answer is read here. */
class ReadAlongPageProbeTest {
    @Test fun `the answer is a page with its first and last character and the sentences on it`() {
        val raw = """{"first":{"id":"s4","text":"Hello there.","offset":6},"last":{"id":"s6","text":"Bye.","offset":4},"visible":["s4","s5","s6"]}"""
        val probe = ReadAlongPageProbe.parse("OEBPS/ch1.xhtml", raw)!!
        assertEquals("OEBPS/ch1.xhtml", probe.href)
        assertEquals(PageEdge("s4", "Hello there.", 6), probe.first)
        assertEquals(PageEdge("s6", "Bye.", 4), probe.last)
        assertEquals(listOf("s4", "s5", "s6"), probe.visible)
    }

    @Test fun `a page with no narrated text is still a page`() {
        val probe = ReadAlongPageProbe.parse("a.xhtml", """{"first":null,"last":null,"visible":[]}""")!!
        assertNull(probe.first)
        assertNull(probe.last)
        assertTrue(probe.visible.isEmpty())
    }

    @Test fun `no answer, or one that is not a page, is none`() {
        assertNull(ReadAlongPageProbe.parse("a.xhtml", null))
        assertNull(ReadAlongPageProbe.parse("a.xhtml", ""))
        assertNull(ReadAlongPageProbe.parse("a.xhtml", "null"))
        assertNull(ReadAlongPageProbe.parse("a.xhtml", "not json"))
        assertNull(ReadAlongPageProbe.parse("a.xhtml", "[1,2]"))
    }

    @Test fun `an answer that came back as a quoted string is read once more`() {
        val inner = """{"first":{"id":"s1","text":"A b","offset":2},"last":null,"visible":["s1"]}"""
        val quoted = "\"" + inner.replace("\"", "\\\"") + "\""
        assertEquals(PageEdge("s1", "A b", 2), ReadAlongPageProbe.parse("a.xhtml", quoted)!!.first)
    }

    @Test fun `an offset outside its text is brought inside it`() {
        val probe = ReadAlongPageProbe.parse("a.xhtml", """{"first":{"id":"s1","text":"Short","offset":99},"last":{"id":"s1","text":"Short","offset":-3},"visible":["s1"]}""")!!
        assertEquals(5, probe.first!!.offset)
        assertEquals(0, probe.last!!.offset)
    }

    @Test fun `an edge without an element is no edge`() {
        val probe = ReadAlongPageProbe.parse("a.xhtml", """{"first":{"id":"","text":"x","offset":0},"last":{"text":"x","offset":0},"visible":[]}""")!!
        assertNull(probe.first)
        assertNull(probe.last)
    }

    @Test fun `the elements go into the script as a list, quoted`() {
        val script = ReadAlongPageProbe.script(listOf("s1", "odd\"id", "s3"))
        assertTrue(script.endsWith("([\"s1\",\"odd\\\"id\",\"s3\"])"))
        assertNotNull(script)
        assertFalse(script.contains("odd\"id"))
    }

    @Test fun `the script asks about the window, so a spread's two columns are one page`() {
        val script = ReadAlongPageProbe.script(listOf("s1"))
        // A character is on the page when its box meets the window: direction and columns do not come into it.
        assertTrue(script.contains("window.innerWidth"))
        assertTrue(script.contains("getClientRects"))
        assertTrue(script.contains("compareDocumentPosition"))
    }
}

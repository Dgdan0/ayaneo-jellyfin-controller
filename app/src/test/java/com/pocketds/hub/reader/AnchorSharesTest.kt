package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a Contents entry that points into a file starts in it (#55): the visible text before its anchor, over all of it. */
class AnchorSharesTest {
    private fun shares(body: String, vararg anchors: String, head: String = "") =
        AnchorShares.of("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>$head</head><body>$body</body></html>", anchors.toSet())

    @Test fun `an anchor's share is the text before it over all the text`() {
        // Sixteen letters of text; the anchors start after 0, 8 and 12 of them.
        val found = shares("<h1 id=\"a\">Aaaa</h1><p>bbbb</p><h2 id=\"c\">cccc</h2><p id=\"d\">dddd</p>", "a", "c", "d")
        assertEquals(0.0, found.getValue("a"), 1e-9)
        assertEquals(0.5, found.getValue("c"), 1e-9)
        assertEquals(0.75, found.getValue("d"), 1e-9)
    }

    @Test fun `several anchors in one file are ordered by where they are`() {
        val body = (1..10).joinToString("") { "<h2 id=\"c$it\">Chapter $it</h2><p>${"word ".repeat(40 + it)}</p>" }
        val found = shares(body, *(1..10).map { "c$it" }.toTypedArray())
        val ordered = (1..10).map { found.getValue("c$it") }
        assertEquals(ordered.sorted(), ordered)
        assertEquals(ordered.size, ordered.distinct().size)
        assertEquals(0.0, ordered.first(), 1e-9)
        assertTrue(ordered.last() < 1.0)
    }

    @Test fun `markup is not words on the page`() {
        val lean = shares("<p>aaaa</p><p id=\"x\">bbbb</p>", "x")
        // The same words with a head full of styles and scripts, attributes and comments, and tags round them.
        val heavy = shares("<!-- a comment of some length --><div class=\"a very long class name indeed\" style=\"color: red\">" +
            "<p><span>aaaa</span></p></div><script>var s = 'ignored words';</script><style>p { margin: 0 }</style><p id=\"x\"><b>bbbb</b></p>",
            "x", head = "<title>A long title that is not on the page</title><style>body { margin: 0 }</style><meta name=\"viewport\" content=\"width=device-width\"/>")
        assertEquals(0.5, lean.getValue("x"), 1e-9)
        assertEquals(0.5, heavy.getValue("x"), 1e-9)
    }

    @Test fun `white space is one space, as a browser sets it, and none at the start`() {
        val spaced = shares("\n   <p>\n      aaaa    bbbb\n   </p>\n   <p id=\"x\">cccc</p>\n", "x")
        val tight = shares("<p>aaaa bbbb</p> <p id=\"x\">cccc</p>", "x")
        assertEquals(tight.getValue("x"), spaced.getValue("x"), 1e-9)
        // "aaaa bbbb " is ten, then "cccc" four: ten of fourteen.
        assertEquals(10.0 / 14, spaced.getValue("x"), 1e-9)
    }

    @Test fun `an entity is one character`() {
        // "a&b ’c  d" is nine characters (a space counts once), then four more.
        val found = shares("<p>a&amp;b &#8217;c &nbsp;d</p><p id=\"x\">eeee</p>", "x")
        assertEquals(9.0 / 13, found.getValue("x"), 1e-9)
        // A bare ampersand in the text is a character too, and is not taken for the start of an entity.
        val bare = shares("<p>fish & chips and more</p><p id=\"x\">zzzz</p>", "x")
        assertEquals(21.0 / 25, bare.getValue("x"), 1e-9)
    }

    @Test fun `an id is found however the attribute is written`() {
        val found = shares("<p>aaaa</p><p id='single'>b</p><a name=\"old\"></a>cc<h2 xml:id=\"x\">d</h2>" +
            "<p data-id=\"nope\" class=\"id\" title=\"id=&quot;no&quot;\">e</p><p ID=\"upper\">f</p>", "single", "old", "x", "nope", "no", "upper")
        assertTrue("single" in found && "old" in found && "x" in found)
        // data-id is another attribute, a value that looks like one is a value, and the markup is case sensitive.
        assertFalse("nope" in found || "no" in found || "upper" in found)
    }

    @Test fun `a quoted value with a greater than sign does not end its tag`() {
        val found = shares("<p title=\"a > b\" id=\"first\">aaaa</p><p id=\"x\">bbbb</p>", "first", "x")
        assertEquals(0.0, found.getValue("first"), 1e-9)
        assertEquals(0.5, found.getValue("x"), 1e-9)
    }

    @Test fun `an anchor that is not there has no share, never a guessed one`() {
        val found = shares("<p id=\"here\">aaaa</p>", "here", "nowhere")
        assertEquals(setOf("here"), found.keys)
        // An id only in the head is not on the page.
        assertNull(shares("<p>aaaa</p>", "hidden", head = "<meta id=\"hidden\" name=\"x\"/>")["hidden"])
        // The first of two with the same id.
        val twice = shares("<p id=\"dup\">aaaa</p><p id=\"dup\">bbbb</p>", "dup")
        assertEquals(0.0, twice.getValue("dup"), 1e-9)
    }

    @Test fun `an anchor at the very end is still inside the file`() {
        val found = shares("<p>aaaa</p><p id=\"last\"></p>", "last")
        assertTrue(found.getValue("last") < 1.0)
        assertTrue(found.getValue("last") > 0.9)
    }

    @Test fun `a document with no words or no anchors wanted gives nothing`() {
        assertEquals(emptyMap<String, Double>(), shares("<img id=\"x\" src=\"a.png\"/>", "x"))
        assertEquals(emptyMap<String, Double>(), AnchorShares.of("", setOf("x")))
        assertEquals(emptyMap<String, Double>(), shares("<p id=\"x\">aaaa</p>"))
    }

    @Test fun `a document that is cut short or badly formed does not break the reading`() {
        assertEquals(emptyMap<String, Double>(), AnchorShares.of("<p id=\"x", setOf("x")))
        assertEquals(emptyMap<String, Double>(), AnchorShares.of("<!-- never closed <p id=\"x\">a</p>", setOf("x")))
        val found = AnchorShares.of("<p id=\"x\">aaaa</p><style>never closed", setOf("x"))
        assertEquals(0.0, found.getValue("x"), 1e-9)
        val cdata = AnchorShares.of("<p>aa</p><![CDATA[bb]]><p id=\"x\">cc</p>", setOf("x"))
        assertEquals(4.0 / 6, cdata.getValue("x"), 1e-9)
    }

    @Test fun `the anchor an href names is decoded`() {
        assertEquals("part2", AnchorShares.fragmentOf("OEBPS/ch1.xhtml#part2"))
        assertEquals("chapter 1", AnchorShares.fragmentOf("ch1.xhtml#chapter%201"))
        assertEquals("a+b", AnchorShares.fragmentOf("ch1.xhtml#a+b"))
        assertEquals("café", AnchorShares.fragmentOf("ch1.xhtml#caf%C3%A9"))
        assertNull(AnchorShares.fragmentOf("OEBPS/ch1.xhtml"))
        assertNull(AnchorShares.fragmentOf("OEBPS/ch1.xhtml#"))
        assertNull(AnchorShares.fragmentOf("OEBPS/ch1.xhtml#%20"))
        // A bad escape is kept as it is.
        assertEquals("100%", AnchorShares.fragmentOf("ch1.xhtml#100%"))
    }
}

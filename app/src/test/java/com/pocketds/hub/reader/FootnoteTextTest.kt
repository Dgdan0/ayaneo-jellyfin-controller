package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** A footnote's words for its card (#18, E5), from the HTML Readium hands over. */
class FootnoteTextTest {
    @Test fun `paragraphs stay and the link back goes`() {
        val html = """<p>The observatory was built in 1891 &amp; rebuilt twice.</p><p>See also the <em>Almanac</em>. <a href="one.xhtml#ref1">↩</a></p>"""
        assertEquals("The observatory was built in 1891 & rebuilt twice.\n\nSee also the Almanac.", FootnoteText.plain(html))
    }

    @Test fun `entities decode once and breaks become lines`() {
        assertEquals("a &lt; b\nc — d “e”", FootnoteText.plain("a &amp;lt; b<br/>c &mdash; d &#8220;e&#x201D;"))
    }

    @Test fun `the note's own number is kept, arrows written out are dropped`() {
        assertEquals("1. A lantern of the old kind.", FootnoteText.plain("""<a href="#r1">1.</a> A lantern of the old kind. &#8617;"""))
        assertEquals("Only words.", FootnoteText.plain("""  Only   words.  <a href="#back">&#x21a9;</a> """))
    }

    @Test fun `empty notes stay empty`() {
        assertEquals("", FootnoteText.plain("<p> </p>"))
    }
}

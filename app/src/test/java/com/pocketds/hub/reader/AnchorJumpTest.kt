package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Going to a place in another file of the book (#59): when the file is gone to first, and when it is laid out. */
class AnchorJumpTest {
    private val ready = AnchorJump.State(isTarget = true, loaded = true, fontsReady = true, width = 48_800)

    @Test fun `a locator names a place when it has a fragment, a way through, a selector or words`() {
        assertTrue(AnchorJump.namesPlace(listOf("part2"), null, otherLocations = false, text = false))
        assertTrue(AnchorJump.namesPlace(emptyList(), 0.4, otherLocations = false, text = false))
        assertTrue(AnchorJump.namesPlace(emptyList(), null, otherLocations = true, text = false))
        assertTrue(AnchorJump.namesPlace(emptyList(), null, otherLocations = false, text = true))
        // The top of a file, or a blank fragment, names no place: a file lands exactly in one step.
        assertFalse(AnchorJump.namesPlace(emptyList(), null, otherLocations = false, text = false))
        assertFalse(AnchorJump.namesPlace(emptyList(), 0.0, otherLocations = false, text = false))
        assertFalse(AnchorJump.namesPlace(listOf("", "  "), 0.0, otherLocations = false, text = false))
    }

    @Test fun `the file is gone to first only for a place in a file other than the one in front`() {
        assertTrue(AnchorJump.resourceFirst("EPUB/one.xhtml", "EPUB/two.xhtml", namesPlace = true))
        assertTrue("from a later file too", AnchorJump.resourceFirst("EPUB/three.xhtml", "EPUB/two.xhtml", namesPlace = true))
        // The same file is a jump within it; a whole file is one step; with no page in front there is nothing to wait for.
        assertFalse(AnchorJump.resourceFirst("EPUB/two.xhtml", "EPUB/two.xhtml", namesPlace = true))
        assertFalse(AnchorJump.resourceFirst("EPUB/one.xhtml", "EPUB/two.xhtml", namesPlace = false))
        assertFalse(AnchorJump.resourceFirst(null, "EPUB/two.xhtml", namesPlace = true))
    }

    @Test fun `the navigator's answer is read, whatever else it says is not`() {
        assertEquals(ready, AnchorJump.parse("\"1|1|1|48800\""))
        assertEquals(AnchorJump.State(false, true, false, 814), AnchorJump.parse("0|1|0|814"))
        assertEquals(AnchorJump.State(true, false, true, 0), AnchorJump.parse(" \"1|0|1|0\" "))
        listOf(null, "", "null", "\"\"", "1|1|1", "1|1|1|2|3", "2|1|1|10", "1|x|1|10", "1|1|1|wide", "{\"a\":1}").forEach { assertNull(it, AnchorJump.parse(it)) }
        // A width with a fraction (a scaled screen) is still a width.
        assertEquals(48_800, AnchorJump.parse("1|1|1|48800.4")!!.width)
    }

    @Test fun `the file is laid out when it is the one asked for, loaded, with its fonts, and no wider than a moment ago`() {
        assertTrue(AnchorJump.settled(ready, ready))
        // The first look has nothing to be the same as.
        assertFalse(AnchorJump.settled(null, ready))
        assertFalse(AnchorJump.settled(ready, null))
        // Still the old page, or not loaded, or fonts coming: not yet.
        assertFalse(AnchorJump.settled(ready, ready.copy(isTarget = false)))
        assertFalse(AnchorJump.settled(ready.copy(isTarget = false), ready))
        assertFalse(AnchorJump.settled(ready, ready.copy(loaded = false)))
        assertFalse(AnchorJump.settled(ready, ready.copy(fontsReady = false)))
        // The columns are still being laid out: the width moved between two looks.
        assertFalse(AnchorJump.settled(ready.copy(width = 20_000), ready))
        assertFalse(AnchorJump.settled(ready.copy(width = 0), ready.copy(width = 0)))
    }

    @Test fun `whether the element a place names is on the page is read from the answer, and asked about by its id`() {
        assertEquals(true, AnchorJump.landed("\"in\""))
        assertEquals(false, AnchorJump.landed("\"out\""))
        listOf(null, "", "\"none\"", "null", "in out").forEach { assertNull(it, AnchorJump.landed(it)) }
        val script = AnchorJump.landedScript("part'2")
        assertTrue(script, "document.getElementById('part\\'2')" in script && "getBoundingClientRect" in script && "window.innerWidth" in script)
        // Looked at until right twice running, within a few seconds at most: the page is hidden all that while.
        assertTrue(AnchorJump.RIGHT_LOOKS in 1..3 && AnchorJump.RETRIES >= AnchorJump.RIGHT_LOOKS)
        assertTrue(AnchorJump.RETRIES * AnchorJump.LOOK_AFTER_MS <= 1_500)
    }

    @Test fun `a place given by how far through the file is checked by the page's own report`() {
        assertEquals(true, AnchorJump.landedByProgress(0.6, 0.58))
        assertEquals(true, AnchorJump.landedByProgress(0.6, 0.31))
        // The page still at the top of the file: the jump came too soon.
        assertEquals(false, AnchorJump.landedByProgress(0.6, 0.0))
        assertEquals(false, AnchorJump.landedByProgress(0.6, 0.1))
        // Nothing to tell a place at the top from the top, and nothing said, are not "not landed".
        assertNull(AnchorJump.landedByProgress(0.0, 0.0))
        assertNull(AnchorJump.landedByProgress(0.04, 0.0))
        assertNull(AnchorJump.landedByProgress(null, 0.5))
        assertNull(AnchorJump.landedByProgress(0.6, null))
        assertNull(AnchorJump.landedByProgress(0.6, Double.NaN))
    }

    @Test fun `the script asks about the file by its path in the one spelling, quoted so a name cannot break out of it`() {
        val script = AnchorJump.script("EPUB/Text/Author - [Series 01] - O'Brien_split_010.htm")
        assertTrue(script, "var want = 'EPUB/Text/Author - [Series 01] - O\\'Brien_split_010.htm';" in script)
        assertTrue(script, "decodeURIComponent" in script && "normalize('NFC')" in script && "document.readyState" in script && "document.fonts" in script && "scrollWidth" in script)
        assertTrue(script, script.startsWith("(function () {") && script.endsWith("})()"))
        assertFalse(AnchorJump.script("a\n</script>").contains("</script>"))
    }
}

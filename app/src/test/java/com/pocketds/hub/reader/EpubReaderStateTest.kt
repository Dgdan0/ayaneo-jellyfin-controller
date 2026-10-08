package com.pocketds.hub.reader

import com.pocketds.hub.model.EpubPositionBody
import com.pocketds.hub.model.EpubPositionResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubReaderStateTest {
    @Test fun `one full page exits scrolling and spread and explicit layout replaces it`() {
        val single = EpubLayoutPolicy.selectOnePage(EpubReaderPreferences(scroll = true, columns = EpubColumns.TWO), true)
        assertFalse(single.scroll)
        assertEquals(EpubColumns.ONE, single.columns)
        assertEquals(1, EpubLayoutPolicy.columnCount(single, 1200, true))
        assertFalse(EpubLayoutPolicy.selectColumns(single, EpubColumns.TWO).onePagePerScreen)
        assertFalse(EpubLayoutPolicy.selectScroll(single, true).onePagePerScreen)
        val disabled = EpubLayoutPolicy.selectOnePage(single, false)
        assertFalse(disabled.onePagePerScreen)
        assertEquals(EpubColumns.ONE, disabled.columns)
    }
    @Test fun `centre tap reveals chrome and a page tap dismisses open chrome`() {
        assertTrue(EpubChromePolicy.handlesTap(.5f, controlsVisible = false))
        assertFalse(EpubChromePolicy.handlesTap(.1f, controlsVisible = false))
        assertFalse(EpubChromePolicy.handlesTap(.9f, controlsVisible = false))
        assertTrue(EpubChromePolicy.handlesTap(.1f, controlsVisible = true))
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `readium locator round trips optional text and location fields unchanged`() {
        val decoded = json.decodeFromString<EpubPositionResponse>(
            """{
                "workId":"rw_book","sourceItemId":"12","timestamp":1700000000000,
                "locator":{"href":"chapter-4.xhtml","type":"application/xhtml+xml","title":"Chapter 4",
                    "locations":{"fragments":["paragraph-3"],"progression":0.4,"totalProgression":0.32,"position":44},
                    "text":{"before":"red ","highlight":"rising","after":" dawn"},
                    "displayInfo":{"resourceScreenIndex":2}}
            }""".trimIndent()
        )

        assertEquals("chapter-4.xhtml", decoded.locator?.get("href")?.toString()?.trim('"'))
        val body = EpubPositionBody(requireNotNull(decoded.locator), decoded.timestamp)
        val encoded = json.parseToJsonElement(json.encodeToString(body)).jsonObject

        assertEquals(decoded.locator, encoded["locator"])
        assertEquals(1700000000000, body.timestamp)
    }

    @Test
    fun `layout uses two columns only for wide paginated books`() {
        val auto = EpubReaderPreferences(columns = EpubColumns.AUTO, scroll = false)

        assertEquals(2, EpubLayoutPolicy.columnCount(auto, viewportWidthDp = 980, publicationAllowsSpreads = true))
        assertEquals(1, EpubLayoutPolicy.columnCount(auto, viewportWidthDp = 680, publicationAllowsSpreads = true))
        assertEquals(1, EpubLayoutPolicy.columnCount(auto.copy(scroll = true), viewportWidthDp = 1200, publicationAllowsSpreads = true))
        assertEquals(1, EpubLayoutPolicy.columnCount(auto, viewportWidthDp = 1200, publicationAllowsSpreads = false))
        assertEquals(1, EpubLayoutPolicy.columnCount(auto.copy(columns = EpubColumns.ONE), viewportWidthDp = 1200, publicationAllowsSpreads = true))
        assertEquals(2, EpubLayoutPolicy.columnCount(auto.copy(columns = EpubColumns.TWO), viewportWidthDp = 800, publicationAllowsSpreads = true))
    }

    @Test fun `choosing two columns exits continuous mode and scrolling exits two columns`() {
        val scrolling = EpubReaderPreferences(scroll = true, columns = EpubColumns.ONE)
        val two = EpubLayoutPolicy.selectColumns(scrolling, EpubColumns.TWO)
        assertFalse(two.scroll)
        assertEquals(2, EpubLayoutPolicy.columnCount(two, 800, true))
        val continuous = EpubLayoutPolicy.selectScroll(two, true)
        assertTrue(continuous.scroll)
        assertEquals(EpubColumns.AUTO, continuous.columns)
    }

    @Test
    fun `appearance changes persist immediately and closing retains them`() {
        val initial = EpubReaderPreferences(theme = EpubTheme.SEPIA, fontScale = 1.0f)
        val state = EpubPreferenceState(initial)

        state.preview(initial.copy(theme = EpubTheme.DARK, fontScale = 1.2f))
        assertEquals(EpubTheme.DARK, state.visible.theme)
        assertTrue(state.commit())
        assertEquals(EpubTheme.DARK, state.saved.theme)
        assertEquals(1.2f, state.saved.fontScale)
        assertTrue(state.dirty)
        state.markPersisted()
        state.cancel()
        assertEquals(state.saved, state.visible)
        assertFalse(state.commit())
    }

    @Test fun `the size slider goes from 70 to 200 percent in tens, fourteen marks`() {
        val sizes = EpubLayoutPolicy.SIZES
        assertEquals(13, sizes.steps)
        assertEquals(14, (0..sizes.steps).map { sizes.at(it) }.toSet().size)
        assertEquals(0.7f, sizes.at(0), 0f)
        assertEquals(2f, sizes.at(sizes.steps), 0f)
        assertEquals(listOf("70%", "100%", "130%", "200%"), listOf(0.7f, 1f, 1.3f, 2f).map { EpubLayoutPolicy.sizeLabel(it) })
        // The default is one of the marks, and a step on the pad is one mark.
        assertEquals(1.3f, sizes.at(sizes.index(EpubReaderPreferences().fontScale)), 0f)
        assertEquals(1.4f, sizes.move(1.3f, 1), 0.001f)
        assertEquals(0.7f, sizes.move(0.7f, -1), 0f)
        assertEquals(2f, sizes.move(2f, 1), 0f)
        // The labels never show a float's tail.
        assertEquals("110%", EpubLayoutPolicy.sizeLabel(sizes.at(4)))
    }

    @Test fun `the spacing row says the line spacing and the margins it opens`() {
        assertEquals("1.5 · Balanced margins", EpubLayoutPolicy.spacingSummary(EpubReaderPreferences()))
        assertEquals("1.3 · Narrow margins", EpubLayoutPolicy.spacingSummary(EpubReaderPreferences(lineHeight = 1.3f, pageMargins = 0.5f)))
        assertEquals("1.8 · Wide margins", EpubLayoutPolicy.spacingSummary(EpubReaderPreferences(lineHeight = 1.8f, pageMargins = 1.7f)))
    }

    @Test fun `brightness is a slider in steps of five percent, never fully dark`() {
        val range = com.pocketds.hub.ui.ScreenComfort.BRIGHTNESS_RANGE
        assertEquals(com.pocketds.hub.ui.ScreenComfort.MIN_BRIGHTNESS, range.at(0), 0f)
        assertEquals(1f, range.at(range.steps), 0f)
        assertEquals(0.05f, range.step, 0f)
        assertEquals(0.95f, range.move(1f, -1), 0.001f)
    }

    /** A look a device that once changed it still has: the book's own style, ragged, tight, and everything else chosen. */
    private val oldLook = EpubReaderPreferences(
        theme = EpubTheme.DARK, fontFamily = "charis", fontScale = 1.4f, lineHeight = 1.1f, pageMargins = 1.7f,
        columns = EpubColumns.TWO, scroll = false, publisherStyles = true, textAlignment = "start", onePagePerScreen = false,
        hyphenation = false
    )

    @Test fun `reset text style brings back the reader's own typography and leaves the rest of the look`() {
        val reset = EpubLayoutPolicy.resetTextStyle(oldLook)
        val defaults = EpubReaderPreferences()
        // The text's style and its typeface are the defaults'; no literal here, so a changed default is followed.
        assertEquals(defaults.publisherStyles, reset.publisherStyles)
        assertEquals(defaults.textAlignment, reset.textAlignment)
        assertEquals(defaults.hyphenation, reset.hyphenation)
        assertEquals(defaults.lineHeight, reset.lineHeight, 0f)
        assertEquals(defaults.fontFamily, reset.fontFamily)
        // The size, theme, margins and columns are as they were.
        assertEquals(oldLook.copy(publisherStyles = defaults.publisherStyles, textAlignment = defaults.textAlignment,
            hyphenation = defaults.hyphenation, lineHeight = defaults.lineHeight, fontFamily = defaults.fontFamily), reset)
        assertEquals(1.4f, reset.fontScale, 0f)
        assertEquals(EpubTheme.DARK, reset.theme)
        assertEquals(1.7f, reset.pageMargins, 0f)
        assertEquals(EpubColumns.TWO, reset.columns)
    }

    @Test fun `reset text style is today's defaults, justified, hyphenated, one and a half`() {
        val reset = EpubLayoutPolicy.resetTextStyle(oldLook)
        assertFalse(reset.publisherStyles)
        assertEquals("justify", reset.textAlignment)
        assertTrue(reset.hyphenation)
        assertEquals(1.5f, reset.lineHeight, 0f)
        assertEquals("literata", reset.fontFamily)
        assertEquals("Literata, justified, hyphenated, 1.5 spacing", EpubLayoutPolicy.textStyleSummary())
    }

    @Test fun `reset text style leaves the layout modes alone and is the same twice`() {
        val scrolling = oldLook.copy(scroll = true, columns = EpubColumns.ONE)
        assertEquals(true, EpubLayoutPolicy.resetTextStyle(scrolling).scroll)
        val onePage = EpubLayoutPolicy.selectOnePage(oldLook, true)
        assertEquals(onePage.copy(publisherStyles = false, textAlignment = "justify", hyphenation = true, lineHeight = 1.5f, fontFamily = "literata"),
            EpubLayoutPolicy.resetTextStyle(onePage))
        val once = EpubLayoutPolicy.resetTextStyle(oldLook)
        assertEquals(once, EpubLayoutPolicy.resetTextStyle(once))
        // A device that never changed it has nothing to reset.
        assertEquals(EpubReaderPreferences(), EpubLayoutPolicy.resetTextStyle(EpubReaderPreferences()))
    }
}

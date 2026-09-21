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

    @Test
    fun `preference changes remain previews until done`() {
        val initial = EpubReaderPreferences(theme = EpubTheme.SEPIA, fontScale = 1.0f)
        val state = EpubPreferenceState(initial)

        state.preview(initial.copy(theme = EpubTheme.DARK, fontScale = 1.2f))
        assertEquals(EpubTheme.DARK, state.visible.theme)
        assertEquals(EpubTheme.SEPIA, state.saved.theme)
        assertFalse(state.dirty)

        state.cancel()
        assertEquals(initial, state.visible)
        state.preview(initial.copy(fontScale = 1.1f))
        assertTrue(state.commit())
        assertEquals(1.1f, state.saved.fontScale)
        assertTrue(state.dirty)
        assertFalse(state.commit())
    }

    @Test
    fun `reader chrome reserves its own viewport and disappears cleanly`() {
        assertEquals(EpubChromeInsets(topDp = 58, bottomDp = 58), EpubChromePolicy.insets(visible = true))
        assertEquals(EpubChromeInsets(topDp = 0, bottomDp = 0), EpubChromePolicy.insets(visible = false))
    }

    @Test
    fun `appearance adjustments are bounded and deterministic`() {
        val initial = EpubReaderPreferences(theme = EpubTheme.SEPIA, fontScale = 1f, pageMargins = 1f)

        assertEquals(EpubTheme.DARK, EpubPreferenceAdjuster.nextTheme(initial).theme)
        assertEquals(EpubColumns.ONE, EpubPreferenceAdjuster.nextColumns(initial).columns)
        assertEquals(1.1f, EpubPreferenceAdjuster.changeFontSize(initial, 0.1f).fontScale)
        assertEquals(0.7f, EpubPreferenceAdjuster.changeFontSize(initial.copy(fontScale = 0.7f), -0.1f).fontScale)
        assertEquals(2.0f, EpubPreferenceAdjuster.changeFontSize(initial.copy(fontScale = 2f), 0.1f).fontScale)
        assertEquals(0.8f, EpubPreferenceAdjuster.changeMargins(initial, -0.2f).pageMargins)
        assertEquals(1.4f, EpubPreferenceAdjuster.changeLineHeight(initial.copy(lineHeight = 1.3f), 0.1f).lineHeight)
    }
}

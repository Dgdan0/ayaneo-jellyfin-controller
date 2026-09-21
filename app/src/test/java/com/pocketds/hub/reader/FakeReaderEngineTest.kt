package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeReaderEngineTest {
    @Test
    fun `page movement clamps and reports a stable locator`() {
        val engine = FakeReaderEngine(
            profile = ReaderProfile.COMIC,
            publicationId = "comic",
            title = "Comic fixture",
            pageCount = 3
        )

        assertFalse(engine.previous())
        assertTrue(engine.next())
        assertEquals(1, engine.currentIndex)
        assertEquals("Page 2 of 3", engine.locator().label)
        assertTrue(engine.next())
        assertFalse(engine.next())
        assertEquals(1.0, engine.locator().progression, 0.0001)
    }

    @Test
    fun `preview returns a locator without changing the current page`() {
        val engine = FakeReaderEngine(ReaderProfile.BOOK, "book", "Book fixture", pageCount = 9)

        val preview = engine.preview(0.75)

        assertEquals(0, engine.currentIndex)
        assertEquals(6, preview.pageIndex)
        assertEquals("Page 7 of 9", preview.locator.label)
    }

    @Test
    fun `seeking commits the preview page`() {
        val engine = FakeReaderEngine(ReaderProfile.BOOK, "book", "Book fixture", pageCount = 9)
        val preview = engine.preview(0.75)

        engine.seek(preview.locator)

        assertEquals(6, engine.currentIndex)
        assertEquals(preview.locator, engine.locator())
    }

    @Test
    fun `read along render model carries a highlighted token and audio progress`() {
        val engine = FakeReaderEngine(
            ReaderProfile.READ_ALONG,
            "readalong",
            "Read-along fixture",
            pageCount = 5,
            startProgression = 0.5
        )

        val model = engine.renderModel()

        assertTrue(model.highlightedToken.isNotBlank())
        assertEquals(engine.locator().progression, model.audioProgress, 0.0001)
    }
}

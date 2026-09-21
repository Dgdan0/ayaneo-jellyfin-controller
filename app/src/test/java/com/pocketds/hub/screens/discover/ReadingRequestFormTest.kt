package com.pocketds.hub.screens.discover

import com.pocketds.hub.model.ReadingQualityProfile
import com.pocketds.hub.model.ReadingRequestMode
import com.pocketds.hub.model.ReadingRequestOptions
import com.pocketds.hub.state.FormModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingRequestFormTest {
    private val options = ReadingRequestOptions(
        key = "reading:abc",
        contentType = "ebook",
        title = "Red Rising",
        modes = listOf(
            ReadingRequestMode("single", "This book"),
            ReadingRequestMode("series", "Entire series", requiresTotalBooks = true)
        ),
        qualityProfiles = listOf(
            ReadingQualityProfile(3, "Any"),
            ReadingQualityProfile(7, "English EPUB", default = true)
        ),
        monitoring = listOf("all", "none")
    )

    @Test
    fun `single book starts safe and hides series count`() {
        val rows = ReadingRequestForm.rows(options)
        assertEquals(listOf("mode", "profile", "monitoring", "submit"), rows.map { it.id })
        val model = FormModel(rows)
        assertEquals(0, model.index)
        assertFalse(rows.any { it.id == "totalBooks" })
    }

    @Test
    fun `series mode exposes a bounded book count and preserves chosen profile`() {
        val rows = ReadingRequestForm.rows(options, modeIndex = 1, profileIndex = 1, totalBooks = 6)
        assertEquals(listOf("mode", "totalBooks", "profile", "monitoring", "submit"), rows.map { it.id })
        val model = FormModel(rows)
        assertEquals(5, model.selectedIndex("totalBooks"))
        assertEquals(1, model.selectedIndex("profile"))

        val body = ReadingRequestForm.body(options, model)
        assertEquals("series", body.mode)
        assertEquals(6, body.totalBooks)
        assertEquals(7, body.qualityProfileId)
        assertEquals("all", body.monitoring)
    }

    @Test
    fun `form cannot submit without a server quality profile`() {
        val empty = options.copy(qualityProfiles = emptyList())
        assertTrue(ReadingRequestForm.rows(empty).none { it.id == "submit" })
    }
}

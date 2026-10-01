package com.pocketds.hub.screens.home

import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.DiscoverRow
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowsTest {
    private fun row(id: String, items: Int = 1) = DiscoverRow(id = id, title = id, items = List(items) { SearchHit(jellyfinItemId = "$id$it") })

    @Test
    fun `rows follow the chosen order, hidden and empty rows drop, unknown rows go last`() {
        val rows = listOf(row("favourites"), row("latest"), row("continue"), row("nextup", 0), row("newthing"))
        assertEquals(listOf("continue", "latest", "favourites", "newthing"), HomeRows.ordered(rows).map { it.id })
        assertEquals(listOf("favourites", "continue", "newthing"),
            HomeRows.ordered(rows, listOf("favourites", "continue", "latest"), hidden = setOf("latest")).map { it.id })
    }

    @Test
    fun `a row the hub could not refresh keeps its place`() {
        val previous = listOf(row("continue"), row("nextup"), row("latest"))
        val merged = HomeRows.ordered(HomeRows.merge(listOf(row("continue"), row("latest")), previous))
        assertEquals(listOf("continue", "nextup", "latest"), merged.map { it.id })
    }

    @Test
    fun `only rows you are partway through use landscape cards`() {
        assertTrue(HomeRows.landscape("continue"))
        assertTrue(HomeRows.landscape("nextup"))
        assertFalse(HomeRows.landscape("latest"))
    }

    @Test
    fun `coming up shows each title once at its next release not yet on disk`() {
        fun episode(key: String, date: String, s: Int, e: Int, hasFile: Boolean = false) = CalendarItem(
            id = "$key$e", media = MediaRef(key = key, type = "series", title = key), date = date, at = "${date}T04:00:00Z",
            season = s, episode = e, episodeTitle = "Ep $e", overview = "o", hasFile = hasFile)
        val row = HomeRows.upcoming(listOf(
            episode("dark", "2026-10-09", 2, 7), episode("dark", "2026-10-02", 2, 6, hasFile = true),
            episode("lanterns", "2026-10-05", 1, 8), episode("dark", "2026-10-03", 2, 6)
        ), today = LocalDate.parse("2026-10-02"))
        assertEquals(listOf("dark", "lanterns"), row.items.map { it.media.title })
        assertEquals("Tomorrow · S2E6", row.items[0].subtitle)
        assertEquals("Mon · S1E8", row.items[1].subtitle)
        assertEquals("Ep 6 — o", row.items[0].overview)
    }

    @Test
    fun `days read as today, tomorrow, a weekday, then a date`() {
        val today = LocalDate.parse("2026-10-02")
        assertEquals("Today", HomeRows.dayLabel(today, today))
        assertEquals("Tomorrow", HomeRows.dayLabel(today.plusDays(1), today))
        assertEquals("Thu", HomeRows.dayLabel(today.plusDays(6), today))
        assertEquals("9 Oct", HomeRows.dayLabel(today.plusDays(7), today))
    }

    @Test
    fun `an older stored order gains new built-in rows and library rows are fetched only when shown`() {
        assertEquals(listOf("latest", "continue", "nextup", "favourites", "upcoming"), HomeRows.complete(listOf("latest", "continue")))
        val order = listOf("continue", HomeRows.libraryRowId("anime"), HomeRows.libraryRowId("docs"))
        assertEquals(listOf("anime"), HomeRows.wantedLibraries(order, hidden = setOf(HomeRows.libraryRowId("docs"))))
    }
}

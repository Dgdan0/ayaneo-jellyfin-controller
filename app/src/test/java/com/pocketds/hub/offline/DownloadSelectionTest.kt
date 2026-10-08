package com.pocketds.hub.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Select mode's ticks and the words the page says of them (#48). */
class DownloadSelectionTest {
    private fun ep(season: Int, number: Int, available: Boolean = true, size: Long = 100) =
        SeriesEpisode("s${season}e$number", "season$season", season, number, false, size, available)

    private val episodes = (1..4).map { ep(1, it) } + (1..3).map { ep(2, it) } + ep(2, 4, available = false)
    private val none = emptySet<String>()

    @Test fun `a tap ticks and a second tap unticks`() {
        val selection = DownloadSelection()
        assertTrue(selection.toggle(episodes[0], none))
        assertTrue(selection.isTicked("s1e1"))
        assertEquals("1 selected", selection.line())
        assertTrue(selection.toggle(episodes[0], none))
        assertTrue(selection.isEmpty)
        assertEquals("0 selected", selection.line())
    }

    @Test fun `an episode already here or coming, or with no file, cannot be ticked`() {
        val selection = DownloadSelection()
        assertFalse(selection.toggle(episodes[0], setOf("s1e1")))
        assertFalse(selection.toggle(episodes[7], none))
        assertTrue(selection.isEmpty)
    }

    @Test fun `Select season ticks the rest of the season, and again unticks it`() {
        val selection = DownloadSelection()
        val have = setOf("s1e2")
        assertEquals(3, selection.toggleSeason(episodes, "season1", have))
        assertEquals(listOf("s1e1", "s1e3", "s1e4"), selection.ids)
        // Partly ticked: it ticks the others, as the old screen's Y did.
        selection.toggle(episodes[0], have)
        assertEquals(3, selection.toggleSeason(episodes, "season1", have))
        assertEquals(0, selection.toggleSeason(episodes, "season1", have))
        assertTrue(selection.isEmpty)
    }

    @Test fun `ticks survive a change of season`() {
        val selection = DownloadSelection()
        selection.toggleSeason(episodes, "season1", none)
        selection.toggle(episodes[4], none)
        assertEquals(5, selection.count)
        assertEquals("4/4", selection.seasonLabel(episodes, "season1", none))
        assertEquals("1/3", selection.seasonLabel(episodes, "season2", none))
    }

    @Test fun `a season pill counts the ticked of those that can be, and says nothing when none can`() {
        val selection = DownloadSelection()
        selection.toggle(episodes[0], none)
        selection.toggle(episodes[1], none)
        assertEquals("2/4", selection.seasonLabel(episodes, "season1", none))
        // One of the season's episodes arrived by another way: it is no longer one to tick.
        assertEquals("2/3", selection.seasonLabel(episodes, "season1", setOf("s1e4")))
        assertNull(selection.seasonLabel(episodes, "season1", setOf("s1e1", "s1e2", "s1e3", "s1e4")))
    }

    @Test fun `ticks of episodes that have arrived meanwhile are dropped`() {
        val selection = DownloadSelection()
        selection.toggleSeason(episodes, "season1", none)
        selection.prune(setOf("s1e2", "s1e3"))
        assertEquals(listOf("s1e1", "s1e4"), selection.ids)
    }

    @Test fun `the bottom bar says how many and how much`() {
        val selection = DownloadSelection()
        val text = { bytes: Long -> "$bytes B" }
        assertEquals("Tick the episodes to download", selection.sizeLine(episodes, text))
        selection.toggle(episodes[0], none)
        assertEquals("1 episode · 100 B", selection.sizeLine(episodes, text))
        selection.toggle(episodes[1], none)
        assertEquals("2 episodes · 200 B", selection.sizeLine(episodes, text))
        selection.clear()
        assertTrue(selection.isEmpty)
    }
}

package com.pocketds.hub.offline

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.OfflineSelectionItem
import com.pocketds.hub.model.OfflineSelectionResponse
import com.pocketds.hub.model.OfflineSelectionSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The series page's ways to download, as the episodes each adds and their size (#48). */
class SeriesDownloadChoicesTest {
    /** Season 1: e1 to e4, e1 and e2 watched. Season 2: e1 to e3. Specials: one. Each is 100 MB but one has no file. */
    private val mb = 1_000_000L
    private fun ep(season: Int, number: Int, played: Boolean = false, available: Boolean = true, size: Long = 100 * mb) =
        SeriesEpisode("s${season}e$number", "season$season", season, number, played, size, available)

    private val series = listOf(
        ep(1, 1, played = true), ep(1, 2, played = true), ep(1, 3), ep(1, 4),
        ep(2, 1), ep(2, 2, available = false), ep(2, 3),
        ep(0, 1)
    )
    private val none = emptySet<String>()

    @Test fun `keep the next N is the next unwatched episodes from the one to play, the specials left out`() {
        val three = SeriesDownloadChoices.keepReady(series, "s1e3", 3, none)
        assertEquals(listOf("s1e3", "s1e4", "s2e1"), three.ids)
        assertEquals(300 * mb, three.bytes)
        // An episode with no file is passed over, not counted.
        assertEquals(listOf("s1e4", "s2e1", "s2e3"), SeriesDownloadChoices.keepReady(series, "s1e4", 3, none).ids)
        // The specials come only when they are the play target.
        assertEquals(listOf("s0e1"), SeriesDownloadChoices.keepReady(series, "s0e1", 3, none).ids)
    }

    @Test fun `episodes the device has count towards N and are not fetched again`() {
        val have = setOf("s1e3")
        val three = SeriesDownloadChoices.keepReady(series, "s1e3", 3, have)
        assertEquals("s1e3 is one of the three", listOf("s1e4", "s2e1"), three.ids)
        assertEquals(200 * mb, three.bytes)
    }

    @Test fun `with no play target Keep ready starts at the first unwatched episode`() {
        assertEquals(listOf("s1e3", "s1e4"), SeriesDownloadChoices.keepReady(series, null, 2, none).ids)
        assertEquals(listOf("s1e3"), SeriesDownloadChoices.keepReady(series, "unknown", 1, none).ids)
        assertTrue(SeriesDownloadChoices.keepReady(series.map { it.copy(played = true) }, null, 3, none).isEmpty)
    }

    @Test fun `the rest of the season is its unwatched episodes that are not here`() {
        val rest = SeriesDownloadChoices.restOfSeason(series, "season1", none)
        assertEquals(listOf("s1e3", "s1e4"), rest.ids)
        assertEquals(listOf("s1e4"), SeriesDownloadChoices.restOfSeason(series, "season1", setOf("s1e3")).ids)
        // The file the server lacks is not offered.
        assertEquals(listOf("s2e1", "s2e3"), SeriesDownloadChoices.restOfSeason(series, "season2", none).ids)
    }

    @Test fun `everything unwatched and the whole series differ by what has been watched`() {
        assertEquals(listOf("s1e3", "s1e4", "s2e1", "s2e3", "s0e1"), SeriesDownloadChoices.everythingUnwatched(series, none).ids)
        val whole = SeriesDownloadChoices.wholeSeries(series, none)
        assertEquals(listOf("s1e1", "s1e2", "s1e3", "s1e4", "s2e1", "s2e3", "s0e1"), whole.ids)
        assertEquals(700 * mb, whole.bytes)
        assertEquals(listOf("s1e1", "s1e2", "s1e4", "s2e1", "s2e3", "s0e1"),
            SeriesDownloadChoices.wholeSeries(series, setOf("s1e3")).ids)
    }

    @Test fun `a season's button says what is left and its size, or that it is all here`() {
        val season = SeriesDownloadChoices.season(series, "season2", none)
        assertEquals(listOf("s2e1", "s2e3"), season.ids)
        assertEquals("Season 2 · 200 MB", SeriesDownloadChoices.seasonButton("Season 2", season, true, "Pocket") { "${it / mb} MB" })
        val done = SeriesDownloadChoices.season(series, "season2", setOf("s2e1", "s2e3"))
        assertTrue(done.isEmpty)
        assertEquals("Season 2 on this Pocket", SeriesDownloadChoices.seasonButton("Season 2", done, true, "Pocket") { "x" })
        assertEquals("Season 3 unavailable", SeriesDownloadChoices.seasonButton("Season 3", DownloadChoice.NONE, false, "Pocket") { "x" })
    }

    @Test fun `the order is season by season with the specials last, whatever order the hub sent`() {
        val shuffled = listOf(ep(0, 1), ep(2, 1), ep(1, 2), ep(1, 1))
        assertEquals(listOf("s1e1", "s1e2", "s2e1", "s0e1"), SeriesDownloadChoices.ordered(shuffled).map { it.id })
    }

    @Test fun `a choice reads as its count and its size`() {
        val text = { bytes: Long -> "${bytes / mb} MB" }
        assertEquals("3 episodes · 300 MB", SeriesDownloadChoices.detail(DownloadChoice(listOf("a", "b", "c"), 300 * mb), text))
        assertEquals("1 episode · 100 MB", SeriesDownloadChoices.detail(DownloadChoice(listOf("a"), 100 * mb), text))
        assertEquals("Nothing left to get", SeriesDownloadChoices.detail(DownloadChoice.NONE, text))
    }

    @Test fun `the target season is the one Play starts in, else the first that is not Specials`() {
        assertEquals("season1", SeriesDownloadChoices.targetSeason(series, "s1e3")?.seasonId)
        assertEquals("season1", SeriesDownloadChoices.targetSeason(series, null)?.seasonId)
        assertEquals("season0", SeriesDownloadChoices.targetSeason(series, "s0e1")?.seasonId)
        assertNull(SeriesDownloadChoices.targetSeason(emptyList(), null))
    }

    @Test fun `the hub's selection becomes episodes with their seasons, sizes and watched state`() {
        val selection = OfflineSelectionResponse(seasons = listOf(
            OfflineSelectionSeason(LibraryItem(id = "sA", type = "season", seasonNumber = 1), listOf(
                OfflineSelectionItem(LibraryItem(id = "a1", type = "episode", seasonNumber = 1, indexNumber = 1, played = true), estimatedSizeBytes = 5, available = true),
                OfflineSelectionItem(LibraryItem(id = "a2", type = "episode", seasonNumber = 0, indexNumber = 2), estimatedSizeBytes = 7, available = false))),
            OfflineSelectionSeason(LibraryItem(id = "sB", type = "season", seasonNumber = 0), listOf(
                OfflineSelectionItem(LibraryItem(id = "b1", type = "episode", indexNumber = 1), estimatedSizeBytes = 9, available = true)))
        ))
        val episodes = SeriesDownloadChoices.from(selection)
        assertEquals(listOf("a1", "a2", "b1"), episodes.map { it.id })
        assertEquals(listOf("sA", "sA", "sB"), episodes.map { it.seasonId })
        // An episode that does not say its season has the season's.
        assertEquals(listOf(1, 1, 0), episodes.map { it.season })
        assertTrue(episodes[0].played)
        assertFalse(episodes[1].available)
        assertEquals(9L, episodes[2].sizeBytes)
    }
}

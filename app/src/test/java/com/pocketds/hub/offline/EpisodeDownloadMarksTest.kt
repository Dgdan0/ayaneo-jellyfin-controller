package com.pocketds.hub.offline

import com.pocketds.hub.offline.EpisodeDownloadMarks.Mark
import com.pocketds.hub.offline.EpisodeDownloadMarks.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An episode card's download corner and its pad menu (#48). */
class EpisodeDownloadMarksTest {
    @Test fun `the corner is an arrow, a ring, waiting or a tick by what the download is`() {
        assertEquals(Mark.ARROW, EpisodeDownloadMarks.badge(null, 0f).mark)
        assertEquals(Mark.ARROW, EpisodeDownloadMarks.badge(OfflineState.FAILED, 0.3f).mark)
        assertEquals(EpisodeDownloadMarks.Badge(Mark.RING, 0.42f), EpisodeDownloadMarks.badge(OfflineState.DOWNLOADING, 0.42f))
        listOf(OfflineState.QUEUED, OfflineState.WAITING, OfflineState.PAUSED).forEach {
            assertEquals("$it", Mark.WAITING, EpisodeDownloadMarks.badge(it, 0f).mark)
        }
        assertEquals(Mark.TICK, EpisodeDownloadMarks.badge(OfflineState.COMPLETE, 1f).mark)
    }

    @Test fun `the ring is kept inside zero and one`() {
        assertEquals(1f, EpisodeDownloadMarks.badge(OfflineState.DOWNLOADING, 3f).progress, 0f)
        assertEquals(0f, EpisodeDownloadMarks.badge(OfflineState.DOWNLOADING, -1f).progress, 0f)
    }

    @Test fun `a tap on the corner downloads at once, stops what is coming, retries a failure and opens the menu on a tick`() {
        assertEquals(Tap.DOWNLOAD, EpisodeDownloadMarks.tap(null))
        assertEquals(Tap.RETRY, EpisodeDownloadMarks.tap(OfflineState.FAILED))
        assertEquals(Tap.STOP, EpisodeDownloadMarks.tap(OfflineState.DOWNLOADING))
        assertEquals(Tap.STOP, EpisodeDownloadMarks.tap(OfflineState.QUEUED))
        assertEquals(Tap.STOP, EpisodeDownloadMarks.tap(OfflineState.PAUSED))
        assertEquals(Tap.MENU, EpisodeDownloadMarks.tap(OfflineState.COMPLETE))
    }

    @Test fun `the corner says what it is for a screen reader`() {
        assertEquals("Download", EpisodeDownloadMarks.description(null, 0f))
        assertEquals("On this device", EpisodeDownloadMarks.description(OfflineState.COMPLETE, 1f))
        assertEquals("Downloading 42 percent, tap to stop", EpisodeDownloadMarks.description(OfflineState.DOWNLOADING, 0.42f))
        assertTrue(EpisodeDownloadMarks.description(OfflineState.QUEUED, 0f).startsWith("Waiting"))
        assertTrue(EpisodeDownloadMarks.description(OfflineState.FAILED, 0f).contains("try again"))
    }

    @Test fun `the card menu is Play, then Download or Stop or Remove by what the card holds, Select episodes and Find releases`() {
        fun ids(state: OfflineState?, resumes: Boolean = false, available: Boolean = true, releases: Boolean = true) =
            EpisodeDownloadMarks.menu(state, resumes, available, releases).map { it.id }
        assertEquals(listOf("play", "download", "select", "releases"), ids(null))
        assertEquals(listOf("play", "stop", "select", "releases"), ids(OfflineState.DOWNLOADING))
        assertEquals(listOf("play", "stop", "select", "releases"), ids(OfflineState.QUEUED))
        assertEquals(listOf("play", "remove", "select", "releases"), ids(OfflineState.COMPLETE))
        assertEquals(listOf("play", "download", "select"), ids(OfflineState.FAILED, releases = false))
        // No file on the server: nothing to download, but the rest stays.
        assertEquals(listOf("play", "select"), ids(null, available = false, releases = false))
    }

    @Test fun `the first row is Resume when the episode has a place, and the words are plain`() {
        val rows = EpisodeDownloadMarks.menu(OfflineState.COMPLETE, true, true, false)
        assertEquals(listOf("Resume", "Remove download", "Select episodes"), rows.map { it.label })
        assertEquals("Play", EpisodeDownloadMarks.menu(null, false, true, false).first().label)
        assertEquals("Stop download", EpisodeDownloadMarks.menu(OfflineState.DOWNLOADING, false, true, false)[1].label)
        assertEquals("Try the download again", EpisodeDownloadMarks.menu(OfflineState.FAILED, false, true, false)[1].label)
    }
}

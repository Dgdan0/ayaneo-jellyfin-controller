package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackPrepareResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class PlayerLabelsTest {
    private lateinit var original: Locale

    @Before
    fun germanDevice() {
        // A comma-decimal locale, the case Locale.US guards against.
        original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun `decimals do not follow the device locale`() {
        assertEquals("1.5 seconds earlier", PlayerLabels.subtitleOffset(-1_500))
        assertEquals("0.3 seconds later", PlayerLabels.subtitleOffset(300))
        assertEquals("No offset", PlayerLabels.subtitleOffset(0))
        val plan = PlaybackPrepareResponse(playMethod = "DirectPlay", width = 1920, height = 1080,
            videoCodec = "hevc", frameRate = 23.976)
        assertEquals("DirectPlay · 1920×1080 · HEVC · 23.98 fps", PlayerLabels.diagnostic(plan))
    }

    @Test
    fun `seek deltas carry their sign`() {
        assertEquals("+0:10", PlayerLabels.signedTime(10_000))
        assertEquals("−0:30", PlayerLabels.signedTime(-30_000))
    }

    @Test
    fun `normal speed reads as a word`() {
        assertEquals("Normal", PlayerLabels.speed(1f))
        assertEquals("1.5×", PlayerLabels.speed(1.5f))
    }

    @Test
    fun theTitleBarNamesTheSeriesAndUnderItTheEpisode() {
        val episode = com.pocketds.hub.model.PlaybackItem(title = "Somewhere Not Here", seriesTitle = "Vinland Saga", seasonNumber = 1, episodeNumber = 1)
        assertEquals("Vinland Saga", PlayerLabels.title(episode))
        assertEquals("S1E1 · Somewhere Not Here", PlayerLabels.subtitle(episode, offline = false))
        assertEquals("S1E1 · Somewhere Not Here · Offline", PlayerLabels.subtitle(episode, offline = true))
        val film = com.pocketds.hub.model.PlaybackItem(title = "Gran Torino")
        assertEquals("Gran Torino", PlayerLabels.title(film))
        assertEquals("", PlayerLabels.subtitle(film, offline = false))
    }

    @Test
    fun underTheTimelineThePositionWithItsChapterAndTheTimeLeft() {
        assertEquals("5:34 · Part A", PlayerLabels.positionLine(334_000, "Part A"))
        // "Chapter 2" says nothing the marks on the timeline do not.
        assertEquals("5:34", PlayerLabels.positionLine(334_000, "Chapter 2"))
        assertEquals("5:34", PlayerLabels.positionLine(334_000, null))
        assertEquals("−22:53", PlayerLabels.remainingLine(334_000, 1_707_000))
        assertEquals("", PlayerLabels.remainingLine(0, 0))
    }

    @Test
    fun thisVideosQualityRowSaysTheChoiceAndTheHeightDelivered() {
        assertEquals("Original · 1080p", PlayerLabels.qualityValue("Original", 1080, offline = false))
        assertEquals("10 Mbps", PlayerLabels.qualityValue("10 Mbps", 0, offline = false))
        assertEquals("Original · downloaded", PlayerLabels.qualityValue("Original", 1080, offline = true))
    }

    @Test
    fun aChaptersLineSaysWhereItStartsHowLongAndWhatItIs() {
        assertEquals("3:58 · 2 min · Intro", PlayerLabels.chapterDetail(238_000, 331_000, "Intro"))
        assertEquals("0:00 · 45 s", PlayerLabels.chapterDetail(0, 45_000, null))
        // The last chapter has no end to measure to.
        assertEquals("26:56", PlayerLabels.chapterDetail(1_616_000, 0, null))
        assertEquals("Credits", PlayerLabels.segmentKind("Outro"))
        assertEquals(null, PlayerLabels.segmentKind("Unknown"))
    }
}

package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAlignedAudio
import com.pocketds.hub.model.ReadingAudioAlignment
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read along streamed (#19): which edition to open, and where each stretch of narration is heard. */
class ReadAlongStreamTest {
    private val tracks = listOf(
        ReadingAudioTrack(0, "t_aaaaaaaaaaaa", "Track 01", 4_610_652, 36_942_522, "audio/mpeg", "\"e1\""),
        ReadingAudioTrack(1, "t_bbbbbbbbbbbb", "Track 02", 7_200_000, 57_600_000, "audio/mpeg", "\"e2\"")
    )
    // Dark Matter's shape: the edition's files named in its own order, the second track cut in two chunks.
    private val manifest = ReadingAudioManifest(revision = "05c8b6c63e2b", aligned = true, tracks = tracks,
        alignment = ReadingAudioAlignment(listOf(
            ReadingAlignedAudio("OEBPS/Audio/00001-00001.mp3", 1, 0),
            ReadingAlignedAudio("OEBPS/Audio/00001-00002.mp3", 1, 3_600_000),
            ReadingAlignedAudio("OEBPS/Audio/00002-00001.mp3", 0, 0))))
    private fun url(index: Int) = "https://hub/audio/tracks/$index?rev=05c8b6c63e2b"
    private fun stretch(href: String) = ReadAlongTrack(href, listOf(ReadAlongSegment("OEBPS/text/one.xhtml", "s1", href, 2_000, 5_000)))

    @Test fun `each stretch is heard from the track its file was mapped to, where that file begins`() {
        val timeline = ReadAlongTimeline(listOf(stretch("OEBPS/Audio/00001-00001.mp3"), stretch("OEBPS/Audio/00001-00002.mp3"),
            stretch("/OEBPS/Audio/00002-00001.mp3")))
        val sources = ReadAlongStream.sources(timeline, manifest, "3726292328809367", ::url)
        assertEquals(listOf(url(1), url(1), url(0)), sources.map { it.uri })
        // The second chunk begins an hour into the track: its sentences are clipped from there.
        assertEquals(listOf(0L, 3_600_000L, 0L), sources.map { it.startMs })
        // The bytes are the audiobook's own, cached under the same key as when it is listened to.
        assertEquals(AudiobookStream.cacheKey("3726292328809367", tracks[1]), sources[0].cacheKey)
    }

    private fun sentence(href: String, id: String, begin: Long, end: Long) = ReadAlongSegment("OEBPS/text/one.xhtml", id, href, begin, end)

    /**
     * A sentence past its file's audio skips itself, not the edition: a file's audio is its window of the track,
     * up to the next file mapped there or the track's end. One that runs past the window ends there, so the voice
     * never reads on into the next file's words (Apple's `fitted`, #52).
     */
    @Test fun `a sentence past its file's audio is skipped and one running past it ends there`() {
        val first = "OEBPS/Audio/00001-00001.mp3"
        val second = "OEBPS/Audio/00001-00002.mp3"
        val third = "/OEBPS/Audio/00002-00001.mp3"
        val timeline = ReadAlongTimeline(listOf(
            // The first file's window is the first hour of track 1, up to where the second file begins.
            ReadAlongTrack(first, listOf(sentence(first, "a", 2_000, 5_000), sentence(first, "b", 3_599_000, 3_601_000),
                sentence(first, "c", 3_600_000, 3_602_000), sentence(first, "d", 3_700_000, 3_701_000))),
            // The second file's window is the rest of track 1: an hour.
            ReadAlongTrack(second, listOf(sentence(second, "e", 1_000, 2_000))),
            // Track 0 is 4,610,652 ms long: nothing of this stretch is in it.
            ReadAlongTrack(third, listOf(sentence(third, "f", 4_610_652, 4_612_000), sentence(third, "g", 4_700_000, 4_701_000)))
        ))
        val fitted = ReadAlongStream.fitted(timeline, manifest)!!
        assertEquals("a stretch left with no sentence goes", listOf(first, second), fitted.tracks.map { it.audioHref })
        assertEquals(listOf("a", "b"), fitted.tracks[0].segments.map { it.fragment })
        assertEquals("it ends where the next file's audio begins", 3_600_000L, fitted.tracks[0].segments[1].endMs)
        assertEquals(timeline.tracks[1], fitted.tracks[1])
        // The fitted timeline still maps, each stretch onto its own file.
        val sources = ReadAlongStream.sources(fitted, manifest, "3726292328809367", ::url)
        assertEquals(listOf(0L, 3_600_000L), sources.map { it.startMs })
    }

    @Test fun `a file whose audio cannot be measured is left as it is`() {
        // Unmapped (`sources` refuses it), or on a track of unknown length with no file after it.
        val unmapped = ReadAlongTimeline(listOf(ReadAlongTrack("OEBPS/Audio/00009-00001.mp3",
            listOf(sentence("OEBPS/Audio/00009-00001.mp3", "x", 9_000_000, 9_001_000)))))
        assertEquals(unmapped, ReadAlongStream.fitted(unmapped, manifest))
        val unknown = manifest.copy(tracks = listOf(tracks[0].copy(durationMs = 0), tracks[1]))
        val third = "OEBPS/Audio/00002-00001.mp3"
        val late = ReadAlongTimeline(listOf(ReadAlongTrack(third, listOf(sentence(third, "y", 9_000_000, 9_001_000)))))
        assertEquals(late, ReadAlongStream.fitted(late, unknown))
        // A file mapped to a track the manifest does not have is as unmeasurable.
        val nowhere = manifest.copy(alignment = ReadingAudioAlignment(listOf(ReadingAlignedAudio(third, 7, 0))))
        assertEquals(late, ReadAlongStream.fitted(late, nowhere))
    }

    @Test fun `an edition with nothing left to play has no narration`() {
        val third = "OEBPS/Audio/00002-00001.mp3"
        val past = ReadAlongTimeline(listOf(ReadAlongTrack(third, listOf(sentence(third, "z", 5_000_000, 5_001_000)))))
        assertNull(ReadAlongStream.fitted(past, manifest))
    }

    @Test fun `a file the hub did not map is no narration at all, never another file's`() {
        val timeline = ReadAlongTimeline(listOf(stretch("OEBPS/Audio/00009-00001.mp3")))
        assertTrue(runCatching { ReadAlongStream.sources(timeline, manifest, "3726292328809367", ::url) }.isFailure)
        val nowhere = manifest.copy(alignment = ReadingAudioAlignment(listOf(ReadingAlignedAudio("OEBPS/Audio/00009-00001.mp3", 7, 0))))
        assertTrue(runCatching { ReadAlongStream.sources(timeline, nowhere, "3726292328809367", ::url) }.isFailure)
    }

    @Test fun `the stream when the hub mapped the audio, the whole edition when it cannot, what is here when it cannot be asked`() {
        assertEquals(NarrationPlan.Stream(manifest), ReadAlongStream.plan(HubResult.Ok(manifest)))
        // An edition the hub could not map (lengths that do not match, Mistborn's while it was building).
        assertEquals(NarrationPlan.Whole, ReadAlongStream.plan(HubResult.Ok(manifest.copy(aligned = false, alignment = null, alignmentReason = "lengths_do_not_match"))))
        assertEquals(NarrationPlan.Whole, ReadAlongStream.plan(HubResult.Ok(manifest.copy(alignment = ReadingAudioAlignment(emptyList())))))
        assertEquals(NarrationPlan.Whole, ReadAlongStream.plan(HubResult.Failed(FailureKind.BAD_RESPONSE, code = "audio_not_streamable", reason = "unmapped_root")))
        assertEquals(NarrationPlan.Whole, ReadAlongStream.plan(HubResult.Failed(FailureKind.NOT_FOUND)))
        val offline = HubResult.Failed(FailureKind.NO_NETWORK)
        assertEquals(NarrationPlan.Unreachable(offline), ReadAlongStream.plan(offline))
    }
}

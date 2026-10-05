package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAlignedAudio
import com.pocketds.hub.model.ReadingAudioAlignment
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult
import org.junit.Assert.assertEquals
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

package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioChapter
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An audiobook's manifest as the player's parts, and its chapters as the Parts sheet (#19, A3). */
class AudiobookStreamTest {
    private val manifest = ReadingAudioManifest(
        workId = "rw_1", sourceItemId = "3726292328809367", revision = "05c8b6c63e2b", totalMs = 1_800_000,
        tracks = listOf(
            ReadingAudioTrack(0, "t_aaaaaaaaaaaa", "Track 01", 600_000, 4_800_000, "audio/mpeg", "\"e1\""),
            ReadingAudioTrack(1, "t_bbbbbbbbbbbb", "", 1_200_000, 9_600_000, "audio/mpeg", "")
        )
    )
    private fun url(index: Int) = "https://hub/v1/reading/works/rw_1/publications/3726292328809367/audio/tracks/$index?rev=05c8b6c63e2b"

    @Test fun `each track is a part with its URL, length, size and cache key, in the manifest's order`() {
        val parts = AudiobookStream.parts(manifest, "3726292328809367", ::url)
        assertEquals(listOf(url(0), url(1)), parts.map { it.uri })
        assertEquals(listOf("Track 01", "Track 2"), parts.map { it.title })
        assertEquals(listOf(600_000L, 1_200_000L), parts.map { it.durationMs })
        assertEquals(listOf(4_800_000L, 9_600_000L), parts.map { it.bytes })
        assertEquals(listOf("t_aaaaaaaaaaaa", "t_bbbbbbbbbbbb"), parts.map { it.trackId })
        assertTrue(parts.all { it.streamed && it.file == null })
        // The bytes are kept by the file (id and validator), not by the URL's revision.
        assertEquals("reading-audio:3726292328809367:t_aaaaaaaaaaaa:e1", parts[0].cacheKey)
        assertEquals("reading-audio:3726292328809367:t_bbbbbbbbbbbb:b9600000", parts[1].cacheKey)
        val revised = AudiobookStream.parts(manifest.copy(revision = "0123456789ab"), "3726292328809367") { "$it" }
        assertEquals(parts.map { it.cacheKey }, revised.map { it.cacheKey })
        assertTrue(parts.all { it.cacheKey.startsWith(AudiobookStream.cachePrefix("3726292328809367")) })
    }

    @Test fun `a manifest plays only with a revision and tracks that have ids`() {
        assertTrue(AudiobookStream.playable(manifest))
        assertFalse(AudiobookStream.playable(manifest.copy(revision = "")))
        assertFalse(AudiobookStream.playable(manifest.copy(tracks = emptyList())))
        assertFalse(AudiobookStream.playable(manifest.copy(tracks = listOf(manifest.tracks[0].copy(id = "")))))
    }

    @Test fun `the whole book is downloaded only when the hub cannot stream it`() {
        assertTrue(AudiobookStream.downloadsWhole(HubResult.Failed(FailureKind.BAD_RESPONSE, code = "audio_not_streamable", reason = "unmapped_root")))
        // A hub from before the routes.
        assertTrue(AudiobookStream.downloadsWhole(HubResult.Failed(FailureKind.NOT_FOUND)))
        // Offline, a busy hub or a refused place are not a reason to fetch gigabytes.
        assertFalse(AudiobookStream.downloadsWhole(HubResult.Failed(FailureKind.NO_NETWORK)))
        assertFalse(AudiobookStream.downloadsWhole(HubResult.Failed(FailureKind.UPSTREAM_DOWN)))
        assertFalse(AudiobookStream.downloadsWhole(HubResult.Failed(FailureKind.BAD_RESPONSE, code = "reading_position_conflict")))
    }

    // ------------------------------------------------------------------ the Parts sheet

    private val parts = AudiobookStream.parts(manifest, "3726292328809367", ::url)
    private val lengths = listOf<Long?>(600_000, 1_200_000)

    @Test fun `without chapters the sheet lists the parts`() {
        val entries = AudiobookContents.entries(parts, lengths, emptyList())
        assertEquals(listOf("Track 01" to 0, "Track 2" to 1), entries.map { it.title to it.part })
        assertEquals(listOf(600_000L, 1_200_000L), entries.map { it.durationMs })
    }

    @Test fun `chapters inside a track take its place, its opening kept`() {
        val chapters = listOf(
            ReadingAudioChapter("The ridge", 300_000, 1), ReadingAudioChapter("The pines", 60_000, 1),
            ReadingAudioChapter("", 900_000, 1), ReadingAudioChapter("Past the end", 5_000_000, 1)
        )
        val entries = AudiobookContents.entries(parts, lengths, chapters)
        assertEquals(listOf("Track 01", "Track 2", "The pines", "The ridge", "Chapter 3"), entries.map { it.title })
        assertEquals(listOf(0L, 0L, 60_000L, 300_000L, 900_000L), entries.map { it.startMs })
        assertEquals(listOf(600_000L, 60_000L, 240_000L, 600_000L, 300_000L), entries.map { it.durationMs })
        // A first mark a moment in is the track's start: no opening entry of half a second.
        val early = AudiobookContents.entries(parts, lengths, listOf(ReadingAudioChapter("One", 400, 0), ReadingAudioChapter("Two", 200_000, 0)))
        assertEquals(listOf("One" to 0L, "Two" to 200_000L, "Track 2" to 0L), early.map { it.title to it.startMs })
    }

    @Test fun `the entry playing and the steps through them`() {
        val entries = AudiobookContents.entries(parts, lengths, listOf(ReadingAudioChapter("A", 0, 1), ReadingAudioChapter("B", 300_000, 1)))
        // Track 01, then A and B inside track 2.
        assertEquals(0, AudiobookContents.current(entries, 0, 5_000))
        assertEquals(1, AudiobookContents.current(entries, 1, 10_000))
        assertEquals(2, AudiobookContents.current(entries, 1, 400_000))
        // Forward: the next entry, nothing past the last.
        assertEquals("B", AudiobookContents.step(entries, 1, 10_000, 1)?.title)
        assertNull(AudiobookContents.step(entries, 1, 400_000, 1))
        // Back: the entry's own start once three seconds in, else the one before.
        assertEquals("B", AudiobookContents.step(entries, 1, 400_000, -1)?.title)
        assertEquals("A", AudiobookContents.step(entries, 1, 301_000, -1)?.title)
        assertEquals("Track 01", AudiobookContents.step(entries, 1, 1_000, -1)?.title)
        assertEquals("Track 01", AudiobookContents.step(entries, 0, 1_000, -1)?.title)
        assertNull(AudiobookContents.step(emptyList(), 0, 0, 1))
    }
}

package com.pocketds.hub.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The hub's audiobook answers (#19) as the app reads them, with HubClient's own decoder settings. */
class ReadingAudioManifestTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test fun `the manifest of AUDIO_PLAN section 4 reads whole`() {
        val manifest = json.decodeFromString<ReadingAudioManifest>("""
            { "workId": "rw_1560", "sourceItemId": "3726292328809367", "revision": "9f2c1a7e4b10",
              "narrator": "Jon Lindstrom", "totalMs": 36538680, "aligned": true,
              "tracks": [ { "index": 0, "id": "t_5d41402abc4b", "title": "Track 01", "durationMs": 4610652,
                            "bytes": 36942522, "mime": "audio/mpeg", "etag": "\"2a9f1c\"" } ],
              "chapters": [ { "title": "Part One", "startMs": 0, "track": 0 } ],
              "alignment": { "audio": [ { "href": "Audio/00001-00001.mp3", "track": 0, "startMs": 0 } ] },
              "cache": { "hit": true, "ageSeconds": 4 }, "somethingNew": [1, 2] }
        """)
        assertEquals("9f2c1a7e4b10", manifest.revision)
        assertEquals(36538680L, manifest.totalMs)
        assertTrue(manifest.aligned)
        val track = manifest.tracks.single()
        assertEquals(ReadingAudioTrack(0, "t_5d41402abc4b", "Track 01", 4610652, 36942522, "audio/mpeg", "\"2a9f1c\""), track)
        assertEquals(ReadingAudioChapter("Part One", 0, 0), manifest.chapters.single())
        assertEquals(ReadingAlignedAudio("Audio/00001-00001.mp3", 0, 0), manifest.alignment!!.audio.single())
        assertTrue(manifest.cache.hit)
    }

    @Test fun `a chapter says where it comes from, and an older hub's says nothing (#31)`() {
        val manifest = json.decodeFromString<ReadingAudioManifest>("""
            { "revision": "9f2c1a7e4b10", "aligned": true, "tracks": [],
              "chapters": [ { "title": "Prologue", "startMs": 66990, "track": 0, "source": "book" },
                            { "title": "Chapter 1", "startMs": 2219720, "track": 0, "source": "book" },
                            { "title": "Part Two", "startMs": 5, "track": 1, "source": "marks" },
                            { "title": "Older hub", "startMs": 9, "track": 1 } ] }
        """)
        assertEquals(listOf("book", "book", "marks", ""), manifest.chapters.map { it.source })
        assertEquals(ReadingAudioChapter("Prologue", 66_990, 0, "book"), manifest.chapters[0])
        assertEquals(listOf(true, true, false, false), manifest.chapters.map { it.fromBook })
        // A source the hub may name later is read as it is, and is not the book's.
        val later = json.decodeFromString<ReadingAudioChapter>("""{"title":"x","startMs":1,"track":2,"source":"something"}""")
        assertEquals("something", later.source)
        assertFalse(later.fromBook)
        // The three fields an older app reads are still there, and a chapter written without one decodes.
        assertEquals(ReadingAudioChapter("Bare"), json.decodeFromString<ReadingAudioChapter>("""{"title":"Bare"}"""))
    }

    @Test fun `a book without a read-along edition has no alignment and may say why`() {
        val plain = json.decodeFromString<ReadingAudioManifest>("""{"revision":"05c8b6c63e2b","aligned":false,"tracks":[],"chapters":[]}""")
        assertFalse(plain.aligned)
        assertNull(plain.alignment)
        assertEquals("", plain.alignmentReason)
        val refused = json.decodeFromString<ReadingAudioManifest>("""{"aligned":false,"alignmentReason":"lengths_ambiguous"}""")
        assertEquals("lengths_ambiguous", refused.alignmentReason)
    }

    @Test fun `a place reads with or without its sentence, and none is null`() {
        val place = json.decodeFromString<ReadingAudioPositionResponse>("""
            { "workId": "rw_1560", "sourceItemId": "3726292328809367", "position": {
              "trackId": "t_5d41402abc4b", "track": 0, "offsetMs": 1234567, "globalMs": 1234567, "completed": false,
              "exact": true, "form": "audio", "timestamp": 1764000000000,
              "sentence": { "href": "text/part0005.html", "fragment": "id34-s12" } } }
        """).position!!
        assertEquals(1234567L, place.offsetMs)
        assertTrue(place.exact)
        assertEquals(ReadingAudioSentence("text/part0005.html", "id34-s12"), place.sentence)
        assertNull(json.decodeFromString<ReadingAudioPositionResponse>("""{"workId":"rw_1560","position":null}""").position)
        val estimate = json.decodeFromString<ReadingAudioPositionResponse>(
            """{"position":{"trackId":"t_aaaaaaaaaaaa","track":1,"offsetMs":5,"exact":false,"form":"text"}}""").position!!
        assertFalse(estimate.exact)
        assertNull(estimate.sentence)
    }

    @Test fun `the hub's error envelope keeps its reason`() {
        val body = json.decodeFromString<HubErrorBody>(
            """{"error":{"code":"audio_not_streamable","reason":"unmapped_root","message":"x","retryable":false},"requestId":"r"}""")
        assertEquals("audio_not_streamable", body.error.code)
        assertEquals("unmapped_root", body.error.reason)
    }
}

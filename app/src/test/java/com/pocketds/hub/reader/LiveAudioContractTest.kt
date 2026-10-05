package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.net.HubResult
import java.io.File
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The app's own code over answers saved from the deployed hub (#19), read-only:
 * Dark Matter's manifest and its edition without audio, The Final Empire's
 * manifest. Skipped unless POCKETDS_LIVE_AUDIO_DIR names a folder holding
 * `dm-manifest.json`, `dm-slim.epub` and `tfe-manifest.json`; nothing of a
 * real book is kept in the repository.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class LiveAudioContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private fun folder(): File {
        val path = System.getenv("POCKETDS_LIVE_AUDIO_DIR")
        assumeTrue("Set POCKETDS_LIVE_AUDIO_DIR to answers saved from the hub", !path.isNullOrBlank())
        return File(path!!).also { assumeTrue(it.isDirectory) }
    }
    private fun manifest(file: File) = json.decodeFromString<ReadingAudioManifest>(file.readText())

    @Test fun darkMatterStreamsAndItsReadAlongMapsEveryStretchOntoItsTracks() {
        val dir = folder()
        val manifest = manifest(File(dir, "dm-manifest.json"))
        assertTrue(AudiobookStream.playable(manifest))
        val parts = AudiobookStream.parts(manifest, "3726292328809367") { "https://hub/tracks/$it?rev=${manifest.revision}" }
        assertEquals(manifest.tracks.size, parts.size)
        assertTrue(parts.all { it.durationMs != null && it.bytes != null && it.cacheKey.isNotEmpty() })
        val plan = ReadAlongStream.plan(HubResult.Ok(manifest))
        assertTrue("Read along streams: $plan", plan is NarrationPlan.Stream)
        val timeline = ReadAlongPackage.read(File(dir, "dm-slim.epub"), requireAudio = false)
        val sources = ReadAlongStream.sources(timeline, manifest, "3726292328809367") { "https://hub/tracks/$it?rev=${manifest.revision}" }
        assertEquals(timeline.tracks.size, sources.size)
        // Every one of the book's tracks is narrated, each from where its file begins.
        assertEquals(manifest.tracks.map { "https://hub/tracks/${it.index}?rev=${manifest.revision}" }.toSet(), sources.map { it.uri }.toSet())
        println("live: ${manifest.tracks.size} tracks, ${timeline.tracks.size} narrated stretches, ${timeline.tracks.sumOf { it.segments.size }} sentences")
    }

    @Test fun theFinalEmpireStreamsAndItsUnmappedReadAlongFallsBackToTheWholeEdition() {
        val manifest = manifest(File(folder(), "tfe-manifest.json"))
        assertTrue(AudiobookStream.playable(manifest))
        assertEquals(NarrationPlan.Whole, ReadAlongStream.plan(HubResult.Ok(manifest)))
    }
}

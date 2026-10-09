package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioChapter
import com.pocketds.hub.model.ReadingAudioManifest
import com.pocketds.hub.model.ReadingAudioTrack
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AwayPromptTest {
    private val manifest = ReadingAudioManifest(
        tracks = listOf(ReadingAudioTrack(index = 0, id = "t0", durationMs = 3_600_000), ReadingAudioTrack(index = 1, id = "t1", durationMs = 3_600_000)),
        chapters = listOf(ReadingAudioChapter("Chapter 7", 0, 0), ReadingAudioChapter("Chapter 8", 1_800_000, 0), ReadingAudioChapter("Chapter 9", 0, 1))
    )
    private val ipad = ReadingProgress.RemoteWriter("ipad-dan", byThisDevice = false, stampMs = 1_000_000)
    private val now = 1_000_000L + 12 * 60_000

    @Test fun anotherDevicesPlaceFurtherOnIsAskedAboutWithItsChapterAndHowLongAgo() {
        val away = AwayPrompt.forAudio(manifest, AudioPlace("t0", 600_000), AudioPlace("t0", 2_400_000), ipad, now)!!
        assertEquals("ipad-dan", away.device)
        assertEquals("Chapter 8", away.where)
        assertEquals(12 * 60_000L, away.agoMs)
        assertEquals("You listened further on ipad-dan", ListenedFurther.title(away))
        assertEquals("Chapter 8 · 12 minutes ago", ListenedFurther.detail(away))
    }

    @Test fun aPlaceBehindOursOrWrittenByThisDeviceOrTheSameIsNotAsked() {
        val ours = AudioPlace("t0", 2_400_000)
        assertNull("behind", AwayPrompt.forAudio(manifest, ours, AudioPlace("t0", 600_000), ipad, now))
        assertNull("this device's own", AwayPrompt.forAudio(manifest, AudioPlace("t0", 600_000), AudioPlace("t1", 10_000), ReadingProgress.RemoteWriter("pocket", true, 1), now))
        assertNull("the same", AwayPrompt.forAudio(manifest, ours, ours, ipad, now))
        assertNull("no writer known", AwayPrompt.forAudio(manifest, AudioPlace("t0", 0), AudioPlace("t1", 0), null, now))
        assertNull("nothing here yet", AwayPrompt.forAudio(manifest, null, AudioPlace("t1", 0), ipad, now))
        assertNull("half a minute ahead is not far", AwayPrompt.forAudio(manifest, AudioPlace("t0", 600_000), AudioPlace("t0", 630_000), ipad, now))
    }

    @Test fun aTrackWithNoChapterIsNamedByItsPart() {
        assertEquals("Chapter 9", AwayPrompt.chapterAt(manifest, AudioPlace("t1", 5_000)))
        assertEquals("Part 2", AwayPrompt.chapterAt(manifest.copy(chapters = emptyList()), AudioPlace("t1", 5_000)))
        assertEquals("", AwayPrompt.chapterAt(manifest, AudioPlace("gone", 5_000)))
    }

    private fun at(share: Double, title: String? = null) = ReadingLocation(locator = JsonObject(buildMap {
        put("locations", JsonObject(mapOf("totalProgression" to JsonPrimitive(share))))
        if (title != null) put("title", JsonPrimitive(title))
    }))

    @Test fun aBooksTextPlaceFurtherOnIsAskedAboutByHowFarThroughTheBookItIs() {
        val away = AwayPrompt.forText(at(0.30), at(0.42, "Chapter 7"), ipad, now)!!
        assertEquals("Chapter 7", away.where)
        assertNull(AwayPrompt.forText(at(0.42), at(0.30), ipad, now))
        assertNull(AwayPrompt.forText(at(0.30), at(0.305), ipad, now))
        assertNull(AwayPrompt.forText(null, at(0.9), ipad, now))
        assertNull(AwayPrompt.forText(at(0.1), at(0.9), null, now))
    }
}

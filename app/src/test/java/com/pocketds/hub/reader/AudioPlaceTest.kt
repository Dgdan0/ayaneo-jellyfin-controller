package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioPosition
import com.pocketds.hub.model.ReadingAudioTrack
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The listening place as the app keeps and sends it (#19, A4). */
class AudioPlaceTest {
    // Three tracks of a book: ten minutes, twenty minutes, five minutes.
    private val tracks = listOf(
        ReadingAudioTrack(0, "t_000000000001", "Track 01", 600_000, 4_800_000),
        ReadingAudioTrack(1, "t_000000000002", "Track 02", 1_200_000, 9_600_000),
        ReadingAudioTrack(2, "t_000000000003", "Track 03", 300_000, 2_400_000)
    )

    @Test fun `a place is a location of its own and reads back the same`() {
        val place = AudioPlace("t_000000000002", 61_250)
        val location = place.location()
        assertEquals(place, AudioPlace.of(location))
        assertNull("A book's page is not a place", AudioPlace.of(ReadingLocation(pageIndex = 4)))
        assertNull(AudioPlace.of(null))
        // Its locator holds the place and nothing else: no clock, no flag of how exact it was.
        assertEquals(setOf("trackId", "offsetMs", "completed"), location.locator!!.keys)
    }

    @Test fun `the hub's answer is a place, whatever its clock says`() {
        val one = ReadingAudioPosition(trackId = "t_000000000002", track = 1, offsetMs = 61_250, timestamp = 1_764_000_000_000, exact = true)
        val later = one.copy(timestamp = 1_900_000_000_000, form = "text", exact = false)
        assertEquals(AudioPlace("t_000000000002", 61_250), AudioPlace.fromServer(one))
        // The timestamp is the hub's clock: two answers at the same place are the same place.
        assertEquals(AudioPlace.fromServer(one), AudioPlace.fromServer(later))
        assertNull(AudioPlace.fromServer(ReadingAudioPosition()))
    }

    @Test fun `a place is kept as the hub will read it back`() {
        // Within the track, as it is.
        assertEquals(AudioPlace("t_000000000002", 61_250), AudioPlace.canonical(tracks, 1, 61_250))
        // A player's track runs a little past the manifest's length; the hub keeps its end.
        assertEquals(AudioPlace("t_000000000001", 600_000), AudioPlace.canonical(tracks, 0, 600_640))
        // The last two seconds of the last track are the book finished, written as its end.
        assertEquals(AudioPlace("t_000000000003", 300_000, completed = true), AudioPlace.canonical(tracks, 2, 298_500))
        assertEquals(AudioPlace("t_000000000003", 300_000, completed = true), AudioPlace.canonical(tracks, 0, 5_000, completed = true))
        // The last two seconds of another track are only its end.
        assertEquals(AudioPlace("t_000000000001", 599_000), AudioPlace.canonical(tracks, 0, 599_000))
        assertNull(AudioPlace.canonical(tracks, 7, 0))
        assertNull(AudioPlace.canonical(emptyList(), 0, 0))
    }

    @Test fun `a write names the place it was based on, or that there was none`() {
        val local = AudioPlace("t_000000000002", 90_000)
        val base = AudioPlace("t_000000000002", 61_250)
        val sent = AudioPlace.body(local, base, baseKnown = true)
        assertEquals("t_000000000002", sent["trackId"]!!.jsonPrimitive.content)
        assertEquals(90_000L, sent["offsetMs"]!!.jsonPrimitive.long)
        assertEquals("false", sent["completed"]!!.jsonPrimitive.content)
        val expected = sent["expected"]!!.jsonObject
        assertEquals(base.trackId, expected["trackId"]!!.jsonPrimitive.content)
        assertEquals(base.offsetMs, expected["offsetMs"]!!.jsonPrimitive.long)
        // The hub ignores the app's clock: none is sent.
        assertFalse(sent.containsKey("timestamp"))
        // Nothing was saved when it was read: expected is null, and the hub checks that still holds.
        assertEquals(JsonNull, AudioPlace.body(local, null, baseKnown = true)["expected"])
        // No base read at all: no expectation, which the hub takes as none to check.
        assertFalse(AudioPlace.body(local, null, baseKnown = false).containsKey("expected"))
    }

    @Test fun `a place kept on this device says how far through the book it is, beside its track and moment`() {
        val kept = AudioPlace.kept(tracks, 1, 300_000)!!
        // The first track and five minutes of the second, of thirty-five minutes in all.
        assertEquals(900_000.0 / 2_100_000, AudioPlace.progressOf(kept)!!, 1e-9)
        assertEquals(setOf("trackId", "offsetMs", "completed", "progress"), kept.locator!!.keys)
        // It is still the same place: the fraction is a note beside it, not part of it.
        assertEquals(AudioPlace("t_000000000002", 300_000), AudioPlace.of(kept))
        // The fraction is of the recording: a part past its length is its end, and the book's start is 0.
        assertEquals(600_000.0 / 2_100_000, AudioPlace.progressOf(AudioPlace.kept(tracks, 0, 600_640)!!)!!, 1e-9)
        assertEquals(0.0, AudioPlace.progressOf(AudioPlace.kept(tracks, 0, 0)!!)!!, 0.0)
        assertNull("No such part, no place to keep", AudioPlace.kept(tracks, 7, 0))
        assertNull(AudioPlace.kept(emptyList(), 0, 0))
    }

    @Test fun `a place whose lengths are not known keeps no fraction rather than a wrong one`() {
        val unknown = tracks.mapIndexed { index, track -> if (index == 1) track.copy(durationMs = 0) else track }
        val kept = AudioPlace.kept(unknown, 0, 5_000)!!
        assertEquals(AudioPlace("t_000000000001", 5_000), AudioPlace.of(kept))
        assertEquals(setOf("trackId", "offsetMs", "completed"), kept.locator!!.keys)
        assertNull(AudioPlace.progressOf(kept))
        // A track the list no longer has: the same.
        assertNull(AudioPlace("t_00000000dead", 5_000).progress(tracks))
        // And a fraction that is not one is never written.
        val place = AudioPlace("t_000000000002", 1)
        assertEquals(setOf("trackId", "offsetMs", "completed"), place.location(Double.NaN).locator!!.keys)
        assertEquals(setOf("trackId", "offsetMs", "completed"), place.location(Double.POSITIVE_INFINITY).locator!!.keys)
        assertEquals(1.0, AudioPlace.progressOf(place.location(7.0))!!, 0.0)
        assertEquals(0.0, AudioPlace.progressOf(place.location(-1.0))!!, 0.0)
    }

    @Test fun `a finished book is the whole of it, with or without a fraction beside it`() {
        // The last two seconds of the last track are the book finished, written as 100%.
        val kept = AudioPlace.kept(tracks, 2, 298_500)!!
        assertTrue(AudioPlace.of(kept)!!.completed)
        assertEquals(1.0, AudioPlace.progressOf(kept)!!, 0.0)
        assertEquals(1.0, AudioPlace.progressOf(AudioPlace.kept(tracks, 0, 5_000, completed = true)!!)!!, 0.0)
        // A finished place an older build kept has no fraction, and is finished all the same.
        assertEquals(1.0, AudioPlace.progressOf(AudioPlace("t_000000000003", 300_000, completed = true).location())!!, 0.0)
        // Even when lengths are unknown the book is done.
        assertEquals(1.0, AudioPlace("t_000000000003", 300_000, completed = true).progress(emptyList())!!, 0.0)
    }

    @Test fun `a place kept by an older build still reads, and says nothing of how far through the book it is`() {
        val old = AudioPlace("t_000000000002", 61_250).location()
        assertEquals(setOf("trackId", "offsetMs", "completed"), old.locator!!.keys)
        assertEquals(AudioPlace("t_000000000002", 61_250), AudioPlace.of(old))
        // Not 0%: it was never counted, which is not the same as the start of the book.
        assertNull(AudioPlace.progressOf(old))
        assertNull(AudioPlace.progressOf(ReadingLocation(pageIndex = 4)))
        assertNull(AudioPlace.progressOf(null))
        // A location of this kind with something else where the fraction goes is not a fraction either.
        val odd = ReadingLocation(locator = buildJsonObject {
            put("trackId", "t_000000000002"); put("offsetMs", 1); put("completed", false); put("progress", "soon")
        })
        assertEquals(AudioPlace("t_000000000002", 1), AudioPlace.of(odd))
        assertNull(AudioPlace.progressOf(odd))
    }

    @Test fun `the write is the place and its base, whatever is kept beside them`() {
        val local = AudioPlace("t_000000000002", 90_000)
        val base = AudioPlace("t_000000000002", 61_250)
        val plain = AudioPlace.body(local, base, baseKnown = true)
        val key = ReadingCheckpointKey("profile", "rw_1", "3726292328809367", AudioPlace.KIND)
        val noted = ReadingCheckpoint(key, local = local.location(0.4), base = base.location(0.3), baseKnown = true, pending = true)
        val sent = AudioPlace.body(noted)!!
        assertEquals(plain, sent)
        assertEquals(setOf("trackId", "offsetMs", "completed", "expected"), sent.keys)
        assertEquals(setOf("trackId", "offsetMs"), sent["expected"]!!.jsonObject.keys)
        assertFalse("The fraction is this device's: the hub is never told", sent.toString().contains("progress"))
        // Nothing read yet: still no expectation, with the fraction there or not.
        assertEquals(AudioPlace.body(local, null, baseKnown = false), AudioPlace.body(noted.copy(base = null, baseKnown = false)))
    }

    @Test fun `two locations are the same place whatever is kept beside the place`() {
        val place = AudioPlace("t_000000000002", 61_250)
        assertTrue(AudioPlace.samePlace(place.location(), place.location()))
        // Kept with a fraction and read back from the hub without one: the hub's own reading of the same place.
        assertTrue(AudioPlace.samePlace(place.location(0.4), place.location()))
        assertTrue(AudioPlace.samePlace(place.location(), place.location(0.4)))
        assertTrue(AudioPlace.samePlace(place.location(0.4), place.location(0.5)))
        // A different moment, track or finish is another place.
        assertFalse(AudioPlace.samePlace(place.location(0.4), AudioPlace("t_000000000002", 61_251).location(0.4)))
        assertFalse(AudioPlace.samePlace(place.location(), AudioPlace("t_000000000001", 61_250).location()))
        assertFalse(AudioPlace.samePlace(place.location(), AudioPlace("t_000000000002", 61_250, completed = true).location()))
        // Nothing, or a location that is not a place of this kind, is not a place.
        assertTrue(AudioPlace.samePlace(null, null))
        assertFalse(AudioPlace.samePlace(place.location(), null))
        assertFalse(AudioPlace.samePlace(null, place.location()))
        assertFalse(AudioPlace.samePlace(place.location(), ReadingLocation(pageIndex = 4)))
        assertTrue(AudioPlace.samePlace(ReadingLocation(pageIndex = 4), ReadingLocation(pageIndex = 4)))
    }

    @Test fun `a place opens on its track, and a finished book at its start`() {
        assertEquals(1 to 61_250L, AudioPlace("t_000000000002", 61_250).openAt(tracks))
        assertEquals(0 to 0L, AudioPlace("t_000000000003", 300_000, completed = true).openAt(tracks))
        // A track the book no longer has: its start, never another track's moment.
        assertEquals(0 to 0L, AudioPlace("t_00000000dead", 61_250).openAt(tracks))
        assertEquals(2 to 300_000L, AudioPlace("t_000000000003", 999_999).openAt(tracks))
    }

    @Test fun `a place in words, for the sheet that asks which`() {
        assertEquals("Part 2 of 3 · 1:01", AudioPlace.label(AudioPlace("t_000000000002", 61_250).location(), tracks))
        assertEquals("Finished", AudioPlace.label(AudioPlace("t_000000000003", 300_000, true).location(), tracks))
        assertTrue(AudioPlace.started(AudioPlace("t_000000000001", 1).location()))
        assertFalse(AudioPlace.started(AudioPlace("t_000000000001", 0).location()))
    }

    @Test fun `a place this device kept by part number finds its track by size`() {
        // Dark Matter (#19): the ZIP's order put the first track last, so part 7 there is the
        // hub's first track and part 0 its second.
        val zipSizes = listOf(9_600_000L, 2_400_000L, 4_800_000L)
        assertEquals(1, AudioPlace.legacyTrack(0, zipSizes, tracks))
        assertEquals(0, AudioPlace.legacyTrack(2, zipSizes, tracks))
        // No ZIP to read, or a size two tracks share: the same number.
        assertEquals(2, AudioPlace.legacyTrack(2, emptyList(), tracks))
        val twins = tracks.map { it.copy(bytes = 1L) }
        assertEquals(1, AudioPlace.legacyTrack(1, listOf(1L, 1L, 1L), twins))
        assertNull(AudioPlace.legacyTrack(5, emptyList(), tracks))
    }
}

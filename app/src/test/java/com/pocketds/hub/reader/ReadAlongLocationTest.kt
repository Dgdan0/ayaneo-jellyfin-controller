package com.pocketds.hub.reader

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReadAlongLocationTest {
    private val timeline = ReadAlongTimeline(listOf(ReadAlongTrack("voice.mp3", listOf(
        ReadAlongSegment("book/ch1.xhtml", "s1", "voice.mp3", 1000, 3000),
        ReadAlongSegment("book/ch1.xhtml", "s2", "voice.mp3", 3000, 5000)
    ))))
    private val locator = Json.parseToJsonElement("""{"href":"old.xhtml","type":"application/xhtml+xml","locations":{"totalProgression":0.2,"cssSelector":"#old"},"text":{"highlight":"old sentence"}}""").jsonObject

    /** #19: the place is the sentence, a text locator any reader understands; the hub maps it to the audio. */
    @Test fun theSentenceIsThePlaceWithoutStaleSelectorsOrAPrivateOffset() {
        val saved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 2500), false)
        assertEquals("book/ch1.xhtml", saved["href"]!!.jsonPrimitive.content)
        val locations = saved["locations"]!!.jsonObject
        assertEquals("s2", locations["fragments"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(locations.containsKey("cssSelector"))
        assertFalse(saved.containsKey("text"))
        assertFalse("No private offset is written", locations.containsKey("pocketdsAudio"))
        // It resumes at the sentence's start.
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongLocation.resume(saved, timeline))
    }

    @Test fun anOldPrivateOffsetIsDroppedAndNotTrusted() {
        val old = Json.parseToJsonElement("""{"href":"book/ch1.xhtml","locations":{"fragments":["s2"],"pocketdsAudio":{"track":0,"offsetMs":3900}}}""").jsonObject
        assertEquals("The sentence decides", ReadAlongPosition(0, 2000), ReadAlongLocation.resume(old, timeline))
        val saved = ReadAlongLocation.save(old, timeline, ReadAlongPosition(0, 500), false)
        assertFalse(saved["locations"]!!.jsonObject.containsKey("pocketdsAudio"))
    }

    /** #49: with the screen off there is no page to ask how far through the book the voice is; the narration says. */
    @Test fun withoutAPageHowFarThroughIsTheShareOfTheNarrationBeforeTheSentence() {
        val two = ReadAlongTimeline(listOf(
            ReadAlongTrack("a.mp3", listOf(
                ReadAlongSegment("ch1.xhtml", "a", "a.mp3", 0, 2000),
                ReadAlongSegment("ch1.xhtml", "b", "a.mp3", 2000, 6000))),
            ReadAlongTrack("b.mp3", listOf(
                ReadAlongSegment("ch1.xhtml", "c", "b.mp3", 0, 2000),
                ReadAlongSegment("ch2.xhtml", "d", "b.mp3", 2000, 4000)))
        ))
        // Chapter one is eight seconds of voice, in the book from 0.2 to 0.6. Three seconds in, in "b": 3/8 of the way.
        val inB = ReadAlongLocation.estimate(two, ReadAlongPosition(0, 3000), 0.2, 0.6)!!
        assertEquals("ch1.xhtml", inB.href)
        assertEquals(0.375, inB.progression, 1e-9)
        assertEquals(0.2 + 0.4 * 0.375, inB.totalProgression, 1e-9)
        // In the second file the first file's part of the chapter counts: "c" begins 6 of 8 s in.
        assertEquals(0.75, ReadAlongLocation.estimate(two, ReadAlongPosition(1, 0), 0.2, 0.6)!!.progression, 1e-9)
        // Another chapter counts from its own first sentence.
        val ch2 = ReadAlongLocation.estimate(two, ReadAlongPosition(1, 2500), 0.6, 1.0)!!
        assertEquals("ch2.xhtml", ch2.href)
        assertEquals(0.25, ch2.progression, 1e-9)
        assertEquals(0.7, ch2.totalProgression, 1e-9)
        // Between two sentences it is the one before; past the end, the end; no such file, none.
        assertEquals("ch1.xhtml", ReadAlongLocation.estimate(two, ReadAlongPosition(0, 6000), 0.2, 0.6)!!.href)
        assertNull(ReadAlongLocation.estimate(two, ReadAlongPosition(5, 0), 0.0, 1.0))
    }

    @Test fun theEstimateIsWrittenIntoTheLocatorBesideTheSentence() {
        val moved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 2500), false)
        val with = ReadAlongLocation.withProgress(moved, ReadAlongLocation.Estimate("book/ch1.xhtml", 0.5, 0.35))
        val locations = with["locations"]!!.jsonObject
        assertEquals(0.5, locations["progression"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.35, locations["totalProgression"]!!.jsonPrimitive.double, 0.0)
        assertEquals("The sentence is still the place", "s2", locations["fragments"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals("book/ch1.xhtml", with["href"]!!.jsonPrimitive.content)
    }

    @Test fun completionAndTextOnlyFallbackAreExplicit() {
        val saved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 4000), true)
        assertEquals(1.0, saved["locations"]!!.jsonObject["totalProgression"]!!.jsonPrimitive.double, 0.0)
        val textOnly = Json.parseToJsonElement("""{"href":"book/ch1.xhtml","locations":{"fragments":["s2"]}}""").jsonObject
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongLocation.resume(textOnly, timeline))
        assertNull(ReadAlongLocation.resume(locator, timeline))
    }
}

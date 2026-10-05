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

    @Test fun completionAndTextOnlyFallbackAreExplicit() {
        val saved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 4000), true)
        assertEquals(1.0, saved["locations"]!!.jsonObject["totalProgression"]!!.jsonPrimitive.double, 0.0)
        val textOnly = Json.parseToJsonElement("""{"href":"book/ch1.xhtml","locations":{"fragments":["s2"]}}""").jsonObject
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongLocation.resume(textOnly, timeline))
        assertNull(ReadAlongLocation.resume(locator, timeline))
    }
}

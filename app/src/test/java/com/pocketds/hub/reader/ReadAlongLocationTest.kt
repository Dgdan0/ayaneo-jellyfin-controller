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

    @Test fun checkpointKeepsExactAudioAndAlignedTextWithoutStaleSelectors() {
        val saved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 2500), false)
        assertEquals("book/ch1.xhtml", saved["href"]!!.jsonPrimitive.content)
        val locations = saved["locations"]!!.jsonObject
        assertEquals("s2", locations["fragments"]!!.jsonArray.single().jsonPrimitive.content)
        assertFalse(locations.containsKey("cssSelector"))
        assertFalse(saved.containsKey("text"))
        assertEquals(ReadAlongPosition(0, 2500), ReadAlongLocation.resume(saved, timeline))
        assertTrue(locations.containsKey("pocketdsAudio")) // Readium preserves custom location properties.
    }
    @Test fun completionAndTextOnlyFallbackAreExplicit() {
        val saved = ReadAlongLocation.save(locator, timeline, ReadAlongPosition(0, 4000), true)
        assertEquals(1.0, saved["locations"]!!.jsonObject["totalProgression"]!!.jsonPrimitive.double, 0.0)
        val textOnly = Json.parseToJsonElement("""{"href":"book/ch1.xhtml","locations":{"fragments":["s2"]}}""").jsonObject
        assertEquals(ReadAlongPosition(0, 2000), ReadAlongLocation.resume(textOnly, timeline))
        assertNull(ReadAlongLocation.resume(locator, timeline))
    }
}

package com.pocketds.hub.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleTimelineTest {
    private val timeline = SubtitleTimeline(
        listOf(
            SubtitleWindow(10_000, 12_000, listOf("first")),
            SubtitleWindow(11_000, 13_000, listOf("overlap")),
            SubtitleWindow(20_000, 21_000, listOf("last"))
        )
    )

    @Test
    fun `positive offset shows cues later without changing their timeline`() {
        assertEquals(emptyList<String>(), timeline.valuesAt(11_999, 2_000))
        assertEquals(listOf("first"), timeline.valuesAt(12_000, 2_000))
        assertEquals(listOf("first", "overlap"), timeline.valuesAt(13_500, 2_000))
    }

    @Test
    fun `negative offset shows cues earlier`() {
        assertEquals(listOf("first"), timeline.valuesAt(9_500, -500))
        assertEquals(listOf("last"), timeline.valuesAt(19_000, -1_000))
    }

    @Test
    fun `cue end is exclusive and invalid windows are ignored`() {
        val value = SubtitleTimeline(
            listOf(
                SubtitleWindow(5, 5, listOf("invalid")),
                SubtitleWindow(5, 10, listOf("valid"))
            )
        )
        assertEquals(listOf("valid"), value.valuesAt(9, 0))
        assertEquals(emptyList<String>(), value.valuesAt(10, 0))
    }
}

package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackChapter
import com.pocketds.hub.model.PlaybackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackEnhancementsTest {
    @Test
    fun chapters_are_ordered_clamped_and_have_stable_navigation() {
        val chapters = PlaybackEnhancements.chapters(
            listOf(
                PlaybackChapter("late", "Credits", 800_000),
                PlaybackChapter("bad", "Bad", -1),
                PlaybackChapter("early", "Opening", 10_000),
                PlaybackChapter("duplicate", "Duplicate", 10_000)
            ),
            durationMillis = 900_000
        )

        assertEquals(listOf("Opening", "Credits"), chapters.map { it.name })
        assertEquals(800_000L, PlaybackEnhancements.nextChapter(chapters, 10_001)?.positionMillis)
        assertEquals(10_000L, PlaybackEnhancements.previousChapter(chapters, 800_000)?.positionMillis)
        assertNull(PlaybackEnhancements.nextChapter(chapters, 800_000))
    }

    @Test
    fun segment_prompt_is_available_only_while_meaningfully_before_the_end() {
        val segment = PlaybackSegment("intro", "Intro", 30_000, 90_000)

        assertEquals(segment, PlaybackEnhancements.skipPrompt(listOf(segment), 30_000))
        assertEquals(segment, PlaybackEnhancements.skipPrompt(listOf(segment), 88_500))
        assertNull(PlaybackEnhancements.skipPrompt(listOf(segment), 89_500))
        assertNull(PlaybackEnhancements.skipPrompt(listOf(segment), 90_000))
    }

    @Test
    fun playback_speed_and_aspect_options_have_safe_defaults() {
        assertEquals(1f, PlaybackEnhancements.defaultSpeed)
        assertEquals(listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f), PlaybackEnhancements.speeds)
        assertEquals(PlaybackAspect.FIT, PlaybackEnhancements.defaultAspect)
        assertEquals(
            listOf(SubtitleAppearance.SYSTEM, SubtitleAppearance.LARGE, SubtitleAppearance.HIGH_CONTRAST),
            PlaybackEnhancements.subtitleAppearances
        )
        assertEquals(SubtitleAppearance.SYSTEM, PlaybackEnhancements.defaultSubtitleAppearance)
    }
}

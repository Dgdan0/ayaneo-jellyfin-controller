package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackChapter
import com.pocketds.hub.model.PlaybackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackEnhancementsTest {
    @Test
    fun subtitles_rise_above_the_controls_only_while_they_show() {
        val large = PlaybackEnhancements.subtitleBottomFraction(SubtitleAppearance.LARGE)
        // Hidden controls: the appearance's own place.
        assertEquals(large, PlaybackEnhancements.subtitleLift(large, coveredPx = 0, heightPx = 1080), 0f)
        // A 216px panel on a 1080px picture covers 20%; subtitles sit 2% above it.
        assertEquals(.22f, PlaybackEnhancements.subtitleLift(large, coveredPx = 216, heightPx = 1080), 1e-4f)
        // Already clear of a short panel: unchanged.
        assertEquals(large, PlaybackEnhancements.subtitleLift(large, coveredPx = 54, heightPx = 1080), 0f)
        // Before layout, or a panel taller than sense: no lift, or a capped one.
        assertEquals(large, PlaybackEnhancements.subtitleLift(large, coveredPx = 216, heightPx = 0), 0f)
        assertEquals(.6f, PlaybackEnhancements.subtitleLift(large, coveredPx = 1000, heightPx = 1080), 0f)
    }

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

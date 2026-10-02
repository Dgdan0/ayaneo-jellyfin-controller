package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackChapter
import com.pocketds.hub.model.PlaybackSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackEnhancementsTest {
    @Test
    fun subtitles_rise_above_the_controls_only_while_they_show() {
        val large = SubtitleSize.LARGE.bottomFraction
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
    fun subtitles_default_to_an_outline_that_stays_put_when_the_controls_show() {
        val look = SubtitleLook()
        assertEquals(SubtitleStyle.OUTLINE, look.style)
        assertEquals(SubtitleSize.MEDIUM, look.size)
        assertEquals(look.size.bottomFraction, PlaybackEnhancements.subtitlePlacement(look, coveredPx = 216, heightPx = 1080), 0f)
        val lifted = look.copy(liftWithControls = true)
        assertEquals(.22f, PlaybackEnhancements.subtitlePlacement(lifted, coveredPx = 216, heightPx = 1080), 1e-4f)
        assertEquals("Outline · Medium", PlayerLabels.subtitleLook(look))
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
    }

    @Test
    fun a_chapters_picture_comes_from_inside_it_on_the_hubs_frame_grid() {
        // A quarter of the way into a 93-second opening, rounded down to five-second frames.
        assertEquals(260_000L, PlaybackEnhancements.chapterFrameMillis(238_000, 331_000))
        // A four-minute cold open: thirty seconds in, past the fade from black.
        assertEquals(30_000L, PlaybackEnhancements.chapterFrameMillis(0, 238_000))
        // A twelve-second chapter stays inside itself.
        assertEquals(0L, PlaybackEnhancements.chapterFrameMillis(0, 12_000))
        // The last chapter, with no end known.
        assertEquals(1_630_000L, PlaybackEnhancements.chapterFrameMillis(1_616_000, 0))
    }

    @Test
    fun a_segment_belongs_to_the_chapter_it_starts_with() {
        val intro = PlaybackSegment(type = "Intro", startMillis = 238_500, endMillis = 331_000)
        assertEquals(intro, PlaybackEnhancements.segmentAt(listOf(intro), 238_000))
        assertNull(PlaybackEnhancements.segmentAt(listOf(intro), 0))
    }
}

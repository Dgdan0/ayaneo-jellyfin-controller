package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackChapter
import com.pocketds.hub.model.PlaybackSegment

enum class PlaybackAspect { FIT, FILL, ZOOM, ORIGINAL }

enum class SubtitleAppearance { SYSTEM, LARGE, HIGH_CONTRAST }

/** Pure rules for optional chapter/segment data and safe player controls. */
object PlaybackEnhancements {
    const val defaultSpeed = 1f
    val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
    val defaultAspect = PlaybackAspect.FIT
    val subtitleAppearances = listOf(
        SubtitleAppearance.SYSTEM,
        SubtitleAppearance.LARGE,
        SubtitleAppearance.HIGH_CONTRAST
    )
    val defaultSubtitleAppearance = SubtitleAppearance.SYSTEM

    /** Where an appearance puts subtitles: their bottom edge, as a fraction of the video's height. */
    fun subtitleBottomFraction(appearance: SubtitleAppearance): Float = when (appearance) {
        SubtitleAppearance.SYSTEM -> .08f
        SubtitleAppearance.LARGE -> .11f
        SubtitleAppearance.HIGH_CONTRAST -> .09f
    }

    /**
     * Subtitles sit just above the timeline while it is showing, rather than
     * under it: the controls cover the bottom fifth of the picture, and Large
     * text at 11% drew straight through the seek bar. [coveredPx] is how much
     * of the bottom the controls cover, 0 when they are hidden.
     */
    fun subtitleLift(baseFraction: Float, coveredPx: Int, heightPx: Int): Float {
        if (coveredPx <= 0 || heightPx <= 0) return baseFraction
        val clear = coveredPx.toFloat() / heightPx + SUBTITLE_GAP
        return maxOf(baseFraction, clear).coerceAtMost(MAX_SUBTITLE_LIFT)
    }

    private const val SUBTITLE_GAP = .02f
    private const val MAX_SUBTITLE_LIFT = .6f

    fun chapters(values: List<PlaybackChapter>, durationMillis: Long): List<PlaybackChapter> {
        val seen = hashSetOf<Long>()
        return values.asSequence()
            .filter { it.positionMillis >= 0 && (durationMillis <= 0 || it.positionMillis < durationMillis) }
            .sortedBy { it.positionMillis }
            .filter { seen.add(it.positionMillis) }
            .mapIndexed { index, chapter ->
                chapter.copy(name = chapter.name.ifBlank { "Chapter ${index + 1}" })
            }
            .toList()
    }

    fun nextChapter(chapters: List<PlaybackChapter>, positionMillis: Long): PlaybackChapter? =
        chapters.firstOrNull { it.positionMillis > positionMillis + 750L }

    fun previousChapter(chapters: List<PlaybackChapter>, positionMillis: Long): PlaybackChapter? =
        chapters.lastOrNull { it.positionMillis < positionMillis - 750L }

    fun skipPrompt(segments: List<PlaybackSegment>, positionMillis: Long): PlaybackSegment? =
        segments.firstOrNull { positionMillis >= it.startMillis && positionMillis < it.endMillis - 1_000L }
}

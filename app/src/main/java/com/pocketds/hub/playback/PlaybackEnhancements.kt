package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackChapter
import com.pocketds.hub.model.PlaybackSegment

enum class PlaybackAspect { FIT, FILL, ZOOM, ORIGINAL }

/**
 * Outline is white text with a black edge and nothing behind it, the way mpv
 * (Findroid's player) draws subtitles. Box is Android's caption look: white on a
 * dark box. Outline is the default since 2026-10: the box read as heavy next to
 * the player the app replaced.
 */
enum class SubtitleStyle { OUTLINE, BOX }

/** Text height as a fraction of the video, and where the bottom line sits. */
enum class SubtitleSize(val textFraction: Float, val bottomFraction: Float) {
    SMALL(.046f, .07f), MEDIUM(.054f, .08f), LARGE(.066f, .10f)
}

/**
 * [liftWithControls] moves subtitles above the timeline while it shows. Off by
 * default: a line that jumps whenever you touch the screen is more distracting
 * than one the controls briefly cover.
 */
data class SubtitleLook(
    val style: SubtitleStyle = SubtitleStyle.OUTLINE,
    val size: SubtitleSize = SubtitleSize.MEDIUM,
    val liftWithControls: Boolean = false
)

/** Pure rules for optional chapter/segment data and safe player controls. */
object PlaybackEnhancements {
    const val defaultSpeed = 1f
    val speeds = listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
    val defaultAspect = PlaybackAspect.FIT
    /**
     * Where subtitles sit: their bottom edge, as a fraction of the video's height.
     * With [SubtitleLook.liftWithControls], just above the timeline while it is
     * showing, since the controls cover the bottom fifth of the picture.
     * [coveredPx] is how much of the bottom the controls cover, 0 when hidden.
     */
    fun subtitlePlacement(look: SubtitleLook, coveredPx: Int, heightPx: Int): Float =
        if (look.liftWithControls) subtitleLift(look.size.bottomFraction, coveredPx, heightPx) else look.size.bottomFraction

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

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

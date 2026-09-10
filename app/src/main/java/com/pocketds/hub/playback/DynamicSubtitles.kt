package com.pocketds.hub.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** One group of cues that is visible for the half-open interval [startMillis, endMillis). */
internal data class SubtitleWindow<T>(
    val startMillis: Long,
    val endMillis: Long,
    val values: List<T>
)

/**
 * An in-memory subtitle timeline.
 *
 * Positive offset means "show later", so playback at 12s reads the original
 * 10s cue when the offset is +2s. Querying this timeline is cheap enough to do
 * while a slider moves and never asks ExoPlayer to reload the video or track.
 */
internal class SubtitleTimeline<T>(windows: List<SubtitleWindow<T>>) {
    private val windows = windows
        .filter { it.endMillis > it.startMillis && it.values.isNotEmpty() }
        .sortedBy { it.startMillis }

    val isEmpty: Boolean get() = windows.isEmpty()

    fun valuesAt(playbackMillis: Long, offsetMillis: Long): List<T> {
        val sourceMillis = playbackMillis - offsetMillis
        if (sourceMillis < 0) return emptyList()
        return windows.asSequence()
            .takeWhile { it.startMillis <= sourceMillis }
            .filter { sourceMillis < it.endMillis }
            .flatMap { it.values.asSequence() }
            .toList()
    }
}

/** Parses the two text formats exposed by the Hub into a reusable local timeline. */
@UnstableApi
internal fun parseDynamicSubtitle(bytes: ByteArray, codec: String): SubtitleTimeline<Cue> {
    val mime = when (codec.lowercase()) {
        "srt", "subrip" -> MimeTypes.APPLICATION_SUBRIP
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        else -> error("Unsupported subtitle codec: $codec")
    }
    val format = Format.Builder().setSampleMimeType(mime).build()
    val factory = DefaultSubtitleParserFactory()
    check(factory.supportsFormat(format)) { "No Media3 parser for $mime" }
    val parser = factory.create(format)
    val windows = mutableListOf<SubtitleWindow<Cue>>()
    parser.parse(bytes, SubtitleParser.OutputOptions.allCues()) { timed ->
        if (
            timed.startTimeUs == C.TIME_UNSET || timed.durationUs == C.TIME_UNSET ||
            timed.durationUs <= 0
        ) return@parse
        windows += SubtitleWindow(
            startMillis = timed.startTimeUs / 1_000L,
            endMillis = timed.endTimeUs / 1_000L,
            values = timed.cues.toList()
        )
    }
    parser.reset()
    return SubtitleTimeline(windows)
}

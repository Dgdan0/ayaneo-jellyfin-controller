package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.state.Fmt
import java.util.Locale
import kotlin.math.abs

/**
 * The player's wording, pure so it is tested on the JVM rather than read off
 * the screen. Decimals use Locale.US like [Fmt]: "23.98 fps" must not become
 * "23,98 fps" because of a device setting.
 */
object PlayerLabels {
    /** The player's title: the series, or the film. */
    fun title(item: com.pocketds.hub.model.PlaybackItem): String = item.seriesTitle.ifEmpty { item.title }

    /** Under it: "S1E1 · Somewhere Not Here" for an episode, nothing for a film; " · Offline" when it plays from the device. */
    fun subtitle(item: com.pocketds.hub.model.PlaybackItem, offline: Boolean): String = listOfNotNull(
        if (item.seriesTitle.isNotEmpty()) com.pocketds.hub.ui.EpisodeLabel.of(item.seasonNumber, item.episodeNumber, item.title) else null,
        "Offline".takeIf { offline }
    ).filter(String::isNotBlank).joinToString(" · ")

    /**
     * Under the timeline's start: "5:34 · Part A". A chapter called only
     * "Chapter 2" says nothing the marks do not, so it is left out.
     */
    fun positionLine(positionMillis: Long, chapterName: String?): String {
        val name = chapterName?.trim().orEmpty().takeUnless { it.isEmpty() || GENERIC_CHAPTER.matches(it) }
        return listOfNotNull(Fmt.clock(positionMillis), name).joinToString(" · ")
    }

    /** Under the timeline's end: the time left, "−22:53". */
    fun remainingLine(positionMillis: Long, durationMillis: Long): String =
        if (durationMillis <= 0) "" else "\u2212" + Fmt.clock((durationMillis - positionMillis).coerceAtLeast(0))

    private val GENERIC_CHAPTER = Regex("""(?i)chapter\s*\d+""")


    fun signedTime(deltaMillis: Long): String =
        (if (deltaMillis < 0) "−" else "+") + Fmt.clock(abs(deltaMillis))

    fun subtitleOffset(offsetMillis: Long): String = when {
        offsetMillis == 0L -> "No offset"
        offsetMillis < 0 -> String.format(Locale.US, "%.1f seconds earlier", abs(offsetMillis) / 1_000.0)
        else -> String.format(Locale.US, "%.1f seconds later", offsetMillis / 1_000.0)
    }

    fun subtitleStyle(value: SubtitleStyle): String = when (value) {
        SubtitleStyle.OUTLINE -> "Outline"
        SubtitleStyle.BOX -> "Box"
    }

    fun subtitleSize(value: SubtitleSize): String = when (value) {
        SubtitleSize.SMALL -> "Small"
        SubtitleSize.MEDIUM -> "Medium"
        SubtitleSize.LARGE -> "Large"
    }

    fun subtitleLook(look: SubtitleLook): String = "${subtitleStyle(look.style)} · ${subtitleSize(look.size)}"

    fun speed(value: Float): String = if (value == 1f) "Normal" else "${value}×"

    fun aspect(value: PlaybackAspect): String = when (value) {
        PlaybackAspect.FIT -> "Fit"
        PlaybackAspect.FILL -> "Fill"
        PlaybackAspect.ZOOM -> "Zoom"
        PlaybackAspect.ORIGINAL -> "Original aspect"
    }

    /** What is playing and how, for the playback panel. */
    fun diagnostic(value: PlaybackPrepareResponse): String = buildList {
        add(value.playMethod.ifEmpty { "Playback" })
        if (value.width > 0) add("${value.width}×${value.height}")
        if (value.videoCodec.isNotEmpty()) add(value.videoCodec.uppercase(Locale.US))
        if (value.audioCodec.isNotEmpty()) add(value.audioCodec.uppercase(Locale.US))
        if (value.frameRate > 0) add(String.format(Locale.US, "%.2f fps", value.frameRate))
        if (value.bitrate > 0) add(Fmt.mbps(value.bitrate.toLong()))
        if (value.hdr.isNotEmpty()) add(value.hdr)
        if (value.transcodeReason.isNotEmpty()) add(value.transcodeReason)
    }.joinToString(" · ")

    fun sourceDetail(container: String, bitrate: Int): String = buildList {
        if (container.isNotEmpty()) add(container.uppercase(Locale.US))
        if (bitrate > 0) add(Fmt.mbps(bitrate.toLong()))
    }.joinToString(" · ")

    /** A media version with its name first, for the options screen's single line. */
    fun source(name: String, container: String, bitrate: Int): String =
        listOf(name, sourceDetail(container, bitrate)).filter(String::isNotEmpty).joinToString(" · ")

    /** This video's Quality row: "Original · 1080p", "10 Mbps · 720p", or the downloaded file. */
    fun qualityValue(qualityLabel: String, height: Int, offline: Boolean): String = when {
        offline -> "Original · downloaded"
        height > 0 -> "$qualityLabel · ${height}p"
        else -> qualityLabel
    }

    /**
     * Under a chapter's name: where it starts, how long it runs, and what it is
     * when a skip segment starts with it ("3:58 · 2 min · Intro").
     */
    fun chapterDetail(startMillis: Long, endMillis: Long, kind: String?): String = listOfNotNull(
        Fmt.clock(startMillis),
        (endMillis - startMillis).takeIf { it > 0 }?.let(::chapterLength),
        kind
    ).joinToString(" · ")

    /** "45 s" under a minute, else whole minutes: "2 min". */
    fun chapterLength(millis: Long): String {
        val seconds = (millis / 1_000).coerceAtLeast(1)
        return if (seconds < 60) "$seconds s" else "${(seconds + 30) / 60} min"
    }

    /** A segment's kind in a chapter's line; credits rather than Jellyfin's "Outro". */
    fun segmentKind(type: String): String? = when (type.trim().lowercase(Locale.US)) {
        "intro" -> "Intro"
        "outro" -> "Credits"
        "recap" -> "Recap"
        "preview" -> "Preview"
        "commercial" -> "Ad"
        else -> null
    }

    /** How the stream will be delivered: method, resolution, bitrate. */
    fun quality(value: PlaybackPrepareResponse): String = buildString {
        append(value.playMethod.ifEmpty { "Original" })
        if (value.width > 0 && value.height > 0) append(" · ${value.width}×${value.height}")
        if (value.bitrate > 0) append(" · ${Fmt.mbps(value.bitrate.toLong())}")
    }
}

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

    fun speed(value: Float): String = if (value == 1f) "Normal" else rate(value)

    /** A speed in a pill or beside a time: "1×", "1.25×", "2×" (never "2.0×"). */
    fun rate(value: Float): String {
        val text = if (value == value.toInt().toFloat()) value.toInt().toString()
            else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        return "$text×"
    }

    /**
     * Listening (#16, A2): what is left as heard at the speed playing, "12 min
     * left in part · 4h 10m in book", or "in chapter" where the book has chapters
     * (#31, [noun]); the book's part drops while the lengths of its parts are
     * still being read.
     */
    fun timeLeft(entryLeftMs: Long, bookLeftMs: Long?, noun: String = "part"): String = listOfNotNull(
        "${Fmt.runtime((entryLeftMs / 1_000).coerceAtLeast(60))} left in $noun",
        bookLeftMs?.let { "${Fmt.runtime((it / 1_000).coerceAtLeast(60))} in book" }
    ).joinToString(" · ")

    /** The mini player's time, "4h 10m left", and nothing while no length is known. */
    fun leftLine(leftMs: Long?): String =
        leftMs?.let { "${Fmt.runtime((it / 1_000).coerceAtLeast(60))} left" }.orEmpty()

    /** The sleep timer on its button: "Sleep", "Sleep · 14:32", "Sleep · end of part" (or chapter), fading. */
    fun sleep(timer: com.pocketds.hub.reader.SleepTimer?, noun: String = "part"): String = when {
        timer == null -> "Sleep"
        timer.fading -> "Sleep · fading"
        timer.choice == com.pocketds.hub.reader.SleepChoice.EndOfPart && timer.skipParts == 0 -> "Sleep · end of $noun"
        else -> "Sleep · ${Fmt.clock(timer.remainingMs)}"
    }

    /** A sleep timer to choose: "15 minutes", "End of this part" (or chapter). */
    fun sleepChoice(choice: com.pocketds.hub.reader.SleepChoice, noun: String = "part"): String = when (choice) {
        is com.pocketds.hub.reader.SleepChoice.Minutes -> if (choice.minutes == 60) "1 hour" else "${choice.minutes} minutes"
        com.pocketds.hub.reader.SleepChoice.EndOfPart -> "End of this $noun"
    }

    fun aspect(value: PlaybackAspect): String = when (value) {
        PlaybackAspect.FIT -> "Fit"
        PlaybackAspect.FILL -> "Fill"
        PlaybackAspect.ZOOM -> "Zoom"
        PlaybackAspect.ORIGINAL -> "Original aspect"
    }

    /** Jellyfin's play method in words: "Direct play", "Direct stream", "Converting". */
    fun playMethod(value: String): String = when (value.trim().lowercase(Locale.US)) {
        "directplay" -> "Direct play"
        "directstream" -> "Direct stream"
        "transcode" -> "Converting"
        "" -> "Playback"
        else -> value
    }

    /** What is playing and how, for the playback panel. */
    fun diagnostic(value: PlaybackPrepareResponse): String = buildList {
        add(playMethod(value.playMethod))
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
        append(if (value.playMethod.isEmpty()) "Original" else playMethod(value.playMethod))
        if (value.width > 0 && value.height > 0) append(" · ${value.width}×${value.height}")
        if (value.bitrate > 0) append(" · ${Fmt.mbps(value.bitrate.toLong())}")
    }
}

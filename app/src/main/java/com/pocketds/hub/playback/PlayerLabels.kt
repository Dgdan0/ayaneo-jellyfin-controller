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

    /** How the stream will be delivered: method, resolution, bitrate. */
    fun quality(value: PlaybackPrepareResponse): String = buildString {
        append(value.playMethod.ifEmpty { "Original" })
        if (value.width > 0 && value.height > 0) append(" · ${value.width}×${value.height}")
        if (value.bitrate > 0) append(" · ${Fmt.mbps(value.bitrate.toLong())}")
    }
}

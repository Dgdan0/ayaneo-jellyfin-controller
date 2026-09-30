package com.pocketds.hub.state

import java.util.Locale

/**
 * Human-readable numbers, and the only place the app formats them.
 *
 * Pure, and tested, because this is where off-by-1024 errors and negative
 * durations live -- and a download screen showing "-1s left" or "0 B/s" for a
 * healthy transfer undermines confidence in everything else on it.
 *
 * Every screen used to carry its own copy of these, and they drifted: the
 * offline screens printed a 75-minute position as "75:30" while the player
 * said "1:15:30", and sizes rounded differently depending on which tab you
 * were on. A fix here now reaches every caller.
 *
 * Decimals always use [Locale.US]. `String.format` otherwise follows the
 * device locale and writes "1,5 GB" on a German one.
 */
object Fmt {

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long): String {
        if (value <= 0) return "0 B"
        var size = value.toDouble()
        var unit = 0
        while (size >= 1024 && unit < UNITS.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0 || size >= 100) {
            "${size.toInt()} ${UNITS[unit]}"
        } else {
            String.format(Locale.US, "%.1f %s", size, UNITS[unit])
        }
    }

    /** Zero is rendered as a dash: "0 B/s" reads as broken rather than idle. */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "—" else bytes(bytesPerSecond) + "/s"

    /**
     * Time remaining on a transfer.
     *
     * @param seconds -1 when the hub could not estimate, which is common and
     *   must not be rendered as a number.
     */
    fun eta(seconds: Long): String {
        if (seconds < 0) return "—"
        if (seconds < 60) return "${seconds}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h ${minutes % 60}m"
        return "${hours / 24}d ${hours % 24}h"
    }

    fun percent(fraction: Double): String =
        if (fraction < 0) "—" else "${(fraction * 100).toInt()}%"

    /**
     * A position within something playing: "24:05", or "1:15:30" once it
     * passes an hour. Minutes never run past 59.
     */
    fun clock(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1_000
        val hours = total / 3_600
        val minutes = total % 3_600 / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /**
     * How long something is: "24 min", or "2h 5m" from an hour up. Empty when
     * the length is unknown, so a caller building a "·"-joined line can drop it.
     */
    fun runtime(seconds: Long): String {
        if (seconds <= 0) return ""
        val minutes = seconds / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
    }

    /** A stream or file bitrate. Empty when the server did not report one. */
    fun mbps(bitsPerSecond: Long): String =
        if (bitsPerSecond <= 0) "" else String.format(Locale.US, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
}

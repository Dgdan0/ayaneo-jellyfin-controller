package com.pocketds.hub.state

/**
 * How often to ask the hub again.
 *
 * A downloads screen has to feel live without turning the handheld into a
 * space heater or hammering the hub from a phone connection. Four rules, all of
 * which matter:
 *
 *  * **Hidden means stopped.** Not slower -- stopped. A backgrounded screen
 *    polling every ten seconds is pure waste, and it is the single easiest way
 *    to flatten a battery.
 *  * **Fast only while something is moving.** Bytes in flight are worth two
 *    seconds; a queue of stalled torrents is not.
 *  * **Fast for a few seconds after acting.** qBittorrent does not flip a
 *    torrent's state synchronously with the reply to a stop: a refresh fired
 *    immediately afterwards reads back the *old* state. Measured on this stack
 *    the reply came in 11ms and the state had not moved 24ms later. Without a
 *    settling window the screen then sat on a stale row for a full idle
 *    interval, which reads as "the button did nothing".
 *  * **Back off on failure.** If the hub is down, asking five times a minute
 *    will not bring it back, and on this hub five failures earns a ban.
 *  * **Reset on success**, so recovery is immediate rather than serving out the
 *    rest of a long backoff.
 */
object PollSchedule {

    const val ACTIVE_MS = 2_000L
    const val IDLE_MS = 10_000L

    /** 5s, 15s, 30s, then hold. Deliberately short of the hub's ban window. */
    private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 30_000L)

    /** How long to keep polling fast after a stop, start or delete. */
    const val SETTLE_MS = 8_000L

    /**
     * @param settling true for a few seconds after the user acted, so the
     *   service has time to reflect it. Deliberately below the failure check:
     *   an unreachable hub is not going to answer faster because we just
     *   pressed a button.
     * @return the delay before the next poll, or null to stop entirely.
     */
    fun nextDelayMs(
        visible: Boolean,
        anyActive: Boolean,
        consecutiveFailures: Int,
        settling: Boolean = false
    ): Long? {
        if (!visible) return null
        if (consecutiveFailures > 0) {
            val index = (consecutiveFailures - 1).coerceAtMost(BACKOFF_MS.lastIndex)
            return BACKOFF_MS[index]
        }
        return if (anyActive || settling) ACTIVE_MS else IDLE_MS
    }
}

/**
 * Human-readable numbers.
 *
 * Pure, and tested, because this is where off-by-1024 errors and negative
 * durations live -- and a download screen showing "-1s left" or "0 B/s" for a
 * healthy transfer undermines confidence in everything else on it.
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
            String.format("%.1f %s", size, UNITS[unit])
        }
    }

    /** Zero is rendered as a dash: "0 B/s" reads as broken rather than idle. */
    fun speed(bytesPerSecond: Long): String =
        if (bytesPerSecond <= 0) "—" else bytes(bytesPerSecond) + "/s"

    /**
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
}

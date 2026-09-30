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

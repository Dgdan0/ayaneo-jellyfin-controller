package com.pocketds.hub.reader

/**
 * At most one sync every [intervalMs], and the last one asked for always runs
 * (#19, A4: the listening place goes to the hub no faster than every 15
 * seconds). Pure; the caller's clock is any that only moves forward.
 */
class SyncThrottle(private val intervalMs: Long) {
    private var last: Long? = null

    /** How long a sync asked for at [now] waits: none for the first, else until [intervalMs] after the last. */
    fun waitFor(now: Long): Long = last?.let { (it + intervalMs - now).coerceAtLeast(0) } ?: 0

    /** A sync ran at [now]. */
    fun ran(now: Long) { last = now }
}

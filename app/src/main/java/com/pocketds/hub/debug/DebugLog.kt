package com.pocketds.hub.debug

import android.util.Log

/**
 * Always-on ring buffer of the things that actually matter when something
 * misbehaves on the device: HTTP calls and their timings, auth failures, pad
 * events and the actions they resolved to, screen pushes and pops, image cache
 * hits and misses.
 *
 * Lifted from the sibling keyboard project, where the reasoning was that
 * diagnosing a fault shouldn't need a rebuild with hand-added Log.d calls plus
 * a logcat trawl through vendor noise. It applies at least as strongly here:
 * this app is mostly network behaviour, and "why was that slow" is a question
 * you want to answer on the sofa without a cable. The trace is always being
 * recorded and is readable on the device itself. Everything also goes to logcat
 * under a single tag for when a cable is handy.
 *
 * The tag differs from the keyboard project's on purpose, so `dev.sh log` in
 * either project doesn't pick up the other's lines.
 */
object DebugLog {

    const val TAG = "PocketDSHub"
    private const val CAPACITY = 400

    private val entries = ArrayDeque<String>()
    private var startedAt = System.currentTimeMillis()

    /**
     * @param area short subsystem label -- "net", "auth", "pad", "nav", "img",
     *   "cache" -- so a trace can be skimmed for the part that misbehaved.
     */
    @Synchronized
    fun log(area: String, message: String) {
        val seconds = (System.currentTimeMillis() - startedAt) / 1000f
        val line = "%8.2f  %-6s %s".format(seconds, area, message)
        if (entries.size >= CAPACITY) entries.removeFirst()
        entries.addLast(line)
        Log.d(TAG, "$area | $message")
    }

    /** Newest last, ready to render. */
    @Synchronized
    fun snapshot(): List<String> = entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        startedAt = System.currentTimeMillis()
    }
}

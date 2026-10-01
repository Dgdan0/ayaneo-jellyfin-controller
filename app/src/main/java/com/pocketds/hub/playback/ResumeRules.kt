package com.pocketds.hub.playback

/**
 * The one place the app decides whether a position is a resume point, a
 * finished item, or not really started.
 *
 * These are Jellyfin's default thresholds, and the hub applies the same rule
 * with the server's live configuration when it saves a stop
 * (hub/internal/api/watchstate.go, decideWatchPosition). The app had four
 * copies of a different rule -- 30 seconds in and 30 seconds left -- so a film
 * stopped at 92% showed "Resume" on its detail page for two minutes and then
 * flipped to watched once the server's answer took over, and a download
 * offered a resume point the server would never have kept.
 */
object ResumeRules {
    const val MIN_RESUME_PCT = 5.0
    const val MAX_RESUME_PCT = 90.0
    const val MIN_RESUME_DURATION_SECONDS = 300L

    enum class Verdict { NOT_STARTED, RESUME, FINISHED }

    fun judge(positionMillis: Long, durationMillis: Long): Verdict {
        val position = positionMillis.coerceAtLeast(0)
        // With no runtime the hub keeps the position rather than guessing
        // "watched", which would hide the item from Continue watching.
        if (durationMillis <= 0) return if (position > 0) Verdict.RESUME else Verdict.NOT_STARTED
        val percent = position * 100.0 / durationMillis
        return when {
            percent < MIN_RESUME_PCT -> Verdict.NOT_STARTED
            percent > MAX_RESUME_PCT || position >= durationMillis -> Verdict.FINISHED
            durationMillis / 1_000 < MIN_RESUME_DURATION_SECONDS -> Verdict.FINISHED
            else -> Verdict.RESUME
        }
    }

    /** The position to resume from, or 0 to start at the beginning. */
    fun resumePosition(positionMillis: Long, durationMillis: Long): Long =
        if (judge(positionMillis, durationMillis) == Verdict.RESUME) positionMillis else 0L

    /**
     * Watched with a saved position is a rewatch in progress: Jellyfin keeps
     * Played set while a watched episode is watched again, and offers Resume
     * for it. So the position wins -- progress bar, "40% watched", Resume --
     * and the watched tick shows only when there is nothing to resume. Cards
     * used to hide the position of anything watched, so a rewatch sat in
     * Continue watching with a tick and no bar, and its page offered Play.
     */
    fun showsWatched(played: Boolean, progress: Double): Boolean = played && progress <= 0.0

    /** "40% watched", "Watched", or null when there is nothing to say. */
    fun watchLabel(played: Boolean, progress: Double): String? = when {
        progress > 0 -> "${(progress * 100).toInt()}% watched"
        played -> "Watched"
        else -> null
    }

    fun isFinished(positionMillis: Long, durationMillis: Long): Boolean =
        judge(positionMillis, durationMillis) == Verdict.FINISHED
}

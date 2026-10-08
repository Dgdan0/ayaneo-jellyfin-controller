package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackSegment

/** Settings › Playback › Show the next episode. */
enum class NextEpisodeTiming(val label: String) {
    CREDITS("When credits start"),
    BEFORE_END("20 s before the end"),
    NEVER("Never")
}

/**
 * When the up-next card appears near the end of an episode, and which
 * segments get a Skip button.
 *
 * The card's bar fills over [COUNTDOWN_MILLIS] and the next episode starts
 * when it is full: Play now starts it at once, Watch credits puts it away
 * until the video ends.
 */
object UpNext {
    const val LEAD_MILLIS = 20_000L
    const val COUNTDOWN_MILLIS = 8_000L

    /**
     * Where the card appears, or null for never. "When credits start" uses the
     * credits segment when there is one in the second half of the video (an
     * opening "Ending" theme in the first half is not the credits), and 20
     * seconds before the end otherwise.
     */
    fun cardAt(timing: NextEpisodeTiming, segments: List<PlaybackSegment>, durationMillis: Long): Long? {
        if (durationMillis <= LEAD_MILLIS * 2) return null
        val beforeEnd = durationMillis - LEAD_MILLIS
        return when (timing) {
            NextEpisodeTiming.NEVER -> null
            NextEpisodeTiming.BEFORE_END -> beforeEnd
            NextEpisodeTiming.CREDITS -> segments
                .firstOrNull { it.type.equals("Outro", ignoreCase = true) && it.startMillis >= durationMillis / 2 }
                ?.startMillis?.coerceAtMost(beforeEnd) ?: beforeEnd
        }
    }

    fun showsCard(positionMillis: Long, cardAt: Long?, durationMillis: Long): Boolean =
        cardAt != null && positionMillis >= cardAt && positionMillis < durationMillis

    /** The button a segment earns, or null: credits are the up-next card's, not a Skip. */
    fun skipLabel(type: String): String? = when (type.trim().lowercase()) {
        "intro" -> "Skip intro"
        "recap" -> "Skip recap"
        "preview" -> "Skip preview"
        "commercial" -> "Skip ad"
        else -> null
    }

    /** Skipping these happens by itself when Settings › Playback says so. */
    fun skipsAutomatically(type: String): Boolean = type.equals("Intro", true) || type.equals("Recap", true)
}

/**
 * "Still watching?" (#48): the next episode starts by itself, but not for ever.
 * After [limit] episodes in a row have started that way with nobody touching the
 * player, the fourth waits: the player pauses and asks, and any button carries on.
 * Pure, so the count is a test rather than a feeling: an autoplay adds one, and
 * any press, tap or gesture on the player puts the count back to none.
 */
class AutoplayRun(private val limit: Int = LIMIT) {
    private var autoplayed = 0

    /** Episodes that started by themselves since anybody last touched the player. */
    val run: Int get() = autoplayed

    /** Any button, tap or gesture on the player: somebody is there. */
    fun input() { autoplayed = 0 }

    /** The countdown is full: may the next episode start by itself, or is it time to ask? */
    fun mayAutoplay(): Boolean = autoplayed < limit

    /** The next episode has started by itself. */
    fun autoplayed() { autoplayed++ }

    companion object {
        /** Netflix's number: three, then ask. */
        const val LIMIT = 3
        const val QUESTION = "Still watching?"
        const val CONTINUE = "Continue watching"
    }
}

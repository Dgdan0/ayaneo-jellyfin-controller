package com.pocketds.hub.reader

/**
 * The listening controls' arithmetic (#16, A2), pure so JVM tests pin it:
 * the speeds offered, the sleep timer with its fade, the smart rewind when
 * it stops playback, the time left in a part and in the book at the speed
 * playing, and how far through the book a moment is (#30).
 */
object Listening {
    /** The speeds a book plays at: 0.75 to 3, the common steps between. */
    val SPEEDS = listOf(0.75f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

    /** The speed after [speed] in [SPEEDS], round to the start after the last. */
    fun nextSpeed(speed: Float): Float {
        val index = SPEEDS.indexOfFirst { kotlin.math.abs(it - speed) < 0.01f }
        return SPEEDS[(index + 1).mod(SPEEDS.size)]
    }

    fun clampSpeed(speed: Float): Float = speed.coerceIn(SPEEDS.first(), SPEEDS.last())

    /** How long [mediaMs] of a recording takes to hear at [speed]. */
    fun heard(mediaMs: Long, speed: Float): Long = (mediaMs.coerceAtLeast(0) / clampSpeed(speed).toDouble()).toLong()

    /** Left in the part playing, as heard. */
    fun partLeft(positionMs: Long, partMs: Long, speed: Float): Long = heard(partMs - positionMs, speed)

    /**
     * Left in the book, as heard: the rest of [part] and every part after it.
     * Null while any of those parts' lengths is still unknown.
     */
    fun bookLeft(part: Int, positionMs: Long, partsMs: List<Long?>, speed: Float): Long? {
        val rest = partsMs.drop(part.coerceAtLeast(0))
        if (rest.isEmpty() || rest.any { it == null || it <= 0 }) return null
        return heard(rest.sumOf { it!! } - positionMs.coerceAtLeast(0), speed)
    }

    /**
     * How far through the book [positionMs] into [part] is, 0 to 1: the parts before it and the
     * moment in it, over every part. Of the recording, not of the time it takes to hear, so a
     * speed does not move it (Storyteller's `totalProgression` is the same sum). Null while any
     * part's length is unknown or there is no such part: a share nobody could count is not 0.
     */
    fun bookProgress(part: Int, positionMs: Long, partsMs: List<Long?>): Double? {
        if (part !in partsMs.indices || partsMs.any { it == null || it <= 0 }) return null
        val lengths = partsMs.map { it!! }
        val heard = lengths.take(part).sum() + positionMs.coerceIn(0, lengths[part])
        return (heard.toDouble() / lengths.sum()).coerceIn(0.0, 1.0)
    }
}

/** What the sleep timer was set to: minutes of listening, or the end of the part playing. */
sealed interface SleepChoice {
    data class Minutes(val minutes: Int) : SleepChoice
    data object EndOfPart : SleepChoice

    companion object {
        val ALL: List<SleepChoice> = listOf(Minutes(5), Minutes(15), Minutes(30), Minutes(45), Minutes(60), EndOfPart)
    }
}

/**
 * The sleep timer (#16, A2). It counts only while something plays; over its
 * last [FADE_MS] the volume falls to nothing, and when it runs out playback
 * pauses and steps back [SmartRewind.AFTER_SLEEP_MS], so what faded is heard
 * again. Any button while it fades keeps you listening: minutes start again,
 * the end of a part becomes the end of the next.
 */
data class SleepTimer(val choice: SleepChoice, val remainingMs: Long, val skipParts: Int = 0) {
    /** Faded and pausing now. */
    val runsOut: Boolean get() = remainingMs <= 0

    /** In its last [FADE_MS]. */
    val fading: Boolean get() = remainingMs in 1 until FADE_MS

    /**
     * Stops as the part playing ends: the player moving on to the next part
     * is the moment, however the last half second fell between two ticks.
     */
    val endsWithPart: Boolean get() = choice == SleepChoice.EndOfPart && skipParts == 0

    /** How loud while it counts: full, then falling over the fade. */
    val volume: Float get() = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

    /** [elapsedMs] of listening later; a minutes timer counts down, the end of a part is read from the player. */
    fun tick(elapsedMs: Long, partLeftHeardMs: Long): SleepTimer = when (choice) {
        is SleepChoice.Minutes -> copy(remainingMs = remainingMs - elapsedMs.coerceAtLeast(0))
        SleepChoice.EndOfPart -> copy(remainingMs = if (skipParts > 0) remainingMs - elapsedMs.coerceAtLeast(0) else partLeftHeardMs)
    }

    /** A button while it fades: carry on listening. */
    fun extended(partLeftHeardMs: Long, nextPartHeardMs: Long?): SleepTimer = when (choice) {
        is SleepChoice.Minutes -> copy(remainingMs = choice.minutes * 60_000L)
        // Still awake at the end of this part: stop at the end of the next one.
        SleepChoice.EndOfPart -> copy(remainingMs = partLeftHeardMs + (nextPartHeardMs ?: 0L), skipParts = skipParts + 1)
    }

    /** A part changed under a timer carried past it: count to the end of the new one. */
    fun partChanged(partLeftHeardMs: Long): SleepTimer =
        if (choice == SleepChoice.EndOfPart && skipParts > 0) copy(remainingMs = partLeftHeardMs, skipParts = skipParts - 1) else this

    companion object {
        const val FADE_MS = 30_000L

        fun start(choice: SleepChoice, partLeftHeardMs: Long): SleepTimer = when (choice) {
            is SleepChoice.Minutes -> SleepTimer(choice, choice.minutes * 60_000L)
            SleepChoice.EndOfPart -> SleepTimer(choice, partLeftHeardMs)
        }
    }
}

/** Where to pick up after the sleep timer stops playback: back over what faded. */
object SmartRewind {
    const val AFTER_SLEEP_MS = SleepTimer.FADE_MS

    fun afterSleep(positionMs: Long): Long = (positionMs - AFTER_SLEEP_MS).coerceAtLeast(0)
}

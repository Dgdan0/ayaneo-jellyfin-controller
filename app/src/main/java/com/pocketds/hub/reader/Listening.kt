package com.pocketds.hub.reader

/**
 * The listening controls' arithmetic (#16, A2), pure so JVM tests pin it:
 * the speeds offered, the sleep timer with its fade, the smart rewind when
 * it stops playback, the time left in a part and in the book at the speed
 * playing, how far through the book a moment is (#30), and the recording
 * between two places or a jump from one, counted across the parts (#31): a
 * chapter of the book's own can start in one part and run on into the next.
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

    /**
     * How much of the recording lies from [fromMs] into [fromPart] to a later place, [toMs]
     * into [toPart], across the parts between (#31): the rest of the first, every part after
     * it up to the last, and the start of that one. Null when the place comes first, and
     * while the length of a part it crosses is unknown or none: nothing to say, which is not 0.
     * Within one part no length is needed.
     */
    fun distance(fromPart: Int, fromMs: Long, toPart: Int, toMs: Long, partsMs: List<Long?>): Long? {
        if (fromPart == toPart) return if (toMs >= fromMs) toMs - fromMs else null
        if (fromPart > toPart || fromPart < 0 || toPart > partsMs.size) return null
        var total = toMs - fromMs
        for (part in fromPart until toPart) {
            val length = partsMs[part]?.takeIf { it > 0 } ?: return null
            total += length
        }
        return total.takeIf { it >= 0 }
    }

    /**
     * A jump by [deltaMs] of recording from [positionMs] into [part], across the parts: back
     * into the part before, on into the next, and no further than a length that is not known
     * yet (the part playing is the one the player knows, [currentPartMs]); going back, the
     * start of the part after it is as far as it can count. The same walk the transport's
     * jumps make, the step back over what faded, and where a moment in a chapter that spans
     * parts is a place.
     */
    fun jump(part: Int, positionMs: Long, deltaMs: Long, partsMs: List<Long?>, currentPartMs: Long? = null): Pair<Int, Long> {
        var target = part
        var offset = positionMs + deltaMs
        while (offset < 0 && target > 0) {
            // Counting back through a part whose length is not known would land anywhere, the book's start among it.
            val length = partsMs.getOrNull(target - 1)?.takeIf { it > 0 } ?: return target to 0L
            target--
            offset += length
        }
        while (target < partsMs.lastIndex) {
            val length = partsMs[target]?.takeIf { it > 0 } ?: currentPartMs?.takeIf { target == part && it > 0 } ?: break
            if (offset < length) break
            offset -= length; target++
        }
        return target to offset.coerceAtLeast(0)
    }
}

/**
 * What the sleep timer was set to: minutes of listening, or the end of what is playing, the
 * chapter where the book has chapters (#31), else the part.
 */
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
 *
 * "The end of a part" is the end of the entry playing, a chapter where the book
 * has chapters and else a part (#31): [entry] is its place in the contents, so the
 * timer knows when what it counts to has changed under it.
 */
data class SleepTimer(val choice: SleepChoice, val remainingMs: Long, val skipParts: Int = 0, val entry: Int = NO_ENTRY) {
    /** Faded and pausing now. */
    val runsOut: Boolean get() = remainingMs <= 0

    /** In its last [FADE_MS]. */
    val fading: Boolean get() = remainingMs in 1 until FADE_MS

    /**
     * Stops as the entry playing ends: where it ends with its part, the player moving on to
     * the next part is the moment, however the last half second fell between two ticks.
     */
    val endsWithPart: Boolean get() = choice == SleepChoice.EndOfPart && skipParts == 0

    /** How loud while it counts: full, then falling over the fade. */
    val volume: Float get() = (remainingMs.toFloat() / FADE_MS).coerceIn(0f, 1f)

    /**
     * [elapsedMs] of listening later, with [entryLeftHeardMs] left of the entry playing (a
     * chapter, else a part) and [entry] its place in the contents. A minutes timer counts
     * down; the end of an entry is read from the player.
     *
     * A chapter ends between two ticks, nowhere the player reports (a chapter inside a file
     * has no event), so the timer remembers which entry it counted to: finding itself in a
     * later one when it was within a tick of the end, it has played on past it, and it is
     * asleep. Any other move, a jump to another chapter or back, is followed.
     */
    fun tick(elapsedMs: Long, entryLeftHeardMs: Long, entry: Int = this.entry): SleepTimer {
        val elapsed = elapsedMs.coerceAtLeast(0)
        return when (choice) {
            is SleepChoice.Minutes -> copy(remainingMs = remainingMs - elapsed)
            SleepChoice.EndOfPart -> {
                val moved = this.entry != NO_ENTRY && entry != this.entry
                when {
                    // Carried past an end: it counts itself down until the entry it was carried to begins, then to that one's end.
                    skipParts > 0 -> if (moved) partChanged(entryLeftHeardMs).copy(entry = entry) else copy(remainingMs = remainingMs - elapsed)
                    moved && entry > this.entry && remainingMs <= elapsed + CROSSED_SLACK_MS -> copy(remainingMs = 0, entry = entry)
                    else -> copy(remainingMs = entryLeftHeardMs, entry = entry)
                }
            }
        }
    }

    /** A button while it fades: carry on listening. */
    fun extended(entryLeftHeardMs: Long, nextEntryHeardMs: Long?): SleepTimer = when (choice) {
        is SleepChoice.Minutes -> copy(remainingMs = choice.minutes * 60_000L)
        // Still awake at the end of this entry: stop at the end of the next one.
        SleepChoice.EndOfPart -> copy(remainingMs = entryLeftHeardMs + (nextEntryHeardMs ?: 0L), skipParts = skipParts + 1)
    }

    /** The entry changed under a timer carried past it: count to the end of the new one. */
    fun partChanged(entryLeftHeardMs: Long): SleepTimer =
        if (choice == SleepChoice.EndOfPart && skipParts > 0) copy(remainingMs = entryLeftHeardMs, skipParts = skipParts - 1) else this

    companion object {
        const val FADE_MS = 30_000L
        /** The timer has not been told which entry it counts to. */
        const val NO_ENTRY = -1
        /** Within this of the end at the last tick, or in a later entry now: the end passed between the ticks. */
        const val CROSSED_SLACK_MS = 1_000L

        fun start(choice: SleepChoice, entryLeftHeardMs: Long, entry: Int = NO_ENTRY): SleepTimer = when (choice) {
            is SleepChoice.Minutes -> SleepTimer(choice, choice.minutes * 60_000L, entry = entry)
            SleepChoice.EndOfPart -> SleepTimer(choice, entryLeftHeardMs, entry = entry)
        }
    }
}

/** Where to pick up after the sleep timer stops playback: back over what faded. */
object SmartRewind {
    const val AFTER_SLEEP_MS = SleepTimer.FADE_MS

    /**
     * [positionMs] into [part], back by [AFTER_SLEEP_MS] of recording: into the part before when
     * the part began less than that ago (a chapter can end just past a track's start, and what
     * faded was in the track before).
     */
    fun afterSleep(part: Int, positionMs: Long, partsMs: List<Long?>): Pair<Int, Long> =
        Listening.jump(part, positionMs, -AFTER_SLEEP_MS, partsMs)
}

package com.pocketds.hub.reader

import com.pocketds.hub.state.Fmt
import java.util.Locale

/**
 * How fast this person reads a book (#18, E3), learnt from the reading itself,
 * for "12 min left in chapter · 4h 10m in book".
 *
 * Readium measures a book in positions: one per 1,024 bytes of each of its
 * files as stored in the EPUB, so a position is an amount of text, about 400
 * words of a novel. Reading on adds the text read and the time it took: a
 * page turned, or a stretch scrolled, once [MIN_STEP_MS] has passed. A move
 * back, a jump (the contents, a search, a chapter key: more than
 * [MAX_STEP_POSITIONS] at once), a long pause (the book put down) or a flick
 * through pages faster than anyone reads adds nothing.
 *
 * The pace leans on [PRIOR_POSITIONS] positions read at a prior pace, the
 * person's pace over every book or [DEFAULT_MINUTES_PER_POSITION] before
 * there is one, so the first pages of a book do not swing it wildly; the
 * book's own pace wins as it is read. Only the most recent
 * [WINDOW_POSITIONS] count, so the pace follows a change of font size.
 *
 * Pure: positions and milliseconds in, a pace out. [ReadingPace.Tracker]
 * keeps the place and the time the reading was last measured from.
 */
data class ReadingPace(val positions: Double = 0.0, val minutes: Double = 0.0) {

    /** [elapsedMs] spent reading from [fromPosition] on to [toPosition]: counted when it is plausible reading. */
    fun observe(fromPosition: Double, toPosition: Double, elapsedMs: Long): ReadingPace {
        val advanced = toPosition - fromPosition
        if (advanced.isNaN() || advanced <= 0.0 || advanced > MAX_STEP_POSITIONS) return this
        if (elapsedMs < MIN_STEP_MS || elapsedMs > MAX_STEP_MS) return this
        val spent = elapsedMs / 60_000.0
        if (advanced / spent > MAX_POSITIONS_PER_MINUTE) return this
        val next = ReadingPace(positions + advanced, minutes + spent)
        return if (next.positions <= WINDOW_POSITIONS) next
            else ReadingPace(WINDOW_POSITIONS, next.minutes * WINDOW_POSITIONS / next.positions)
    }

    /** Minutes a position takes: what was read, leaning on [prior] until there is enough of it. */
    fun minutesPerPosition(prior: Double = DEFAULT_MINUTES_PER_POSITION): Double =
        (minutes + prior * PRIOR_POSITIONS) / (positions + PRIOR_POSITIONS)

    /** Read in this book and in another, as one: the pace over every book. */
    operator fun plus(other: ReadingPace): ReadingPace = ReadingPace(positions + other.positions, minutes + other.minutes)

    fun encode(): String = String.format(Locale.US, "%.4f|%.4f", positions, minutes)

    /** A stretch read: from [fromPosition] on to [toPosition] in [elapsedMs]. [observe] judges it. */
    data class Reading(val fromPosition: Double, val toPosition: Double, val elapsedMs: Long)

    /**
     * Where the reading was last measured from: [at] each new place in the
     * book, with the time, gives the stretch read since, once there has been
     * time enough to judge it ([MIN_STEP_MS]). A move back, a jump or a pause
     * beyond [MAX_STEP_MS] starts again from the new place.
     */
    class Tracker {
        private var fromPosition = Double.NaN
        private var fromMs = 0L

        fun at(position: Double, nowMs: Long): Reading? {
            if (fromPosition.isNaN() || position < fromPosition || nowMs - fromMs > MAX_STEP_MS ||
                position - fromPosition > MAX_STEP_POSITIONS) {
                restart(position, nowMs)
                return null
            }
            // A little scrolled, or a page turned quickly: wait until there is time enough to judge.
            if (nowMs - fromMs < MIN_STEP_MS) return null
            val reading = Reading(fromPosition, position, nowMs - fromMs)
            restart(position, nowMs)
            return reading
        }

        /** The book shows again (or opened somewhere new): the time away is not reading. */
        fun restart(position: Double = Double.NaN, nowMs: Long = 0L) {
            fromPosition = position
            fromMs = nowMs
        }
    }

    /** [reading] counted, if it is plausible reading: see [observe]. */
    fun observe(reading: Reading): ReadingPace = observe(reading.fromPosition, reading.toPosition, reading.elapsedMs)

    companion object {
        /** About 400 words a position, at 250 words a minute. */
        const val DEFAULT_MINUTES_PER_POSITION = 1.6
        const val PRIOR_POSITIONS = 6.0
        const val WINDOW_POSITIONS = 120.0
        /** More at once than a page or two is a jump, not reading. */
        const val MAX_STEP_POSITIONS = 4.0
        const val MIN_STEP_MS = 4_000L
        /** Longer on one stretch than this is a pause. */
        const val MAX_STEP_MS = 8 * 60_000L
        /** About 1,600 words a minute: flicking through, not reading. */
        const val MAX_POSITIONS_PER_MINUTE = 4.0

        fun decode(raw: String?): ReadingPace {
            val parts = raw?.split('|') ?: return ReadingPace()
            val positions = parts.getOrNull(0)?.toDoubleOrNull() ?: return ReadingPace()
            val minutes = parts.getOrNull(1)?.toDoubleOrNull() ?: return ReadingPace()
            if (positions < 0.0 || minutes < 0.0 || positions.isNaN() || minutes.isNaN()) return ReadingPace()
            return ReadingPace(positions, minutes)
        }
    }
}

/**
 * How long is left, in the chapter and in the book (#18, E3): from the pace
 * over Readium's positions, or, reading along, from the narration itself.
 */
data class TimeLeft(val chapterMs: Long, val bookMs: Long) {
    /** "12 min left in chapter · 4h 10m in book"; a minute at least, as the audiobook's line. */
    fun label(): String = "${chapterLabel()} · ${runtime(bookMs)} in book"

    /** "12 min left in chapter": the menu's line and a page's corner say it the same way (#42). */
    fun chapterLabel(): String = "${runtime(chapterMs)} left in chapter"

    /** "4h 10m left in book". */
    fun bookLabel(): String = "${runtime(bookMs)} left in book"

    private fun runtime(ms: Long): String = Fmt.runtime((ms / 1_000).coerceAtLeast(60))

    companion object {
        /**
         * The positions in each part of the book ([sectionSizes], in reading
         * order), the part on screen and how far through it: what is left at
         * [minutesPerPosition]. Null before the book's positions are known.
         */
        fun ofPositions(sectionSizes: List<Int>, section: Int, progression: Double, minutesPerPosition: Double): TimeLeft? {
            val size = sectionSizes.getOrNull(section) ?: return null
            if (minutesPerPosition <= 0.0 || minutesPerPosition.isNaN()) return null
            val inChapter = size * (1.0 - progression.coerceIn(0.0, 1.0))
            val after = sectionSizes.drop(section + 1).sum()
            val perPosition = minutesPerPosition * 60_000.0
            return TimeLeft((inChapter * perPosition).toLong(), ((inChapter + after) * perPosition).toLong())
        }

        /** Where in the whole book, in positions: the parts before and the way through this one. */
        fun position(sectionSizes: List<Int>, section: Int, progression: Double): Double? {
            val size = sectionSizes.getOrNull(section) ?: return null
            return sectionSizes.take(section).sum() + size * progression.coerceIn(0.0, 1.0)
        }

        /**
         * Reading along: what the narration has left to say, as heard at
         * [speed]. The chapter is the part of the book holding the sentence
         * being read; null when the narration is between sentences it knows.
         */
        fun ofNarration(timeline: ReadAlongTimeline, position: ReadAlongPosition, speed: Float): TimeLeft? {
            val track = timeline.tracks.getOrNull(position.track) ?: return null
            val now = track.startMs + position.offsetMs.coerceAtLeast(0)
            val chapter = (timeline.active(position.track, position.offsetMs) ?: track.segments.firstOrNull { it.endMs > now })?.textHref
                ?: return null
            var inChapter = 0L
            var inBook = 0L
            timeline.tracks.forEachIndexed { index, value ->
                if (index < position.track) return@forEachIndexed
                value.segments.forEach { segment ->
                    val from = if (index == position.track) maxOf(segment.beginMs, now) else segment.beginMs
                    val left = segment.endMs - from
                    if (left <= 0) return@forEach
                    inBook += left
                    if (segment.textHref == chapter) inChapter += left
                }
            }
            return TimeLeft(Listening.heard(inChapter, speed), Listening.heard(inBook, speed))
        }
    }
}

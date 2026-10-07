package com.pocketds.hub.reader

import com.pocketds.hub.playback.PlayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningTest {
    @Test fun `one sound at a time`() {
        val arbiter = AudioArbiter()
        assertEquals(emptySet<AudioSource>(), arbiter.start(AudioSource.AUDIOBOOK))
        // Video starts: the audiobook pauses.
        assertEquals(setOf(AudioSource.AUDIOBOOK), arbiter.start(AudioSource.VIDEO))
        assertEquals(setOf(AudioSource.VIDEO), arbiter.sounding)
        // Narration over video: the video pauses.
        assertEquals(setOf(AudioSource.VIDEO), arbiter.start(AudioSource.NARRATION))
        // A player restarting itself pauses nothing.
        assertEquals(emptySet<AudioSource>(), arbiter.start(AudioSource.NARRATION))
        arbiter.stop(AudioSource.NARRATION)
        assertEquals(emptySet<AudioSource>(), arbiter.sounding)
        assertEquals(emptySet<AudioSource>(), arbiter.start(AudioSource.AUDIOBOOK))
        // A stop for something not playing changes nothing.
        arbiter.stop(AudioSource.VIDEO)
        assertEquals(setOf(AudioSource.AUDIOBOOK), arbiter.sounding)
    }

    @Test fun `speeds from three quarters to three`() {
        assertEquals(1.1f, Listening.nextSpeed(1f))
        assertEquals(0.75f, Listening.nextSpeed(3f))
        assertEquals(0.75f, Listening.nextSpeed(1.3f))
        assertEquals(3f, Listening.clampSpeed(9f))
        assertEquals(0.75f, Listening.clampSpeed(0.2f))
    }

    @Test fun `time left is as heard at the speed playing`() {
        assertEquals(30_000L, Listening.heard(60_000 - 30_000, 1f))
        assertEquals(15_000L, Listening.heard(60_000 - 30_000, 2f))
        // The rest of part 1 and all of part 2, at one and a half.
        assertEquals(60_000L, Listening.bookLeft(1, 30_000, listOf(60_000L, 60_000L, 60_000L), 1.5f))
        // A length still being read: no book total yet.
        assertNull(Listening.bookLeft(0, 0, listOf(60_000L, null), 1f))
        assertEquals("1 min left in part · 2h 5m in book", PlayerLabels.timeLeft(59_000, 7_500_000))
        assertEquals("12 min left in part", PlayerLabels.timeLeft(12 * 60_000L, null))
    }

    @Test fun `how far through the book is of the recording, whatever the speed`() {
        val parts = listOf<Long?>(600_000, 1_200_000, 300_000)
        assertEquals(0.0, Listening.bookProgress(0, 0, parts)!!, 1e-9)
        // The first part and five minutes of the second, of thirty-five minutes in all.
        assertEquals(900_000.0 / 2_100_000, Listening.bookProgress(1, 300_000, parts)!!, 1e-9)
        assertEquals(1.0, Listening.bookProgress(2, 300_000, parts)!!, 1e-9)
        // A player runs a little past a part's length, and may report a moment before its start.
        assertEquals(1.0, Listening.bookProgress(2, 999_999, parts)!!, 1e-9)
        assertEquals(600_000.0 / 2_100_000, Listening.bookProgress(1, -5, parts)!!, 1e-9)
        // A length still being read, or a part the book does not have: nothing to say, which is not 0.
        assertNull(Listening.bookProgress(0, 0, listOf(600_000L, null)))
        assertNull(Listening.bookProgress(0, 0, listOf(600_000L, 0L)))
        assertNull(Listening.bookProgress(3, 0, parts))
        assertNull(Listening.bookProgress(-1, 0, parts))
        assertNull(Listening.bookProgress(0, 0, emptyList()))
    }

    @Test fun `a minutes timer counts while playing, fades over its last half minute and runs out`() {
        var timer = SleepTimer.start(SleepChoice.Minutes(15), entryLeftHeardMs = 0)
        assertEquals(15 * 60_000L, timer.remainingMs)
        assertEquals(1f, timer.volume)
        timer = timer.tick(15 * 60_000L - 15_000, entryLeftHeardMs = 999_999)
        assertTrue(timer.fading)
        assertEquals(0.5f, timer.volume, 0.001f)
        assertEquals("Sleep · fading", PlayerLabels.sleep(timer))
        // A button while it fades: the fifteen minutes start again.
        timer = timer.extended(entryLeftHeardMs = 0, nextEntryHeardMs = null)
        assertEquals(15 * 60_000L, timer.remainingMs)
        assertEquals("Sleep · 15:00", PlayerLabels.sleep(timer))
        timer = timer.tick(16 * 60_000L, 0)
        assertTrue(timer.runsOut)
        assertEquals(0f, timer.volume)
    }

    @Test fun `the end of a part is read from the player, and a button carries it to the next part's end`() {
        var timer = SleepTimer.start(SleepChoice.EndOfPart, entryLeftHeardMs = 90_000)
        assertEquals("Sleep · end of part", PlayerLabels.sleep(timer))
        // The player moving on is the moment it stops, not a tick that might fall after it.
        assertTrue(timer.endsWithPart)
        assertFalse(SleepTimer.start(SleepChoice.Minutes(5), 0).endsWithPart)
        timer = timer.tick(1_000, entryLeftHeardMs = 20_000)
        assertTrue(timer.fading)
        timer = timer.extended(entryLeftHeardMs = 20_000, nextEntryHeardMs = 300_000)
        assertEquals(320_000L, timer.remainingMs)
        assertEquals(1, timer.skipParts)
        assertFalse(timer.endsWithPart)
        assertFalse(timer.fading)
        // While carried, it counts down itself rather than reading this part's end.
        timer = timer.tick(10_000, entryLeftHeardMs = 10_000)
        assertEquals(310_000L, timer.remainingMs)
        // The next part begins: count to its end again.
        timer = timer.partChanged(entryLeftHeardMs = 300_000)
        assertEquals(0, timer.skipParts)
        assertTrue(timer.endsWithPart)
        assertEquals(300_000L, timer.remainingMs)
        assertEquals("End of this part", PlayerLabels.sleepChoice(SleepChoice.EndOfPart))
        assertEquals("1 hour", PlayerLabels.sleepChoice(SleepChoice.Minutes(60)))
        assertEquals("Sleep", PlayerLabels.sleep(null))
    }

    @Test fun `stopping for sleep steps back over what faded, into the part before when it began less than that ago`() {
        val lengths = listOf<Long?>(600_000, 1_200_000)
        assertEquals(1 to 90_000L, SmartRewind.afterSleep(1, 120_000, lengths))
        assertEquals(0 to 0L, SmartRewind.afterSleep(0, 10_000, lengths))
        // A chapter that ends five seconds into a track: what faded was in the track before it (#31).
        assertEquals(0 to 575_000L, SmartRewind.afterSleep(1, 5_000, lengths))
        // A length it cannot count back through: as far as the start of the part.
        assertEquals(1 to 0L, SmartRewind.afterSleep(1, 5_000, listOf(null, 1_200_000L)))
        assertEquals(1 to 0L, SmartRewind.afterSleep(1, 5_000, emptyList()))
    }

    // ------------------------------------------------------------------ across the parts (#31)

    private val parts = listOf<Long?>(600_000, 1_200_000, 300_000)

    @Test fun `the recording between two places counts the parts between them`() {
        assertEquals(5_000L, Listening.distance(1, 10_000, 1, 15_000, parts))
        assertEquals(0L, Listening.distance(1, 10_000, 1, 10_000, parts))
        // The rest of the first, all of the second, and the start of the third.
        assertEquals(590_000L + 1_200_000 + 20_000, Listening.distance(0, 10_000, 2, 20_000, parts))
        assertEquals(1_200_000L, Listening.distance(1, 0, 2, 0, parts))
        // Not backwards.
        assertNull(Listening.distance(1, 15_000, 1, 10_000, parts))
        assertNull(Listening.distance(2, 0, 1, 0, parts))
        // A length it needs not known: nothing to say, which is not 0. Within one part, none is needed.
        assertNull(Listening.distance(0, 10_000, 2, 20_000, listOf(600_000L, null, 300_000L)))
        assertNull(Listening.distance(0, 10_000, 1, 20_000, listOf(0L, 1_200_000L, 300_000L)))
        assertEquals(5_000L, Listening.distance(1, 10_000, 1, 15_000, listOf(null, null, null)))
    }

    @Test fun `a jump goes across the parts, back into the one before and on into the next`() {
        assertEquals(0 to 595_000L, Listening.jump(0, 580_000, 15_000, parts))
        // On into the next part, and back into the one before.
        assertEquals(1 to 5_000L, Listening.jump(0, 595_000, 10_000, parts))
        assertEquals(0 to 595_000L, Listening.jump(1, 5_000, -10_000, parts))
        // Over a whole part.
        assertEquals(2 to 100_000L, Listening.jump(1, 1_100_000, 200_000, parts))
        // Back over a whole part, and the start of the one before.
        assertEquals(0 to 599_000L, Listening.jump(2, 1_000, -1_202_000, parts))
        // Not before the book's start, and not through a part whose length is not known.
        assertEquals(0 to 0L, Listening.jump(0, 5_000, -15_000, parts))
        assertEquals(1 to 0L, Listening.jump(1, 5_000, -10_000, listOf(null, 1_200_000L, 300_000L)))
        assertEquals(2 to 0L, Listening.jump(2, 5_000, -1_500_000, listOf(600_000L, null, 300_000L)))
        // A length not yet known stops it, unless it is the part playing, which the player knows.
        assertEquals(0 to 700_000L, Listening.jump(0, 650_000, 50_000, listOf(null, 1_200_000L, 300_000L)))
        assertEquals(1 to 100_000L, Listening.jump(0, 650_000, 50_000, listOf(null, 1_200_000L, 300_000L), currentPartMs = 600_000))
    }

    // ------------------------------------------------------------------ the sleep timer by the chapter (#31)

    /** A listener's place, counted as the player would: what is left of the chapter playing, as heard, and which it is. */
    private class Book(val entries: List<AudiobookContents.Entry>, val lengths: List<Long?>) {
        fun span(part: Int, position: Long) = AudiobookContents.span(entries, part, position, lengths[part]!!, lengths)
        fun left(part: Int, position: Long, speed: Float = 1f) = Listening.heard(span(part, position).leftMs, speed)
        fun entry(part: Int, position: Long) = span(part, position).entry
    }

    // Three tracks of 100 seconds, and a chapter that starts 30 seconds before the first track's end and ends 30 seconds into the next.
    private val tracks = listOf<Long?>(100_000, 100_000, 100_000)
    private val book = Book(
        AudiobookContents.entries(tracks.mapIndexed { i, ms -> AudiobookPart("Track $i", uri = "u$i", durationMs = ms) }, tracks, listOf(
            com.pocketds.hub.model.ReadingAudioChapter("One", 0, 0, "book"), com.pocketds.hub.model.ReadingAudioChapter("Two", 70_000, 0, "book"),
            com.pocketds.hub.model.ReadingAudioChapter("Three", 30_000, 1, "book"))), tracks)

    @Test fun `the end of a chapter that runs into the next track is the chapter's end, not the track's`() {
        // Listening to Two from 80 seconds into the first track: 50 seconds to its end, 30 seconds into the second.
        var part = 0
        var position = 80_000L
        var timer = SleepTimer.start(SleepChoice.EndOfPart, book.left(part, position), book.entry(part, position))
        assertEquals(50_000L, timer.remainingMs)
        assertEquals(1, timer.entry)
        var ticks = 0
        var stoppedAt: Pair<Int, Long>? = null
        var leftAtTheBoundary = Long.MAX_VALUE
        while (stoppedAt == null && ticks++ < 1_000) {
            // Half a second of listening; the player goes on into the next track as the first one ends.
            position += 500
            if (part == 0 && position >= 100_000) { part = 1; position -= 100_000 }
            if (part == 1 && position == 0L) leftAtTheBoundary = book.left(part, position)
            timer = timer.tick(500, book.left(part, position), book.entry(part, position))
            if (timer.runsOut) stoppedAt = part to position
        }
        // It did not stop with the first track, which is where the part's end would have been.
        assertEquals(30_000L, leftAtTheBoundary)
        assertEquals(1 to 30_000L, stoppedAt)
    }

    @Test fun `a chapter that ended between two ticks is the end, and a move to another chapter is followed`() {
        // Half a second from the end at the last tick, and now in the next chapter: it ran out.
        val about = SleepTimer(SleepChoice.EndOfPart, remainingMs = 300, entry = 1)
        val ran = about.tick(500, 1_450_000, entry = 2)
        assertTrue(ran.runsOut)
        // The same move, a long way from the end: a jump to the next chapter, so it counts to that one's end.
        val far = SleepTimer(SleepChoice.EndOfPart, remainingMs = 600_000, entry = 1)
        val followed = far.tick(500, 1_450_000, entry = 2)
        assertFalse(followed.runsOut)
        assertEquals(1_450_000L, followed.remainingMs)
        assertEquals(2, followed.entry)
        // Back to an earlier chapter, even from the last moment of this one.
        val back = about.tick(500, 900_000, entry = 0)
        assertFalse(back.runsOut)
        assertEquals(900_000L, back.remainingMs)
        // A timer that has not been told which chapter it is in learns it from the first tick.
        val unknown = SleepTimer(SleepChoice.EndOfPart, remainingMs = 300)
        assertEquals(-1, unknown.entry)
        val learnt = unknown.tick(500, 1_450_000, entry = 3)
        assertFalse(learnt.runsOut)
        assertEquals(3, learnt.entry)
        // Minutes do not care which chapter it is.
        val minutes = SleepTimer(SleepChoice.Minutes(5), remainingMs = 100_000, entry = 1).tick(500, 1_450_000, entry = 2)
        assertEquals(99_500L, minutes.remainingMs)
    }

    @Test fun `a button carries the end of the chapter on to the next chapter's end`() {
        var timer = SleepTimer.start(SleepChoice.EndOfPart, entryLeftHeardMs = 20_000, entry = 1)
        assertTrue(timer.fading)
        timer = timer.extended(entryLeftHeardMs = 20_000, nextEntryHeardMs = 300_000)
        assertEquals(320_000L, timer.remainingMs)
        assertEquals(1, timer.skipParts)
        // While carried it counts itself, whichever track the player is in.
        timer = timer.tick(10_000, entryLeftHeardMs = 10_000, entry = 1)
        assertEquals(310_000L, timer.remainingMs)
        assertFalse(timer.endsWithPart)
        // The next chapter begins: it counts to that one's end.
        timer = timer.tick(500, entryLeftHeardMs = 295_000, entry = 2)
        assertEquals(295_000L, timer.remainingMs)
        assertEquals(0, timer.skipParts)
        assertEquals(2, timer.entry)
        assertTrue(timer.endsWithPart)
    }

    @Test fun `the words say chapter where the book has chapters, and part where it does not`() {
        assertEquals("12 min left in chapter · 4h 10m in book", PlayerLabels.timeLeft(12 * 60_000L, 15_000_000L, "chapter"))
        assertEquals("12 min left in chapter", PlayerLabels.timeLeft(12 * 60_000L, null, "chapter"))
        assertEquals("12 min left in part · 4h 10m in book", PlayerLabels.timeLeft(12 * 60_000L, 15_000_000L, "part"))
        assertEquals("1 min left in chapter", PlayerLabels.timeLeft(10_000, null, "chapter"))
        val timer = SleepTimer.start(SleepChoice.EndOfPart, 90_000)
        assertEquals("Sleep · end of chapter", PlayerLabels.sleep(timer, "chapter"))
        assertEquals("Sleep · end of part", PlayerLabels.sleep(timer))
        assertEquals("Sleep", PlayerLabels.sleep(null, "chapter"))
        assertEquals("Sleep · fading", PlayerLabels.sleep(timer.tick(1_000, 20_000), "chapter"))
        // A carried one counts down, in either wording.
        assertEquals("Sleep · 5:20", PlayerLabels.sleep(timer.extended(20_000, 300_000), "chapter"))
        assertEquals("End of this chapter", PlayerLabels.sleepChoice(SleepChoice.EndOfPart, "chapter"))
        assertEquals("End of this part", PlayerLabels.sleepChoice(SleepChoice.EndOfPart, "part"))
        assertEquals("15 minutes", PlayerLabels.sleepChoice(SleepChoice.Minutes(15), "chapter"))
        assertEquals("1 hour", PlayerLabels.sleepChoice(SleepChoice.Minutes(60), "chapter"))
    }

    @Test fun `the mini player says how long is left of the book, and which chapter it is in`() {
        assertEquals("4h 10m left", PlayerLabels.leftLine(15_000_000L))
        assertEquals("1 min left", PlayerLabels.leftLine(4_000))
        assertEquals("", PlayerLabels.leftLine(null))
    }
}

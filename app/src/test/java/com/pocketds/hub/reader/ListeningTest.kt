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
        assertEquals(30_000L, Listening.partLeft(30_000, 60_000, 1f))
        assertEquals(15_000L, Listening.partLeft(30_000, 60_000, 2f))
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
        var timer = SleepTimer.start(SleepChoice.Minutes(15), partLeftHeardMs = 0)
        assertEquals(15 * 60_000L, timer.remainingMs)
        assertEquals(1f, timer.volume)
        timer = timer.tick(15 * 60_000L - 15_000, partLeftHeardMs = 999_999)
        assertTrue(timer.fading)
        assertEquals(0.5f, timer.volume, 0.001f)
        assertEquals("Sleep · fading", PlayerLabels.sleep(timer))
        // A button while it fades: the fifteen minutes start again.
        timer = timer.extended(partLeftHeardMs = 0, nextPartHeardMs = null)
        assertEquals(15 * 60_000L, timer.remainingMs)
        assertEquals("Sleep · 15:00", PlayerLabels.sleep(timer))
        timer = timer.tick(16 * 60_000L, 0)
        assertTrue(timer.runsOut)
        assertEquals(0f, timer.volume)
    }

    @Test fun `the end of a part is read from the player, and a button carries it to the next part's end`() {
        var timer = SleepTimer.start(SleepChoice.EndOfPart, partLeftHeardMs = 90_000)
        assertEquals("Sleep · end of part", PlayerLabels.sleep(timer))
        // The player moving on is the moment it stops, not a tick that might fall after it.
        assertTrue(timer.endsWithPart)
        assertFalse(SleepTimer.start(SleepChoice.Minutes(5), 0).endsWithPart)
        timer = timer.tick(1_000, partLeftHeardMs = 20_000)
        assertTrue(timer.fading)
        timer = timer.extended(partLeftHeardMs = 20_000, nextPartHeardMs = 300_000)
        assertEquals(320_000L, timer.remainingMs)
        assertEquals(1, timer.skipParts)
        assertFalse(timer.endsWithPart)
        assertFalse(timer.fading)
        // While carried, it counts down itself rather than reading this part's end.
        timer = timer.tick(10_000, partLeftHeardMs = 10_000)
        assertEquals(310_000L, timer.remainingMs)
        // The next part begins: count to its end again.
        timer = timer.partChanged(partLeftHeardMs = 300_000)
        assertEquals(0, timer.skipParts)
        assertTrue(timer.endsWithPart)
        assertEquals(300_000L, timer.remainingMs)
        assertEquals("End of this part", PlayerLabels.sleepChoice(SleepChoice.EndOfPart))
        assertEquals("1 hour", PlayerLabels.sleepChoice(SleepChoice.Minutes(60)))
        assertEquals("Sleep", PlayerLabels.sleep(null))
    }

    @Test fun `stopping for sleep steps back over what faded`() {
        assertEquals(90_000L, SmartRewind.afterSleep(120_000))
        assertEquals(0L, SmartRewind.afterSleep(10_000))
    }
}

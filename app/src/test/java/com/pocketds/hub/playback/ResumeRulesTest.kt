package com.pocketds.hub.playback

import com.pocketds.hub.playback.ResumeRules.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeRulesTest {

    private val hour = 3_600_000L

    @Test
    fun `under five percent is not a resume point`() {
        assertEquals(Verdict.NOT_STARTED, ResumeRules.judge(0, hour))
        assertEquals(Verdict.NOT_STARTED, ResumeRules.judge(179_999, hour))
        assertEquals(0L, ResumeRules.resumePosition(179_999, hour))
    }

    @Test
    fun `between five and ninety percent resumes`() {
        assertEquals(180_000L, ResumeRules.resumePosition(180_000, hour))
        assertEquals(3_240_000L, ResumeRules.resumePosition(3_240_000, hour))
    }

    @Test
    fun `past ninety percent is watched, the way the hub saves it`() {
        // A film stopped at 92% used to offer "Resume" for two minutes, then
        // flip to watched when the server's answer arrived.
        assertEquals(Verdict.FINISHED, ResumeRules.judge(3_312_000, hour))
        assertTrue(ResumeRules.isFinished(3_312_000, hour))
        assertEquals(0L, ResumeRules.resumePosition(3_312_000, hour))
        assertTrue(ResumeRules.isFinished(hour, hour))
    }

    @Test
    fun `anything shorter than five minutes is watched once started`() {
        assertEquals(Verdict.FINISHED, ResumeRules.judge(60_000, 240_000))
        assertEquals(Verdict.NOT_STARTED, ResumeRules.judge(5_000, 240_000))
    }

    @Test
    fun `no runtime keeps the position, as the hub does`() {
        assertEquals(Verdict.RESUME, ResumeRules.judge(60_000, 0))
        assertEquals(Verdict.NOT_STARTED, ResumeRules.judge(0, 0))
    }

    @Test
    fun `a played item never resumes`() {
        assertEquals(0L, ResumeRules.resumePosition(1_200_000, hour, played = true))
        assertFalse(ResumeRules.isFinished(1_200_000, hour))
    }
}

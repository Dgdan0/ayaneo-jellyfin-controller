package com.pocketds.hub.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.reader.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadAlongPlaybackTest {
    @Test fun realAudioResumesHighlightsAndPausesWithoutAutoplayOrPositionLoss() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(activity.cacheDir, "narration-test.wav")
        val bytes = 8000 * 2 * 4
        val wav = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(bytes)
        file.writeBytes(wav.array())
        var audio: ReadAlongPlayback? = null
        var highlighted: ReadAlongSegment? = null
        var saved: ReadAlongPosition? = null
        var failed = false
        try {
            i.runOnMainSync {
                val segments = (0..3).map { ReadAlongSegment("chapter.xhtml", "s$it", "voice.wav", it * 1000L, (it + 1) * 1000L) }
                audio = ReadAlongPlayback(activity, ReadAlongTimeline(listOf(ReadAlongTrack("voice.wav", segments))), listOf(file),
                    ReadAlongPosition(0, 1100), { highlighted = it }, {}, { point, _ -> saved = point }, { failed = true })
            }
            Thread.sleep(400)
            i.runOnMainSync {
                assertFalse(audio!!.isPlaying)
                assertEquals(1100L, audio!!.position.offsetMs)
                assertNull(saved) // Opening a book must not rewrite its progress.
                audio!!.toggle()
            }
            val deadline = System.currentTimeMillis() + 5000
            var reached = false
            while (!reached && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
                i.runOnMainSync { reached = audio!!.position.offsetMs >= 1500 }
            }
            var paused = 0L
            i.runOnMainSync {
                assertFalse(failed)
                assertTrue(reached)
                assertEquals("s1", highlighted?.fragment)
                audio!!.pause()
                paused = audio!!.position.offsetMs
                assertEquals(paused, saved?.offsetMs)
            }
            Thread.sleep(250)
            i.runOnMainSync {
                assertFalse(audio!!.isPlaying)
                assertTrue(kotlin.math.abs(paused - audio!!.position.offsetMs) < 50)
                paused = audio!!.position.offsetMs
                assertEquals(paused, saved?.offsetMs)
                audio!!.release(); audio = null
                assertEquals(paused, saved?.offsetMs)
            }
        } finally {
            i.runOnMainSync { audio?.release(); activity.finish() }
            file.delete()
        }
    }
}

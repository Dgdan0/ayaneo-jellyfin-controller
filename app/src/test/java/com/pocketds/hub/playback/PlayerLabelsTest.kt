package com.pocketds.hub.playback

import com.pocketds.hub.model.PlaybackPrepareResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class PlayerLabelsTest {
    private lateinit var original: Locale

    @Before
    fun germanDevice() {
        // A comma-decimal locale, the case Locale.US guards against.
        original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
    }

    @After
    fun restore() = Locale.setDefault(original)

    @Test
    fun `decimals do not follow the device locale`() {
        assertEquals("1.5 seconds earlier", PlayerLabels.subtitleOffset(-1_500))
        assertEquals("0.3 seconds later", PlayerLabels.subtitleOffset(300))
        assertEquals("No offset", PlayerLabels.subtitleOffset(0))
        val plan = PlaybackPrepareResponse(playMethod = "DirectPlay", width = 1920, height = 1080,
            videoCodec = "hevc", frameRate = 23.976)
        assertEquals("DirectPlay · 1920×1080 · HEVC · 23.98 fps", PlayerLabels.diagnostic(plan))
    }

    @Test
    fun `seek deltas carry their sign`() {
        assertEquals("+0:10", PlayerLabels.signedTime(10_000))
        assertEquals("−0:30", PlayerLabels.signedTime(-30_000))
    }

    @Test
    fun `normal speed reads as a word`() {
        assertEquals("Normal", PlayerLabels.speed(1f))
        assertEquals("1.5×", PlayerLabels.speed(1.5f))
    }
}

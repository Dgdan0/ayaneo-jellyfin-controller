package com.pocketds.hub.playback

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrOutputTest {

    @Test
    fun `hdr10 and dolby vision base layers are converted`() {
        // Captain America: The Winter Soldier -- BT.2020, SMPTE 2084, DV profile 8.
        assertTrue(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.COLOR_TRANSFER_ST2084, sdkInt = 33))
    }

    @Test
    fun `hlg is converted`() {
        // Iron Man 3 -- BT.2020, ARIB STD-B67.
        assertTrue(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.COLOR_TRANSFER_HLG, sdkInt = 33))
    }

    @Test
    fun `video explicitly tagged sdr is left alone`() {
        assertFalse(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.COLOR_TRANSFER_SDR, sdkInt = 33))
        assertFalse(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.COLOR_TRANSFER_SRGB, sdkInt = 33))
    }

    @Test
    fun `untagged video is asked too, because hdr may only be known once decoding starts`() {
        // Iron Man 3's MKV has no colour element: Media3 created the decoder
        // with no colour info, and only the decoder saw HLG in the stream.
        assertTrue(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.INDEX_UNSET, sdkInt = 33))
    }

    @Test
    fun `audio decoders are never asked`() {
        assertFalse(HdrOutput.wantsSdrConversion(isVideo = false, colorTransfer = C.COLOR_TRANSFER_ST2084, sdkInt = 33))
    }

    @Test
    fun `devices before Android 12 have no such request`() {
        assertFalse(HdrOutput.wantsSdrConversion(isVideo = true, colorTransfer = C.COLOR_TRANSFER_HLG, sdkInt = 30))
    }
}

package com.pocketds.hub.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerBrightnessPolicyTest {
    @Test fun `local video dimmer is clear at full brightness and bounded at minimum`() {
        assertEquals(0f, PlayerBrightnessPolicy.overlayAlpha(1f), 0f)
        assertEquals(.85f, PlayerBrightnessPolicy.overlayAlpha(0f), 0f)
        assertEquals(.425f, PlayerBrightnessPolicy.overlayAlpha(.5f), .001f)
        assertEquals(0f, PlayerBrightnessPolicy.overlayAlpha(9f), 0f)
    }
}

package com.pocketds.hub.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CastTransferPolicyTest {
    @Test fun buildsOnlyReceiverReachableHttpsGrantUrls() {
        assertEquals(
            "https://media.example.org:55886/v1/cast/opaque/stream",
            CastTransferPolicy.receiverUrl(
                "https://media.example.org:55886/",
                "/v1/cast/opaque/stream"
            )
        )
        assertNull(CastTransferPolicy.receiverUrl("http://media.example.org", "/v1/cast/opaque/stream"))
        assertNull(CastTransferPolicy.receiverUrl("https://localhost:8080", "/v1/cast/opaque/stream"))
        assertNull(CastTransferPolicy.receiverUrl("https://100.97.20.86", "/v1/cast/opaque/stream"))
        assertNull(CastTransferPolicy.receiverUrl("https://media.example.org", "https://evil.example/stream"))
        assertNull(CastTransferPolicy.receiverUrl("https://media.example.org", "/v1/library"))
    }

    @Test fun localPlaybackStopsOnlyAfterRemoteLoadSucceeds() {
        assertEquals(CastTransferStage.LOCAL, CastTransferPolicy.afterLoad(false))
        assertEquals(CastTransferStage.REMOTE, CastTransferPolicy.afterLoad(true))
    }
}

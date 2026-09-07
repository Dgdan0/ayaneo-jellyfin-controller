package com.pocketds.hub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubFailuresTest {

    @Test
    fun `http status codes map to their kinds`() {
        assertEquals(FailureKind.UNAUTHORIZED, HubFailures.classify(null, 401))
        assertEquals(FailureKind.UNAUTHORIZED, HubFailures.classify(null, 403))
        assertEquals(FailureKind.NOT_FOUND, HubFailures.classify(null, 404))
        assertEquals(FailureKind.RATE_LIMITED, HubFailures.classify(null, 429))
        assertEquals(FailureKind.UPSTREAM_DOWN, HubFailures.classify(null, 503))
        assertEquals(FailureKind.SERVER, HubFailures.classify(null, 500))
        assertEquals(FailureKind.BAD_RESPONSE, HubFailures.classify(null, 400))
    }

    @Test
    fun `the hub's ban response is rate limiting, not an auth failure`() {
        // The hub answers 429 once a source is banned. Treating that as
        // UNAUTHORIZED would send the user to the Setup screen to fix a token
        // that is perfectly correct.
        assertEquals(FailureKind.RATE_LIMITED, HubFailures.classify(null, 429))
    }

    @Test
    fun `transport exceptions map to their kinds`() {
        assertEquals(
            FailureKind.TIMEOUT,
            HubFailures.classify("java.net.SocketTimeoutException", null)
        )
        assertEquals(
            FailureKind.NO_NETWORK,
            HubFailures.classify("java.net.UnknownHostException", null)
        )
        assertEquals(
            FailureKind.NO_NETWORK,
            HubFailures.classify("java.net.ConnectException", null)
        )
        assertEquals(
            FailureKind.BAD_RESPONSE,
            HubFailures.classify("javax.net.ssl.SSLHandshakeException", null)
        )
    }

    @Test
    fun `a decode failure is a bad response, not a network fault`() {
        // These need completely different fixes: one is "the hub changed shape",
        // the other is "the wifi dropped".
        assertEquals(
            FailureKind.BAD_RESPONSE,
            HubFailures.classify("kotlinx.serialization.json.internal.JsonDecodingException", null)
        )
        assertEquals(
            FailureKind.BAD_RESPONSE,
            HubFailures.classify("kotlinx.serialization.SerializationException", null)
        )
    }

    @Test
    fun `a status code wins over an exception name`() {
        assertEquals(
            FailureKind.UNAUTHORIZED,
            HubFailures.classify("java.net.SocketTimeoutException", 401)
        )
    }

    @Test
    fun `anything unrecognised is unknown rather than mislabelled`() {
        assertEquals(FailureKind.UNKNOWN, HubFailures.classify("com.example.Weird", null))
        assertEquals(FailureKind.UNKNOWN, HubFailures.classify(null, null))
    }

    @Test
    fun `only the transient kinds are retryable`() {
        assertTrue(FailureKind.TIMEOUT.isRetryable)
        assertTrue(FailureKind.NO_NETWORK.isRetryable)
        assertTrue(FailureKind.SERVER.isRetryable)
        assertTrue(FailureKind.UPSTREAM_DOWN.isRetryable)

        // Retrying a rejected credential cannot start working, and five failures
        // in a minute earns this device a 15-minute ban from the hub.
        assertFalse(FailureKind.UNAUTHORIZED.isRetryable)
        assertFalse(FailureKind.NOT_FOUND.isRetryable)
        assertFalse(FailureKind.BAD_RESPONSE.isRetryable)
    }

    @Test
    fun `every kind has a message a person could act on`() {
        for (kind in FailureKind.entries) {
            val message = HubFailures.message(kind)
            assertTrue("$kind has no message", message.isNotBlank())
            assertFalse("$kind leaks the enum name", message.contains("_"))
        }
    }
}

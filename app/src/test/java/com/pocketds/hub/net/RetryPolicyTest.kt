package com.pocketds.hub.net

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryPolicyTest {

    private val noJitter = Random(0)

    @Test
    fun `a transient failure is retried`() {
        assertNotNull(RetryPolicy.delayMsFor(1, FailureKind.TIMEOUT, idempotent = true, noJitter))
    }

    @Test
    fun `a rejected credential is never retried`() {
        // It cannot start working, and hammering it earns a 15-minute ban.
        assertNull(
            RetryPolicy.delayMsFor(1, FailureKind.UNAUTHORIZED, idempotent = true, noJitter)
        )
    }

    @Test
    fun `a non-idempotent call is never retried`() {
        // A timeout does not mean it did not happen. Retrying a request that
        // actually succeeded leaves a duplicate to clean up by hand.
        assertNull(RetryPolicy.delayMsFor(1, FailureKind.TIMEOUT, idempotent = false, noJitter))
        assertNull(RetryPolicy.delayMsFor(1, FailureKind.SERVER, idempotent = false, noJitter))
    }

    @Test
    fun `a 4xx is never retried`() {
        assertNull(RetryPolicy.delayMsFor(1, FailureKind.NOT_FOUND, idempotent = true, noJitter))
        assertNull(
            RetryPolicy.delayMsFor(1, FailureKind.BAD_RESPONSE, idempotent = true, noJitter)
        )
    }

    @Test
    fun `it gives up after the attempt limit`() {
        assertNotNull(RetryPolicy.delayMsFor(1, FailureKind.TIMEOUT, true, noJitter))
        assertNotNull(RetryPolicy.delayMsFor(2, FailureKind.TIMEOUT, true, noJitter))
        assertNull(RetryPolicy.delayMsFor(3, FailureKind.TIMEOUT, true, noJitter))
        assertNull(RetryPolicy.delayMsFor(99, FailureKind.TIMEOUT, true, noJitter))
    }

    @Test
    fun `the delay grows between attempts`() {
        val first = RetryPolicy.delayMsFor(1, FailureKind.TIMEOUT, true, Random(1))!!
        val second = RetryPolicy.delayMsFor(2, FailureKind.TIMEOUT, true, Random(1))!!
        assertTrue("$first should be less than $second", first < second)
    }

    @Test
    fun `jitter keeps the delay positive and near the base`() {
        // Several screens recovering at once must not all hit the hub on the
        // same tick, but the spread has to stay sane.
        repeat(200) { seed ->
            val delay = RetryPolicy.delayMsFor(1, FailureKind.TIMEOUT, true, Random(seed))!!
            assertTrue("delay $delay is not positive", delay > 0)
            assertTrue("delay $delay strayed too far from 400ms", delay in 250L..550L)
        }
    }

    @Test
    fun `the whole retry ladder fits inside the call timeout`() {
        // callTimeout is 45s. If the sleeps alone could exceed it, the last
        // attempt would be cancelled before it ever left the device.
        assertTrue(RetryPolicy.worstCaseDelayMs() < 45_000L)
    }

    @Test
    fun `an attempt number below one is refused rather than indexing backwards`() {
        assertNull(RetryPolicy.delayMsFor(0, FailureKind.TIMEOUT, true, noJitter))
        assertNull(RetryPolicy.delayMsFor(-5, FailureKind.TIMEOUT, true, noJitter))
    }
}

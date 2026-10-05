package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** The listening place goes to the hub no faster than every 15 seconds, and the last one always goes (#19, A4). */
class SyncThrottleTest {
    @Test fun `the first sync runs at once, the next waits out the interval, and none waits longer`() {
        val throttle = SyncThrottle(15_000)
        assertEquals(0L, throttle.waitFor(1_000))
        throttle.ran(1_000)
        // A pause two seconds later waits for the rest of the fifteen.
        assertEquals(13_000L, throttle.waitFor(3_000))
        // Asked again later, it waits only what is left: the last asked for runs then.
        assertEquals(1_000L, throttle.waitFor(15_000))
        assertEquals(0L, throttle.waitFor(16_000))
        assertEquals(0L, throttle.waitFor(60_000))
        throttle.ran(60_000)
        assertEquals(15_000L, throttle.waitFor(60_000))
    }
}

package com.pocketds.hub

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the JVM unit-test toolchain is wired up at all, so a failure in a real
 * test means the code is wrong rather than the build being misconfigured.
 */
class TestInfrastructureTest {

    @Test
    fun `unit tests run on the jvm`() {
        assertEquals(4, 2 + 2)
    }
}

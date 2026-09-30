package com.pocketds.hub.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialGateTest {

    private val gate = CredentialGate()

    @Test
    fun `a new token may be sent but one request at a time`() {
        assertNull(gate.blockFor("abc", 0))
        assertTrue(gate.needsProbe("abc"))
    }

    @Test
    fun `one 401 blocks every later request with that token`() {
        gate.observe("abc", 401, null, 0)
        assertEquals(CredentialGate.Block.Rejected, gate.blockFor("abc", 0))
        assertEquals(CredentialGate.Block.Rejected, gate.blockFor("abc", 60 * 60_000))
    }

    @Test
    fun `editing the token allows one new attempt`() {
        gate.observe("abc", 401, null, 0)
        assertNull(gate.blockFor("abd", 0))
        assertTrue(gate.needsProbe("abd"))
    }

    @Test
    fun `a 403 is a missing scope, not a wrong token`() {
        // "this device is not allowed to control downloads" -- the same token
        // still reads everything else.
        gate.observe("abc", 403, null, 0)
        assertNull(gate.blockFor("abc", 0))
        assertFalse(gate.needsProbe("abc"))
    }

    @Test
    fun `success lifts the probe and clears an old rejection`() {
        gate.observe("abc", 401, null, 0)
        gate.observe("abc", 200, null, 0)
        assertNull(gate.blockFor("abc", 0))
        assertFalse(gate.needsProbe("abc"))
    }

    @Test
    fun `a later 401 for an accepted token rejects it again`() {
        // The token was revoked on the hub.
        gate.observe("abc", 200, null, 0)
        gate.observe("abc", 401, null, 0)
        assertEquals(CredentialGate.Block.Rejected, gate.blockFor("abc", 0))
        assertTrue(gate.needsProbe("abc"))
    }

    @Test
    fun `a ban holds every request until it expires`() {
        gate.observe("abc", 429, 900, 1_000)
        val block = gate.blockFor("abc", 61_000)
        assertTrue(block is CredentialGate.Block.Banned)
        assertEquals(840, (block as CredentialGate.Block.Banned).remainingSeconds)
        assertEquals("The Hub is refusing this device for 14 min after failed sign-ins", block.message)
        assertNull(gate.blockFor("abc", 901_000))
    }

    @Test
    fun `the ordinary rate limit is not a ban`() {
        gate.observe("abc", 429, 2, 0)
        assertNull(gate.blockFor("abc", 0))
        gate.observe("abc", 429, null, 0)
        assertNull(gate.blockFor("abc", 0))
    }

    @Test
    fun `a new token may test the connection during a ban`() {
        // Restarting the hub clears its bans; typing the token again is how
        // the user asks to try.
        gate.observe("abc", 429, 900, 0)
        assertNull(gate.blockFor("xyz", 0))
    }

    @Test
    fun `missing credentials are left to the caller`() {
        gate.observe("", 401, null, 0)
        assertNull(gate.blockFor("", 0))
        assertFalse(gate.needsProbe(""))
    }
}

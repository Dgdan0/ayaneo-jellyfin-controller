package com.pocketds.hub.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubConnectionValidationTest {
    private val token = "a".repeat(43)

    @Test
    fun `https address and complete token are accepted`() {
        assertNull(HubConnectionValidation.error("https://ayaneo.example.ts.net", token))
    }

    @Test
    fun `missing token is explained before any network request`() {
        assertEquals(
            "Paste the Hub access token",
            HubConnectionValidation.error("https://ayaneo.example.ts.net", "")
        )
    }

    @Test
    fun `short token is not sent to the hub`() {
        assertEquals(
            "The Hub access token is incomplete",
            HubConnectionValidation.error("https://ayaneo.example.ts.net", "too-short")
        )
    }

    @Test
    fun `remote plain http is refused`() {
        assertEquals(
            "Enter a complete HTTPS address",
            HubConnectionValidation.error("http://ayaneo.example.ts.net", token)
        )
    }
}

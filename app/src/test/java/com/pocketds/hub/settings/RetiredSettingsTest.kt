package com.pocketds.hub.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredSettingsTest {
    @Test fun `the stored look and theme go, and nothing else does`() {
        assertTrue("look" in RetiredSettings.KEYS)
        assertTrue("theme_mode" in RetiredSettings.KEYS)
        val stored = setOf("look", "theme_mode", "library_last_view", "hub_url", "accent:abc:books", "content_mode")
        assertEquals(listOf("look", "theme_mode", "library_last_view"), RetiredSettings.among(stored))
    }

    @Test fun `a second launch finds nothing to remove`() {
        assertEquals(emptyList<String>(), RetiredSettings.among(setOf("hub_url", "accent:abc:media")))
        assertEquals(emptyList<String>(), RetiredSettings.among(emptySet()))
    }
}

package com.pocketds.hub.settings

import com.pocketds.hub.state.ContentMode
import org.junit.Assert.*
import org.junit.Test

class AppearancePolicyTest {
    @Test fun `ten stable presets each have accessible light and dark variants`() {
        assertEquals(10, AccentPreset.entries.size)
        assertEquals(10, AccentPreset.entries.map { it.id }.distinct().size)
        fun luminance(rgb: Int): Double = listOf(16,8,0).map { shift ->
            val v = ((rgb shr shift) and 255) / 255.0
            if (v <= .04045) v / 12.92 else Math.pow((v + .055) / 1.055, 2.4)
        }.let { it[0]*.2126 + it[1]*.7152 + it[2]*.0722 }
        fun contrast(a: Int, b: Int): Double = (maxOf(luminance(a), luminance(b))+.05)/(minOf(luminance(a),luminance(b))+.05)
        AccentPreset.entries.forEach {
            // Dark page and card surfaces, light page and white.
            assertTrue(it.id, contrast(it.dark, 0xff0a0d12.toInt()) >= 4.5)
            assertTrue(it.id, contrast(it.dark, 0xff131821.toInt()) >= 4.5)
            assertTrue(it.id, contrast(it.light, 0xfff5f6f4.toInt()) >= 4.5)
            assertTrue(it.id, contrast(it.light, -1) >= 4.5)
            // The label on a Play button filled with the accent.
            assertTrue(it.id, contrast(it.dark, it.ink(dark = true)) >= 4.5)
            assertTrue(it.id, contrast(it.light, it.ink(dark = false)) >= 4.5)
        }
        assertEquals(AccentPreset.TEAL, AccentPreset.fromStored("retired"))
    }

    @Test fun `books default to gold and the first palette's choices keep their nearest pastel`() {
        assertEquals(AccentPreset.GOLD, AccentPreset.defaultFor(ContentMode.BOOKS))
        assertEquals(AccentPreset.TEAL, AccentPreset.defaultFor(ContentMode.MEDIA))
        assertEquals(AccentPreset.GOLD, AccentPreset.fromStored(null, AccentPreset.GOLD))
        assertEquals(AccentPreset.SKY, AccentPreset.fromStored("blue", AccentPreset.GOLD))
        assertEquals(AccentPreset.PEACH, AccentPreset.fromStored("coral"))
        assertEquals(AccentPreset.ROSE, AccentPreset.fromStored("rose"))
    }
    @Test fun `profile and domain scope is isolated and normalizes hub address`() {
        val scope = PreferenceScope.key("HTTPS://HOST:443/", "alice", ContentMode.BOOKS)
        assertEquals(scope, PreferenceScope.key("https://host", "alice", ContentMode.BOOKS))
        assertNotEquals(scope, PreferenceScope.key("https://host", "bob", ContentMode.BOOKS))
        assertNotEquals(scope, PreferenceScope.key("https://host", "alice", ContentMode.MEDIA))
        assertNotEquals(scope, PreferenceScope.key("https://other", "alice", ContentMode.BOOKS))
    }
    @Test fun `sort decode survives corrupt values and unsupported choice is only a projection`() {
        val preferred = SortPreference("author", false)
        assertEquals(SortPreference("title", true), preferred.supported(listOf("title"), "title"))
        assertEquals(preferred, preferred.supported(listOf("title", "author"), "title"))
        assertEquals(SortPreference("name", true), SortPreference.decode("garbage", "name"))
        assertEquals(preferred, SortPreference.decode(preferred.encode(), "title"))
        assertFalse(SortPreference.forField("played").ascending)
        assertFalse(SortPreference.forField("rating").ascending)
        assertTrue(SortPreference.forField("author").ascending)
    }

    @Test
    fun `the direction is named for what it does to the field`() {
        assertEquals("Newest first", SortPreference("added", false).directionLabel())
        assertEquals("Oldest first", SortPreference("added", true).directionLabel())
        assertEquals("A to Z", SortPreference("name", true).directionLabel())
        assertEquals("Highest first", SortPreference("rating", false).directionLabel())
        // A new field keeps its own default direction; flipping keeps the field.
        assertEquals(SortPreference("added", false), SortPreference.forField("added"))
    }
}

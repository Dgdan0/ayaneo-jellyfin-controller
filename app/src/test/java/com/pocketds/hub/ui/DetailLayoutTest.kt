package com.pocketds.hub.ui

import org.junit.Assert.*
import org.junit.Test

class DetailLayoutTest {
    @Test fun `a real landscape image gets a hero only when text has enough room`() {
        assertTrue(DetailLayout.useHero("movie", true, 785, 1f))
        assertTrue(DetailLayout.useHero("episode", true, 663, 1f))
        assertFalse(DetailLayout.useHero("movie", false, 785, 1f))
        assertFalse(DetailLayout.useHero("movie", true, 420, 1f))
        assertFalse(DetailLayout.useHero("movie", true, 663, 1.5f))
        assertFalse(DetailLayout.useHero("series", true, 785, 1f))
        assertFalse(DetailLayout.useHero("book", true, 785, 1f))
    }

    @Test fun `the largest poster still fits its reserved focus space`() {
        for (height in listOf(144, 192, 216, 280)) {
            val clearance = DetailLayout.focusClearance(height)
            val expanded = height * DetailLayout.POSTER_FOCUS_SCALE + 2 * DetailLayout.FOCUS_RING_DP
            assertTrue("$height dp poster clips on focus", height + 2 * clearance >= expanded)
        }
        assertTrue("Focus lift should not obscure neighbours", DetailLayout.POSTER_FOCUS_SCALE <= 1.04f)
    }

    @Test fun `shelf accommodates its captions and focus at larger font scales`() {
        for (fontScale in listOf(1f, 1.3f, 1.6f)) {
            val height = DetailLayout.posterCardHeight(144, fontScale)
            assertTrue(height >= 144 + 36 * fontScale)
            val shelf = DetailLayout.shelfHeight(height)
            assertTrue(shelf >= height * DetailLayout.POSTER_FOCUS_SCALE + 2 * DetailLayout.FOCUS_RING_DP)
        }
    }

    @Test fun `more keeps every less frequent movie action reachable`() {
        val movie = DetailActions.forType("movie")
        // The state toggles sit beside Play; the rest is under More.
        assertEquals(listOf("play", "watched", "favorite", "download", "more"), movie.visible)
        assertEquals(listOf("restart", "options"), movie.overflow)
        assertEquals(setOf("play", "favorite", "download", "restart", "options", "watched"),
            (movie.visible.filterNot { it == "more" } + movie.overflow).toSet())
        assertEquals(listOf("play", "watched", "favorite", "download", "more"), DetailActions.forType("series").visible)
        assertTrue(DetailActions.forType("season").visible.isEmpty())
    }

    @Test fun `return focus follows identity and falls back when an item disappears`() {
        assertEquals("season:b", DetailLayout.restoreFocus("season:b", listOf("play", "season:c", "season:b")))
        assertEquals("play", DetailLayout.restoreFocus("season:deleted", listOf("play", "season:b")))
        assertEquals(null, DetailLayout.restoreFocus("play", emptyList()))
    }

    @Test fun `profile and server are part of the detail snapshot identity`() {
        val key = DetailSnapshotKey.of("https://hub/", "dan", "series")
        assertEquals(key, DetailSnapshotKey.of("https://hub", "dan", "series"))
        assertNotEquals(key, DetailSnapshotKey.of("https://hub", "other", "series"))
        assertNotEquals(key, DetailSnapshotKey.of("https://other-hub", "dan", "series"))
    }
}

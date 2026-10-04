package com.pocketds.hub.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageArtworkTest {

    private val bleach = "/v1/img/jf/d151b13906a376f2a95e6bc56a43b4a9/Backdrop?tag=c677"

    @Test fun `new artwork re-tints the page`() {
        assertEquals(bleach, PageArtwork.next(shown = null, reported = bleach))
        assertEquals(bleach, PageArtwork.next(shown = "/v1/img/tmdb/w342/a.jpg", reported = bleach))
    }

    @Test fun `the same artwork again changes nothing`() {
        assertNull(PageArtwork.next(shown = bleach, reported = bleach))
    }

    @Test fun `a screen with no artwork keeps the last one`() {
        assertNull(PageArtwork.next(shown = bleach, reported = null))
        assertNull(PageArtwork.next(shown = bleach, reported = ""))
        assertNull(PageArtwork.next(shown = bleach, reported = "  "))
        assertNull(PageArtwork.next(shown = null, reported = null))
    }

    @Test fun `a title page shows its backdrop, else the episode's still, else its poster`() {
        assertEquals("/b", PageArtwork.title(backdrop = "/b", poster = "/p", still = "/s"))
        assertEquals("/s", PageArtwork.title(backdrop = "", poster = "/p", still = "/s"))
        assertEquals("/p", PageArtwork.title(backdrop = "", poster = "/p"))
        assertNull(PageArtwork.title(backdrop = "", poster = ""))
    }
}

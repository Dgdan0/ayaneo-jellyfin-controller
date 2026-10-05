package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PagedImageStateTest {
    private val portrait = { index: Int -> PageDimension(index, 1200, 1800) }

    @Test
    fun `cover is isolated and ltr pairs read left to right`() {
        val spreads = SpreadPlanner.plan((0..4).map(portrait), PageDirection.LTR)

        assertEquals(listOf(0), spreads[0].readingOrder)
        assertEquals(listOf(1, 2), spreads[1].readingOrder)
        assertEquals(1, spreads[1].leftPage)
        assertEquals(2, spreads[1].rightPage)
        assertEquals(listOf(3, 4), spreads[2].readingOrder)
    }

    @Test
    fun `manga pairs have right to left reading order without swapping source indices`() {
        val spreads = SpreadPlanner.plan((0..4).map(portrait), PageDirection.RTL)

        assertEquals(2, spreads[1].leftPage)
        assertEquals(1, spreads[1].rightPage)
        assertEquals(listOf(1, 2), spreads[1].readingOrder)
    }

    @Test
    fun `wide pages stay alone and do not steal a neighbour`() {
        val pages = listOf(
            portrait(0),
            portrait(1),
            PageDimension(2, 2400, 1400, isWide = true),
            portrait(3),
            portrait(4)
        )

        val spreads = SpreadPlanner.plan(pages, PageDirection.LTR)

        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3, 4)), spreads.map { it.readingOrder })
    }

    @Test
    fun `thirds come from the page's shape and the screen's`() {
        // The Pocket's 1920 x 1080 top screen.
        assertEquals(3, ViewportStepPlanner.count(1988, 3056, 1920, 1080))   // a US comic page
        assertEquals(3, ViewportStepPlanner.count(1400, 2000, 1920, 1080))   // a manga page
        assertEquals(2, ViewportStepPlanner.count(3976, 3056, 1920, 1080))   // a two-page spread
        assertEquals(1, ViewportStepPlanner.count(4000, 1500, 1920, 1080))   // a page that fits at its width
        assertEquals(1, ViewportStepPlanner.count(0, 0, 1920, 1080))         // size unknown
        // A long strip keeps going, a screen at a time less the overlap, up to a limit.
        assertEquals(ViewportStepPlanner.MAX_STEPS, ViewportStepPlanner.count(800, 400_000, 1920, 1080))
    }

    @Test
    fun `thirds fit the width and run top to bottom, overlapping, never cropped`() {
        val steps = ViewportStepPlanner.fitWidth(1988, 3056, 1920, 1080)
        assertEquals(3, steps.size)
        steps.forEach {
            assertEquals(0.0, it.left, 0.0001)
            assertEquals(1.0, it.right, 0.0001)
        }
        assertEquals(0.0, steps.first().top, 0.0001)
        assertEquals(1.0, steps.last().bottom, 0.0001)
        // Each shows the screen's share of the page at its width, and a little of the step before.
        val visible = 1080.0 * 1988 / 1920 / 3056
        steps.forEach { assertEquals(visible, it.bottom - it.top, 0.0001) }
        assertTrue(steps[0].bottom - steps[1].top >= visible * ViewportStepPlanner.OVERLAP - 0.0001)
        assertEquals(listOf(NormalizedViewport(0.0, 0.0, 1.0, 1.0)), ViewportStepPlanner.fitWidth(4000, 1500, 1920, 1080))
    }

    @Test
    fun `each page takes its own steps, and going back lands on the last`() {
        // Page 1 is a spread (2 steps), the others comic pages (3).
        val state = PagedImageState(pageCount = 3, startPage = 0, stepsFor = { if (it == 1) 2 else 3 })
        state.advance(); state.advance()
        assertEquals(0 to 2, state.pageIndex to state.viewportIndex)
        state.advance()
        assertEquals(1 to 0, state.pageIndex to state.viewportIndex)
        assertEquals(2, state.viewportSteps)
        state.advance(); state.advance()
        assertEquals(2 to 0, state.pageIndex to state.viewportIndex)
        state.retreat()
        assertEquals(1 to 1, state.pageIndex to state.viewportIndex)
        assertTrue(state.turnPage(-1))
        assertEquals(0 to 2, state.pageIndex to state.viewportIndex)
    }

    @Test
    fun `a saved step comes back, and a page that changes size keeps the step inside it`() {
        var spread = false
        val state = PagedImageState(pageCount = 4, startPage = 2, stepsFor = { if (spread) 2 else 3 }, startStep = 2)
        assertEquals(2, state.viewportIndex)
        // It decoded as a spread: two steps, so the third becomes the second.
        spread = true
        state.refit()
        assertEquals(1, state.viewportIndex)
        state.jump(3, 7)
        assertEquals(3 to 1, state.pageIndex to state.viewportIndex)
        // A start step past the page's last is its last.
        assertEquals(2, PagedImageState(pageCount = 2, startPage = 1, stepsFor = { 3 }, startStep = Int.MAX_VALUE).viewportIndex)
    }

    @Test
    fun `a series keeps its fit and direction, and the default covers the rest`() {
        assertEquals(ComicView(ComicFit.THIRDS), ComicView.decode(null, ComicView.DEFAULT_FIT))
        assertEquals(ComicView(ComicFit.WHOLE), ComicView.decode(null, ComicFit.WHOLE))
        val chosen = ComicView(ComicFit.WIDTH, "rtl")
        assertEquals(chosen, ComicView.decode(chosen.encode(), ComicFit.THIRDS))
        assertEquals(ComicView(ComicFit.WHOLE, null), ComicView.decode(ComicView(ComicFit.WHOLE).encode(), ComicFit.THIRDS))
        // Something unreadable falls back rather than breaking the reader.
        assertEquals(ComicView(ComicFit.THIRDS, null), ComicView.decode("sideways|up", ComicFit.THIRDS))
    }

    @Test
    fun `trimming the margins is on unless a series turns it off, and older settings keep it on`() {
        assertTrue(ComicView.decode(null, ComicFit.THIRDS).trim)
        // As #16 stored them.
        assertTrue(ComicView.decode("width|rtl", ComicFit.THIRDS).trim)
        assertTrue(ComicView.decode("thirds|", ComicFit.THIRDS).trim)
        val off = ComicView(ComicFit.THIRDS, "rtl", trim = false)
        assertEquals(off, ComicView.decode(off.encode(), ComicFit.WHOLE))
        assertEquals(ComicView(ComicFit.WHOLE, null, trim = false), ComicView.decode(ComicView(ComicFit.WHOLE, trim = false).encode(), ComicFit.THIRDS))
        // On is written as #16 wrote it.
        assertEquals("width|rtl", ComicView(ComicFit.WIDTH, "rtl").encode())
    }

    @Test
    fun `the third you were on comes back only on the same page of the same issue`() {
        val place = ComicPlace("kavita:51", page = 4, step = 2)
        assertEquals(place, ComicPlace.decode(place.encode()))
        assertEquals(2, place.stepFor("kavita:51", 4, steps = 3))
        assertEquals(1, place.stepFor("kavita:51", 4, steps = 2))
        assertEquals(0, place.stepFor("kavita:51", 5, steps = 3))
        assertEquals(0, place.stepFor("kavita:52", 4, steps = 3))
        assertEquals(null, ComicPlace.decode("kavita:51|four|2"))
        assertEquals(null, ComicPlace.decode(null))
    }

    @Test
    fun `a zoom stays for the next page, at the same place across and at its top`() {
        // Zoomed to 1.5 times the fit, the middle of the view 70% across a 2000-wide page.
        val zoom = ComicZoom.of(scale = 1.5f, base = 1f, centerX = 1400f, pageWidth = 2000f)
        assertTrue(zoom.active)
        assertEquals(1.5f, zoom.factor, 0.001f)
        assertEquals(0.7f, zoom.anchorX, 0.001f)
        assertEquals(1.2f, zoom.scaleFor(base = 0.8f, min = 0.5f, max = 6f), 0.001f)
        // The next page, 2000 x 3000, of which 1280 x 720 shows: 70% across, at its top.
        val (x, y) = zoom.center(2000f, 3000f, 1280f, 720f, atEnd = false)
        assertEquals(1360f, x, 0.5f)   // as far right as the view can go
        assertEquals(360f, y, 0.5f)
        // Going back it opens at the bottom; a page narrower than the view is centred.
        assertEquals(2640f, zoom.center(2000f, 3000f, 1280f, 720f, atEnd = true).second, 0.5f)
        assertEquals(500f, zoom.center(1000f, 3000f, 1280f, 720f, atEnd = false).first, 0.5f)
        // Pinched back to the fit, the zoom ends.
        assertFalse(ComicZoom.of(scale = 1.01f, base = 1f, centerX = 900f, pageWidth = 2000f).active)
    }

    @Test
    fun `the end card names the issue and what comes next`() {
        assertEquals("End of Fantastic Four #51", EndOfIssue.heading("Fantastic Four", "Chapter 51", "51"))
        assertEquals("End of Fantastic Four · Annual 1965", EndOfIssue.heading("Fantastic Four", "Annual 1965", "1"))
        assertEquals("End of Saga #7", EndOfIssue.heading("Saga", "7", ""))
        assertEquals("Next: #52", EndOfIssue.next("Fantastic Four", "Fantastic Four", "Chapter 52", "52", readingList = false))
        // A reading list passing to another series names it.
        assertEquals("Next: Spider-Man #1", EndOfIssue.next("Fantastic Four", "Spider-Man", "Issue 1", "", readingList = true))
        assertEquals("That was the last issue", EndOfIssue.next("Fantastic Four", null, "", "", readingList = false))
        assertEquals("End of the reading list", EndOfIssue.next("Fantastic Four", null, "", "", readingList = true))
    }

    @Test
    fun `paged navigation consumes viewport steps before changing page`() {
        val state = PagedImageState(pageCount = 3, startPage = 1, viewportSteps = 3)

        assertTrue(state.advance())
        assertEquals(1, state.pageIndex)
        assertEquals(1, state.viewportIndex)
        assertTrue(state.advance())
        assertEquals(2, state.viewportIndex)
        assertTrue(state.advance())
        assertEquals(2, state.pageIndex)
        assertEquals(0, state.viewportIndex)
        assertTrue(state.advance())
        assertTrue(state.advance())
        assertFalse(state.advance())
        assertTrue(state.retreat())
        assertEquals(2, state.pageIndex)
        assertEquals(1, state.viewportIndex)
        assertTrue(state.retreat())
        assertTrue(state.retreat())
        assertEquals(1, state.pageIndex)
        assertEquals(2, state.viewportIndex)
    }

    @Test
    fun `whole page turns skip thirds and land at the reading edge`() {
        val state = PagedImageState(pageCount = 4, startPage = 1, viewportSteps = 3)
        state.advance()
        assertTrue(state.turnPage(1))
        assertEquals(2, state.pageIndex)
        assertEquals(0, state.viewportIndex)
        assertTrue(state.turnPage(-1))
        assertEquals(1, state.pageIndex)
        assertEquals(2, state.viewportIndex)
        state.seek(0)
        assertFalse(state.turnPage(-1))
        assertEquals(0, state.pageIndex)
    }

    @Test
    fun `controller pan moves by a viewport step and stops at page edges`() {
        assertEquals(1320f, ComicPanPolicy.step(500f, 2000, 1000f, 1)!!, 0.01f)
        assertEquals(1500f, ComicPanPolicy.step(1400f, 2000, 1000f, 1)!!, 0.01f)
        assertEquals(null, ComicPanPolicy.step(1500f, 2000, 1000f, 1))
        assertEquals(500f, ComicPanPolicy.step(900f, 2000, 1000f, -1)!!, 0.01f)
        // Within the content of a trimmed page: 1800 long from 100.
        assertEquals(1400f, ComicPanPolicy.step(1300f, 1800, 1000f, 1, from = 100f)!!, 0.01f)
        assertEquals(600f, ComicPanPolicy.edge(1800, 1000f, high = false, from = 100f), 0.01f)
        assertEquals(1400f, ComicPanPolicy.edge(1800, 1000f, high = true, from = 100f), 0.01f)
        assertEquals(null, ComicPanPolicy.step(500f, 2000, 1000f, -1))
        assertEquals(null, ComicPanPolicy.step(500f, 1000, 1200f, 1))
        assertEquals(500f, ComicPanPolicy.edge(2000, 1000f, high = false), 0.01f)
        assertEquals(1500f, ComicPanPolicy.edge(2000, 1000f, high = true), 0.01f)
    }

    @Test
    fun `page progress is zero based and reaches completion on final page`() {
        val state = PagedImageState(pageCount = 5, startPage = 2)

        assertEquals(0.5, state.progression, 0.0001)
        state.seek(4)
        assertEquals(1.0, state.progression, 0.0001)
        assertTrue(state.completed)
        state.seek(-50)
        assertEquals(0, state.pageIndex)
    }

    @Test
    fun `reader title does not repeat identical series and publication names`() {
        assertEquals("Daredevil 000.5", ReaderTitleFormatter.format("Daredevil 000.5", "Daredevil 000.5", "Fallback"))
        assertEquals("Daredevil · 000.5", ReaderTitleFormatter.format("Daredevil", "000.5", "Fallback"))
        assertEquals("Fallback", ReaderTitleFormatter.format("", "", "Fallback"))
    }

    @Test
    fun `reader controls initially focus the forward page action`() {
        assertEquals(6, ReaderControlFocusPolicy.initialIndex(controlCount = 7))
        assertEquals(null, ReaderControlFocusPolicy.initialIndex(controlCount = 0))
    }

    @Test
    fun `the reader heads a comic with its series and names the issue under it`() {
        assertEquals("Fantastic Four", ReaderTitleFormatter.heading("Fantastic Four", "Chapter 51", "Fallback"))
        assertEquals("Issue 51", ReaderTitleFormatter.issue("comic", "Fantastic Four", "Chapter 51", "51"))
        assertEquals("Chapter 11", ReaderTitleFormatter.issue("manga", "Chainsaw Man", "11", "11"))
        assertEquals("Annual 1965", ReaderTitleFormatter.issue("comic", "Fantastic Four", "Annual 1965", "1"))
        assertEquals("", ReaderTitleFormatter.issue("comic", "Saga", "Saga", ""))
        assertEquals("Issue 51 · Page 2 of 24", ReaderTitleFormatter.subtitle("Issue 51", 2, 24))
        assertEquals("Page 2 of 24 · Part 1 of 3", ReaderTitleFormatter.subtitle("", 2, 24, part = 1, parts = 3))
        // A page read whole has no parts.
        assertEquals("Page 2 of 24", ReaderTitleFormatter.subtitle("", 2, 24, part = 1, parts = 1))
    }

    @Test fun `an issue's cover is Kavita's chapter cover, and nothing else is guessed`() {
        assertEquals("/v1/img/reading/kavita-chapter/34", IssueCover.path("kavita-chapter:34"))
        assertEquals(null, IssueCover.path("storyteller:abc"))
        assertEquals(null, IssueCover.path("kavita-chapter:"))
        assertEquals(null, IssueCover.path("kavita-chapter:12/../x"))
        assertEquals(null, IssueCover.path("issue-51"))
    }
}

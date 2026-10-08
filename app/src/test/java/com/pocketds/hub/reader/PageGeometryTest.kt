package com.pocketds.hub.reader

import com.pocketds.hub.reader.PageGeometry.InsetTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Kindle's margins and gap on the Pocket's page (#47): outer 36 dp, gap 32 dp. */
class PageGeometryTest {
    @Test fun `readium keeps half the gap at each side of a column`() {
        assertEquals(32, PageGeometry.GAP_DP)
        assertEquals(16, PageGeometry.GUTTER_DP)
        // Two columns, each padded by the gutter at both sides: a gap apart, not twice a margin.
        assertEquals(PageGeometry.GAP_DP, 2 * PageGeometry.GUTTER_DP)
    }

    @Test fun `the presets are Kindle's margins scaled to the Pocket`() {
        assertEquals(24, PageGeometry.outerMarginDp(0.5f))
        assertEquals(36, PageGeometry.outerMarginDp(1f))
        assertEquals(56, PageGeometry.outerMarginDp(1.7f))
        assertEquals(listOf("Narrow", "Balanced", "Wide"), PageGeometry.Margin.entries.map { it.label })
        // The stored multipliers are the ones a device already keeps.
        assertEquals(listOf(0.5f, 1f, 1.7f), PageGeometry.Margin.entries.map { it.stored })
    }

    @Test fun `any stored multiplier lands on its nearest preset`() {
        assertEquals(PageGeometry.Margin.NARROW, PageGeometry.preset(0.5f))
        assertEquals(PageGeometry.Margin.NARROW, PageGeometry.preset(0.7f))
        assertEquals(PageGeometry.Margin.BALANCED, PageGeometry.preset(0.75f))
        assertEquals(PageGeometry.Margin.BALANCED, PageGeometry.preset(1.3f))
        assertEquals(PageGeometry.Margin.WIDE, PageGeometry.preset(1.35f))
        assertEquals(PageGeometry.Margin.WIDE, PageGeometry.preset(2f))
    }

    @Test fun `the inset is the outer margin less the gutter readium keeps itself`() {
        // Pocket, balanced: 36 dp outer, a 16 dp gutter, so a 20 dp inset (appendix A3 of the plan).
        assertEquals(20, PageGeometry.insetDp(1f))
        assertEquals(8, PageGeometry.insetDp(0.5f))
        assertEquals(40, PageGeometry.insetDp(1.7f))
        // The text starts the outer margin from the screen's edge, whatever the preset.
        for (m in listOf(0.5f, 1f, 1.7f)) assertEquals(PageGeometry.outerMarginDp(m), PageGeometry.insetDp(m) + PageGeometry.GUTTER_DP)
    }

    @Test fun `two columns on the Pocket are about 374 dp wide with a 32 dp gap`() {
        assertEquals(374.5f, PageGeometry.columnWidthDp(853f, 1f, 2), 0.01f)
        // One column fills the width between the margins.
        assertEquals(853f - 72f, PageGeometry.columnWidthDp(853f, 1f, 1), 0.01f)
        // Wider margins narrow the columns; narrower ones widen them.
        assertTrue(PageGeometry.columnWidthDp(853f, 1.7f, 2) < PageGeometry.columnWidthDp(853f, 1f, 2))
        assertTrue(PageGeometry.columnWidthDp(853f, 0.5f, 2) > PageGeometry.columnWidthDp(853f, 1f, 2))
        // The same text width in two columns as the outer edges and the gap leave: nothing is lost or counted twice.
        val columns = PageGeometry.columnWidthDp(853f, 1f, 2)
        assertEquals(853f, 2 * PageGeometry.outerMarginDp(1f) + 2 * columns + PageGeometry.GAP_DP, 0.01f)
    }

    @Test fun `readium is always told the same margin, so its gutter stays put`() {
        assertEquals(1.0, PageGeometry.READIUM_MARGIN_FACTOR, 0.0)
    }

    @Test fun `a tap in the left or right inset turns the page that way`() {
        val width = 853f
        val inset = 20f
        assertEquals(InsetTap.BACK, PageGeometry.inset(0f, width, inset))
        assertEquals(InsetTap.BACK, PageGeometry.inset(19.9f, width, inset))
        assertEquals(InsetTap.NONE, PageGeometry.inset(20f, width, inset))
        assertEquals(InsetTap.NONE, PageGeometry.inset(400f, width, inset))
        assertEquals(InsetTap.NONE, PageGeometry.inset(833f, width, inset))
        assertEquals(InsetTap.FORWARD, PageGeometry.inset(833.1f, width, inset))
        assertEquals(InsetTap.FORWARD, PageGeometry.inset(853f, width, inset))
        // With no inset there is nothing that is not Readium's.
        assertEquals(InsetTap.NONE, PageGeometry.inset(0f, width, 0f))
    }
}

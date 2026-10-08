package com.pocketds.hub.reader

/**
 * Kindle's margins and the gap between two columns, on the Pocket's page (#47): pure, so the arithmetic is
 * tested without a screen.
 *
 * Readium pads every column on both sides, so by itself the gap between two columns is twice the outer
 * margin (136 pt against 68 on the iPad; Kindle has 48 against 90). So the reader asks Readium for a
 * [GUTTER_DP] of padding (the RS property `pageGutter`: half the gap, so two columns are [GAP_DP] apart)
 * and puts the rest of the outer margin outside the page, as a side inset on the page's host
 * ([insetDp]); the corners line up with the text's outer edge ([outerMarginDp]).
 *
 * Measured against Kindle on the iPad (appendix A3 of READER_TYPOGRAPHY_PLAN.md): outer 90 pt and gap 48 pt
 * there; here the same shape at the Pocket's size, 36 dp and 32 dp, which leaves two columns of about 374 dp
 * (38 to 42 characters a line) on its 853 dp page.
 */
object PageGeometry {
    /** The space between two columns, in dp. */
    const val GAP_DP = 32

    /** Readium's padding at each side of a column: half the gap. */
    const val GUTTER_DP = GAP_DP / 2

    /**
     * What Readium is told for its page margins, always: the margin is chosen by the inset, not by Readium's
     * multiplier, so its gutter stays [GUTTER_DP] whatever the preset.
     */
    const val READIUM_MARGIN_FACTOR = 1.0

    /** The presets of "Margins": the stored multiplier (kept as it was) and the outer margin each means. */
    enum class Margin(val stored: Float, val outerDp: Int, val label: String) {
        NARROW(0.5f, 24, "Narrow"),
        BALANCED(1f, 36, "Balanced"),
        WIDE(1.7f, 56, "Wide")
    }

    /** The preset a stored multiplier is nearest to. */
    fun preset(pageMargins: Float): Margin = when {
        pageMargins < 0.75f -> Margin.NARROW
        pageMargins < 1.35f -> Margin.BALANCED
        else -> Margin.WIDE
    }

    /** From the screen's edge to the text's, in dp. */
    fun outerMarginDp(pageMargins: Float): Int = preset(pageMargins).outerDp

    /** The side inset of the page's host: the outer margin less the gutter Readium keeps itself. */
    fun insetDp(pageMargins: Float): Int = (outerMarginDp(pageMargins) - GUTTER_DP).coerceAtLeast(0)

    /** How wide a column of text is on a screen [widthDp] wide, in [columns] columns. */
    fun columnWidthDp(widthDp: Float, pageMargins: Float, columns: Int): Float {
        val count = columns.coerceAtLeast(1)
        return (widthDp - 2 * outerMarginDp(pageMargins) - (count - 1) * GAP_DP) / count
    }

    /**
     * Whether a touch at [x] lies in the inset at the left or the right of a page [width] wide, which is not
     * Readium's: the reader turns the page itself there ([InsetTap]).
     */
    fun inset(x: Float, width: Float, insetPx: Float): InsetTap = when {
        insetPx <= 0f -> InsetTap.NONE
        x < insetPx -> InsetTap.BACK
        x > width - insetPx -> InsetTap.FORWARD
        else -> InsetTap.NONE
    }

    /** What a tap in the side inset does: left turns back, right turns on. */
    enum class InsetTap { NONE, BACK, FORWARD }
}

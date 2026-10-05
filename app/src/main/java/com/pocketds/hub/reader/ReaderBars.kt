package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.HintBarView
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Theme

/**
 * A reader's bars (#16, X7). On Glass they are the prototype's: a bar of
 * near-solid dark glass along the top and another along the foot, each
 * floating [INSET_DP] in from the edges with [CORNER_DP] corners and tinted by
 * the cover (OverlayButtons.panel, the player's glass), and the row of keys
 * across the very foot under them, where the app's hint bar sits. Classic
 * keeps flat bars edge to edge with the keys inside the lower one.
 *
 * A reader adds [top] and [bottom] to its root and hands them to
 * ReaderPagePreviewController. Each is as tall as everything it holds, so a
 * book's page makes room for exactly that much; over a comic they float. The
 * reader fills [topRow] and [bottomRow] with its controls.
 */
class ReaderBars(
    context: Context,
    colors: PocketColors,
    topRowDp: Int,
    private val bottomRowDp: Int,
    onKey: (PadAction) -> Unit
) {
    val glass: Boolean = Theme.isGlass(context)
    private val density = context.resources.displayMetrics.density
    val top = FrameLayout(context)
    val topRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
        clipChildren = false
    }
    val bottom = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val bottomRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        clipToPadding = false
        clipChildren = false
    }
    /** What the keys do, inside the controls: the app's own hint bar is hidden in a reader. */
    val keys: HintBarView = ReaderKeys.row(context, colors, onKey)

    /** How tall [top] and [bottom] are, in pixels: what a book's page makes room for. */
    val topHeight: Int = dp(if (glass) GAP_DP + topRowDp else topRowDp)
    var bottomHeight: Int = bottomHeightFor(true)
        private set

    init {
        if (glass) {
            OverlayButtons.panel(topRow, CORNER_DP)
            OverlayButtons.panel(bottomRow, CORNER_DP)
            top.addView(topRow, FrameLayout.LayoutParams(MATCH, dp(topRowDp)).apply {
                setMargins(dp(INSET_DP), dp(GAP_DP), dp(INSET_DP), 0)
            })
            bottom.addView(bottomRow, LinearLayout.LayoutParams(MATCH, dp(bottomRowDp)).apply {
                setMargins(dp(INSET_DP), 0, dp(INSET_DP), dp(GAP_DP))
            })
        } else {
            top.setBackgroundColor(BAR)
            bottom.setBackgroundColor(BAR)
            top.addView(topRow, FrameLayout.LayoutParams(MATCH, MATCH))
            bottom.addView(bottomRow, LinearLayout.LayoutParams(MATCH, dp(bottomRowDp)))
        }
        bottom.addView(keys, LinearLayout.LayoutParams(MATCH, dp(ReaderKeys.ROW_DP)))
    }

    fun topParams() = FrameLayout.LayoutParams(MATCH, topHeight, Gravity.TOP)

    fun bottomParams() = FrameLayout.LayoutParams(MATCH, bottomHeight, Gravity.BOTTOM)

    /**
     * Read along, the narration's dock takes the lower bar's place: the row
     * goes, the keys stay, and the dock sits [dockMargin] up from the foot.
     */
    fun showBottomRow(show: Boolean) {
        bottomRow.visibility = if (show) View.VISIBLE else View.GONE
        bottomHeight = bottomHeightFor(show)
        bottom.layoutParams?.let { it.height = bottomHeight; bottom.layoutParams = it }
    }

    /** Where a dock over the keys stands: the gap above them, as a bar would. */
    val dockMargin: Int get() = dp(ReaderKeys.ROW_DP + GAP_DP)

    private fun bottomHeightFor(row: Boolean): Int =
        dp(ReaderKeys.ROW_DP + if (row) bottomRowDp + (if (glass) GAP_DP else 0) else 0)

    private fun dp(value: Int): Int = (value * density + .5f).toInt()

    companion object {
        /** In from the screen's edges, and between a bar and what is under it. */
        const val INSET_DP = 8
        const val GAP_DP = 6
        const val CORNER_DP = 16f
        /** A row of the 44dp controls with their padding. */
        const val ROW_DP = 52
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Classic's bars over the page: the app's ground, nearly opaque. */
        val BAR = Color.argb(235, 10, 13, 18)
        /** Words on a bar that are not its title. */
        val SOFT_TEXT = Color.rgb(213, 219, 227)
    }
}

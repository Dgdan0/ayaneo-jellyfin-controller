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

/**
 * A reader's bars (#16, X7), the prototype's: a bar of near-solid dark glass
 * along the top and another along the foot, each floating [INSET_DP] in from
 * the edges with [CORNER_DP] corners and tinted by the cover
 * (OverlayButtons.panel, the player's glass), and the row of keys across the
 * very foot under them, where the app's hint bar sits.
 *
 * A reader adds [top] and [bottom] to its root and hands them to
 * ReaderPagePreviewController. Each is as tall as everything it holds, so a
 * book's page makes room for exactly that much; over a comic they float. The
 * reader fills [topRow] and [bottomRow] with its controls, or gives the lower
 * bar's place to a view of its own ([useAsLowerBar]: read along's player).
 */
class ReaderBars(
    context: Context,
    colors: PocketColors,
    topRowDp: Int,
    private val bottomRowDp: Int,
    onKey: (PadAction) -> Unit
) {
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
    val topHeight: Int = dp(GAP_DP + topRowDp)
    var bottomHeight: Int = bottomHeightFor(true)
        private set

    init {
        OverlayButtons.panel(topRow, CORNER_DP)
        OverlayButtons.panel(bottomRow, CORNER_DP)
        top.addView(topRow, FrameLayout.LayoutParams(MATCH, dp(topRowDp)).apply {
            setMargins(dp(INSET_DP), dp(GAP_DP), dp(INSET_DP), 0)
        })
        bottom.addView(bottomRow, LinearLayout.LayoutParams(MATCH, dp(bottomRowDp)).apply {
            setMargins(dp(INSET_DP), 0, dp(INSET_DP), dp(GAP_DP))
        })
        bottom.addView(keys, LinearLayout.LayoutParams(MATCH, dp(ReaderKeys.ROW_DP)))
    }

    fun topParams() = FrameLayout.LayoutParams(MATCH, topHeight, Gravity.TOP)

    fun bottomParams() = FrameLayout.LayoutParams(MATCH, bottomHeight, Gravity.BOTTOM)

    /** What stands as the lower bar instead of [bottomRow]: read along, the narration's dock. */
    var lowerBar: View? = null
        private set

    /**
     * Read along (#21), the narration's dock is the menu's lower bar: it stands
     * where a book's position row does, above the keys, inset as the row is,
     * and shows and hides with the bars, so the page makes room for it as it
     * does for the row. Its own height is [heightDp].
     */
    fun useAsLowerBar(view: View, heightDp: Int) {
        if (lowerBar === view) return
        lowerBar?.let(bottom::removeView)
        (view.parent as? ViewGroup)?.removeView(view)
        lowerBar = view
        bottomRow.visibility = View.GONE
        view.visibility = View.VISIBLE
        bottom.addView(view, 0, LinearLayout.LayoutParams(MATCH, dp(heightDp)).apply {
            setMargins(dp(INSET_DP), 0, dp(INSET_DP), dp(GAP_DP))
        })
        bottomHeight = dp(ReaderKeys.ROW_DP + heightDp + GAP_DP)
        bottom.layoutParams?.let { it.height = bottomHeight; bottom.layoutParams = it }
    }

    private fun bottomHeightFor(row: Boolean): Int =
        dp(ReaderKeys.ROW_DP + if (row) bottomRowDp + GAP_DP else 0)

    private fun dp(value: Int): Int = (value * density + .5f).toInt()

    companion object {
        /** In from the screen's edges, and between a bar and what is under it. */
        const val INSET_DP = 8
        const val GAP_DP = 6
        const val CORNER_DP = 16f
        /** A row of the 44dp controls with their padding. */
        const val ROW_DP = 52
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Words on a bar that are not its title. */
        val SOFT_TEXT = Color.rgb(213, 219, 227)
    }
}

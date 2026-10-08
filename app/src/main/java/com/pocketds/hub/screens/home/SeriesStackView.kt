package com.pocketds.hub.screens.home

import android.content.Context
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.screens.library.SeriesFan
import com.pocketds.hub.ui.CoverFanView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.textWeight

/**
 * A series on Books Home: a fan of its books, the first in front on the left and the others fanning right with the
 * book you are on lit (the same plan as the library's Series view and a series page's header, #54), the series'
 * name and "6 books · on #6" under it.
 *
 * Focus rings the front cover rather than the whole fan, which would be a box around empty corners, and opens the
 * fan as far as its box has room for.
 */
class SeriesStackView(
    context: Context,
    colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {

    var onFocused: (() -> Unit)? = null
    private val fan = CoverFanView(context, colors, CoverFanView.COVER_DP, SeriesFan.SMALL_SLOTS, CoverFanView.BOX_DP)
    private val title = TextView(context)
    private val line = TextView(context)
    private val ring = ThemeGradientDrawable.rounded(Styler.dp(context, 7f), android.graphics.Color.TRANSPARENT,
        Styler.dpInt(context, 3f), colors.focusRing)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val (width, height) = fan.boxDp
        fan.roomDp = width / 2f + SeriesFan.SPREAD_MARGIN_DP
        addView(fan, LayoutParams(dp(width), dp(height)))
        addView(title.apply {
            textSize = 12f
            textWeight(700)
            setTextColor(colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }, LayoutParams(dp(width), LayoutParams.WRAP_CONTENT))
        addView(line.apply {
            textSize = 11f
            setTextColor(GlassColors.QUIET)
            isSingleLine = true
            setPadding(0, dp(1), 0, 0)
        })
        Styler.makeFocusable(this)
        // The lift and haptic tick every card has; the ring goes on the front cover.
        FocusDecorator.attach(this, ringVisible)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            fan.setRing(if (focused && ringVisible()) ring else null)
            fan.spread(focused)
            if (focused) onFocused?.invoke()
        }
    }

    fun bind(item: ReadingShelves.SeriesShelfItem, loader: ImageLoader, imageUrl: (String) -> String) {
        title.text = item.title
        line.text = item.line
        contentDescription = "${item.title}, ${item.line}"
        fan.bindPlan(item.plan, loader, imageUrl)
        // A recycled stack keeps no ring from the series it showed before.
        fan.setRing(if (isFocused && ringVisible()) ring else null)
        fan.spread(isFocused)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
}

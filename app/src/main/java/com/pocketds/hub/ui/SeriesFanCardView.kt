package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.SeriesFan
import com.pocketds.hub.ui.glass.GlassColors

/**
 * A series in the Books library's Series view (#54): a fan of its books ([CoverFanView], standing as the
 * [SeriesFan.Plan] says), the series' name, "6 books · on #1" under it and the bar of the whole series under that.
 * The card is one focus target: a ring on the front cover and the fan opens a little, as on Books Home.
 */
class SeriesFanCardView(context: Context, colors: PocketColors, private val ringVisible: () -> Boolean) : LinearLayout(context) {
    private val fan = CoverFanView(context, colors, COVER_DP)
    private val title = TextView(context)
    private val caption = TextView(context)
    private val bar = SeriesBarView(context, colors)
    private val ring = ThemeGradientDrawable.rounded(Styler.dp(context, 7f), Color.TRANSPARENT, Styler.dpInt(context, 3f), colors.focusRing)
    /** What a tap on this card does, as of what it was bound to. */
    var target: SeriesFan.Target = SeriesFan.Target.OpenSeries(null)
        private set
    /** Told when the card gains or loses focus, after its own ring and opening (a card has one focus listener, so this is the way in). */
    var onFocus: ((Boolean) -> Unit)? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val (width, height) = CoverFanView.planSizeDp(COVER_DP)
        addView(fan, LayoutParams(dp(width), dp(height)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        addView(title.apply {
            textSize = 12f
            textWeight(700)
            setTextColor(colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(8), 0, 0)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(caption.apply {
            textSize = 11f
            setTextColor(GlassColors.QUIET)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            textAlignment = TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(1), 0, dp(5))
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // The same width for every series: the width of the fan at rest.
        addView(bar, LayoutParams(dp(BAR_DP), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        setPadding(0, 0, 0, dp(4))
        Styler.makeFocusable(this)
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        isClickable = true
        FocusDecorator.attach(this, ringVisible)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            fan.setRing(if (focused && ringVisible()) ring else null)
            fan.spread(focused)
            onFocus?.invoke(focused)
        }
    }

    fun bind(series: ReadingWork, plan: SeriesFan.Plan, loader: ImageLoader, imageUrl: (String) -> String) {
        title.text = series.title
        caption.text = plan.caption
        contentDescription = "${series.title}, ${plan.caption}"
        target = plan.target
        bar.show(plan.bar)
        fan.bindPlan(plan, loader, imageUrl)
        // A recycled card keeps no ring from the series it showed before.
        fan.setRing(if (isFocused && ringVisible()) ring else null)
        fan.spread(isFocused)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        /** A cover of the fan; five slots of them and the room they lean into fit four to a row of the Pocket's grid. */
        const val COVER_DP = 56
        /** The bar's width, the same for every series: about that of the fan. */
        private const val BAR_DP = 120
    }
}

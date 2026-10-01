package com.pocketds.hub.screens.home

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.textWeight

/**
 * A series as a little fan of its covers: the book you are on in front, two
 * more tilted behind, the series' name and "6 books · on #6" under them.
 *
 * Focus rings the front cover rather than the whole fan, which would be a box
 * around empty corners.
 */
class SeriesStackView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {

    var onFocused: (() -> Unit)? = null
    private val covers = List(3) { ImageView(context) }
    private val title = TextView(context)
    private val line = TextView(context)
    private val ring = ThemeGradientDrawable.rounded(Styler.dp(context, 7f), android.graphics.Color.TRANSPARENT,
        Styler.dpInt(context, 2.5f), colors.focusRing)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        val fan = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
        // Back to front: the third book, the second, then the one you are on.
        val placement = listOf(Triple(44, 4, 8f), Triple(22, 2, -3f), Triple(4, 6, -9f))
        placement.forEachIndexed { i, (left, top, angle) ->
            val cover = covers[2 - i]
            cover.apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ThemeGradientDrawable.rounded(Styler.dp(context, 6f), colors.posterPlaceholder)
                clipToOutline = true
                rotation = angle
                elevation = Styler.dp(context, 6f + i * 2f)
            }
            fan.addView(cover, FrameLayout.LayoutParams(dp(78), dp(117)).apply { leftMargin = dp(left); topMargin = dp(top) })
        }
        addView(fan, LayoutParams(dp(150), dp(128)))
        addView(title.apply {
            textSize = 13f
            textWeight(600)
            setTextColor(colors.primaryText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }, LayoutParams(dp(150), LayoutParams.WRAP_CONTENT))
        addView(line.apply { textSize = 11f; setTextColor(colors.mutedText); isSingleLine = true })
        Styler.makeFocusable(this)
        // The lift and haptic tick every card has; the ring goes on the front cover.
        FocusDecorator.attach(this, ringVisible)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            covers[0].foreground = if (focused && ringVisible()) ring else null
            if (focused) onFocused?.invoke()
        }
    }

    fun bind(item: ReadingShelves.SeriesShelfItem, loader: ImageLoader, imageUrl: (String) -> String) {
        title.text = item.title
        line.text = item.line
        contentDescription = "${item.title}, ${item.line}"
        covers.forEachIndexed { i, view ->
            val path = item.covers.getOrNull(i)
            // A series of one book still fans: the empty cards behind it read as "a series".
            Artwork.bind(view, loader, path?.let(imageUrl), opaque = true)
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
}

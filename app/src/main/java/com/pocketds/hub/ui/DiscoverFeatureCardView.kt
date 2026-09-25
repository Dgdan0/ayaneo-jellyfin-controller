package com.pocketds.hub.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest

/** A single editorial-looking treatment of an existing Discover result. */
class DiscoverFeatureCardView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val kicker = TextView(context).apply { textSize = 11f; setTextColor(colors.accent); includeFontPadding = false }
    private val heading = TextView(context).apply {
        textSize = 21f; setTextColor(colors.primaryText); maxLines = 2
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private val meta = TextView(context).apply {
        textSize = 13f; setTextColor(colors.mutedText); maxLines = 2
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private val overview = TextView(context).apply {
        textSize = 12f; setTextColor(colors.mutedText); maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private var imageKey = ""

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        minimumHeight = Styler.dpInt(context, 128f)
        fun face(focused: Boolean) = ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 13f)
            setColor(this@DiscoverFeatureCardView.colors.cardSurface)
            if (focused) setStroke(Styler.dpInt(context, 2f), this@DiscoverFeatureCardView.colors.focusRing)
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(true))
            addState(intArrayOf(), face(false))
        }
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        addView(art, LayoutParams(Styler.dpInt(context, 92f), Styler.dpInt(context, 128f)))
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Styler.dpInt(context, 20f), Styler.dpInt(context, 9f),
                Styler.dpInt(context, 18f), Styler.dpInt(context, 9f))
        }
        copy.addView(kicker, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        copy.addView(heading, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Styler.dpInt(context, 6f)
        })
        copy.addView(meta, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Styler.dpInt(context, 5f)
        })
        copy.addView(overview, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Styler.dpInt(context, 6f)
        })
        addView(copy, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun bind(title: String, subtitle: String, description: String, artworkUrl: String,
             landscape: Boolean, loader: ImageLoader) {
        kicker.text = "FEATURED"
        heading.text = title
        meta.text = subtitle
        overview.text = description
        overview.visibility = if (description.isBlank()) GONE else VISIBLE
        contentDescription = listOf(title, subtitle).filter(String::isNotBlank).joinToString(", ")
        val params = art.layoutParams as LayoutParams
        params.width = Styler.dpInt(context, if (landscape) 216f else 92f)
        art.layoutParams = params
        if (artworkUrl != imageKey) {
            imageKey = artworkUrl
            loader.enqueue(ImageRequest.Builder(context).data(artworkUrl).target(art).build())
        }
    }
}

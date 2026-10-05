package com.pocketds.hub.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader

/**
 * A single editorial-looking treatment of an existing Discover result: the
 * prototype's featured card (`.feat`), a glass card, the picture filling its
 * left half and more, "FEATURED · NOT IN YOUR LIBRARY" over a display title
 * on the right.
 */
class DiscoverFeatureCardView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val kicker = TextView(context).apply { typeRole(Type.Role.EYEBROW); setTextColor(colors.accent) }
    private val heading = TextView(context).apply {
        typeRole(Type.Role.HERO, 24f); setTextColor(colors.primaryText); maxLines = 2
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
        val corner = Styler.dp(context, CORNER_DP)
        com.pocketds.hub.ui.glass.GlassPanelDrawable.attach(this, corner)
        // The picture is cut to the card's corners by an outline the panel does not give.
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, corner)
        }
        heading.typeface = Type.display(context, 800)
        heading.textSize = 19f
        meta.textSize = 12f
        meta.setTextColor(META)
        overview.textSize = 11.5f
        overview.setTextColor(OVERVIEW)
        kicker.textSize = 10.5f
        kicker.setTextColor(KICKER)
        clipToOutline = true
        foreground = Styler.focusOutline(context, colors, CORNER_DP, 3f)
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        addView(art, LayoutParams(Styler.dpInt(context, 92f), Styler.dpInt(context, 128f)))
        val copy = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(Styler.dpInt(context, 16f), Styler.dpInt(context, 10f), Styler.dpInt(context, 16f), Styler.dpInt(context, 10f))
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

    /** [mark]: where the title stands, after FEATURED in the accent ("NOT IN YOUR LIBRARY"). */
    fun bind(title: String, subtitle: String, description: String, artworkUrl: String,
             loader: ImageLoader, mark: String = "") {
        kicker.text = if (mark.isNotBlank()) android.text.SpannableStringBuilder("FEATURED · ").apply {
            val start = length
            append(mark.uppercase())
            setSpan(android.text.style.ForegroundColorSpan(colors.accent), start, length, 0)
        } else "FEATURED"
        heading.text = title
        meta.text = subtitle
        overview.text = description
        overview.visibility = if (description.isBlank()) GONE else VISIBLE
        contentDescription = listOf(title, subtitle).filter(String::isNotBlank).joinToString(", ")
        val params = art.layoutParams as LayoutParams
        // The prototype's picture: a little over half the card, as tall as the card.
        params.width = 0
        params.weight = 1.15f
        params.height = Styler.dpInt(context, HEIGHT_DP)
        art.layoutParams = params
        if (artworkUrl != imageKey) {
            imageKey = artworkUrl
            Artwork.bind(art, loader, artworkUrl, opaque = true)
        }
    }

    private companion object {
        const val CORNER_DP = 16f
        const val HEIGHT_DP = 176f
        /** The eyebrow white at 72%, the facts at 82%, the overview at 78%. */
        const val KICKER = com.pocketds.hub.ui.glass.GlassColors.EYEBROW
        const val META = com.pocketds.hub.ui.glass.GlassColors.FACTS
        const val OVERVIEW = 0xC7FFFFFF.toInt()
    }
}

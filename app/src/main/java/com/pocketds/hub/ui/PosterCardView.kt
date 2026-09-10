package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader
import coil.request.ImageRequest
import com.pocketds.hub.model.Availability
import com.pocketds.hub.model.SearchHit

/**
 * One title in a grid: poster, badge, title, subtitle.
 *
 * The placeholder is a flat colour rather than a spinner. Twenty-five spinning
 * progress bars is twenty-five running animators and a screen that reads as
 * broken; a flat fill lets the grid appear instantly as a grid and fill in.
 */
class PosterCardView(
    context: Context,
    private val colors: PocketColors,
    /**
     * Poster height in dp. The card is sized from this because a poster is
     * fixed at 2:3, so one number decides the whole card -- and on a 456dp-tall
     * landscape screen that number is what decides whether you can see one row
     * of content or two.
     */
    posterHeightDp: Float = 190f
) : LinearLayout(context) {

    private val poster: ImageView
    private val badge: TextView
    private val title: TextView
    private val subtitle: TextView
    private val progressBar: android.view.View
    private val compactCard: Boolean
    private var boundProgress = 0.0

    init {
        orientation = VERTICAL
        background = Styler.cardBackground(context, colors)
        Styler.makeFocusable(this)
        isClickable = true
        // The whole card is one focus target. Without this the image, title and
        // badge are three, and focus appears to wander inside a single item.
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        // Padding and type scale with the card. A 6dp inset and 13sp title look
        // right at 190dp and waste a third of a 140dp card.
        val compact = posterHeightDp < 170f
        val pad = Styler.dpInt(context, if (compact) 4f else 6f)
        setPadding(pad, pad, pad, pad)

        val posterWrap = FrameLayout(context)
        poster = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(MATCH, Styler.dpInt(context, posterHeightDp))
            setBackgroundColor(colors.posterPlaceholder)
        }
        posterWrap.addView(poster)

        badge = TextView(context).apply {
            textSize = 10f
            setTextColor(colors.accentText)
            val h = Styler.dpInt(context, 6f)
            val v = Styler.dpInt(context, 3f)
            setPadding(h, v, h, v)
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = Styler.dpInt(context, 4f)
                marginEnd = Styler.dpInt(context, 4f)
            }
        }
        posterWrap.addView(badge)

        // A thin bar along the bottom of the poster while something is actually
        // downloading -- readable at a glance without reading any text.
        progressBar = android.view.View(context).apply {
            setBackgroundColor(colors.accent)
            visibility = GONE
            layoutParams = FrameLayout.LayoutParams(0, Styler.dpInt(context, 4f)).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            }
        }
        posterWrap.addView(progressBar)
        addView(posterWrap, LayoutParams(MATCH, WRAP))
        poster.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateProgressWidth()
        }

        title = TextView(context).apply {
            textSize = if (compact) 11f else 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colors.primaryText)
            setPadding(0, Styler.dpInt(context, if (compact) 3f else 6f), 0, 0)
        }
        addView(title)

        subtitle = TextView(context).apply {
            textSize = if (compact) 9f else 11f
            maxLines = 1
            setTextColor(colors.mutedText)
            // On a compact card the year alone is worth the line; anything
            // longer just crowds the title it sits under.
            visibility = if (compact) GONE else VISIBLE
        }
        addView(subtitle)
        compactCard = compact
    }

    fun bind(hit: SearchHit, imageLoader: ImageLoader, imageUrl: (String) -> String) =
        bind(hit, imageLoader, imageUrl, showAvailability = true)

    fun bind(
        hit: SearchHit,
        imageLoader: ImageLoader,
        imageUrl: (String) -> String,
        showAvailability: Boolean
    ) {
        title.text = hit.media.title
        subtitle.text = hit.subtitle
        if (compactCard) subtitle.visibility = GONE

        val availability = Availability.fromWire(hit.availability)
        val libraryBadge = when {
            hit.played -> "✓"
            hit.unplayedCount > 0 -> hit.unplayedCount.toString()
            hit.favorite -> "★"
            else -> ""
        }
        if (!showAvailability && libraryBadge.isNotEmpty()) {
            badge.visibility = VISIBLE
            badge.text = libraryBadge
            badge.background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(
                    if (hit.played) this@PosterCardView.colors.badgeAvailable
                    else this@PosterCardView.colors.accent
                )
            }
            badge.minWidth = Styler.dpInt(context, 24f)
            badge.gravity = Gravity.CENTER
        } else if (showAvailability && availability.label.isNotEmpty()) {
            badge.visibility = VISIBLE
            badge.text = availability.label
            badge.setBackgroundColor(badgeColour(availability))
        } else {
            badge.visibility = GONE
        }

        boundProgress = if (hit.played) 0.0 else hit.progress.coerceIn(0.0, 1.0)
        if (boundProgress > 0.0) {
            progressBar.visibility = VISIBLE
            updateProgressWidth()
            // RecyclerView normally binds before the poster has a measured
            // width. Recompute after layout so 50% is really half the poster,
            // rather than the old two-pixel fallback.
            poster.post(::updateProgressWidth)
        } else {
            progressBar.visibility = GONE
        }

        poster.setImageDrawable(ColorDrawable(colors.posterPlaceholder))
        val url = imageUrl(hit.media.poster)
        if (url.isNotEmpty()) {
            imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .target(poster)
                    // RGB_565 halves the memory of a grid thumbnail, and banding
                    // is invisible on a photographic poster at this size.
                    .bitmapConfig(Bitmap.Config.RGB_565)
                    .build()
            )
        }
    }

    private fun updateProgressWidth() {
        if (boundProgress <= 0.0 || poster.width <= 0) return
        val params = progressBar.layoutParams as FrameLayout.LayoutParams
        val next = (poster.width * boundProgress).toInt()
            .coerceAtLeast(Styler.dpInt(context, 2f))
        if (params.width != next) {
            params.width = next
            progressBar.layoutParams = params
        }
    }

    private fun badgeColour(availability: Availability): Int = when (availability) {
        Availability.AVAILABLE -> colors.badgeAvailable
        // Pink, matching what Jellyfin and the *arr apps use for "monitored but
        // incomplete". Lumping it in with green said "you have this", which for
        // a series missing half its episodes is not true.
        Availability.PARTIAL -> colors.badgePartial
        Availability.DOWNLOADING, Availability.PROCESSING, Availability.REQUESTED ->
            colors.badgePending
        Availability.BLOCKED, Availability.DELETED -> colors.badgeFailed
        else -> colors.mutedText
    }

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

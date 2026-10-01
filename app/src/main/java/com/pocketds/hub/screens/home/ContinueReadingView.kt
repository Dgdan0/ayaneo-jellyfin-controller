package com.pocketds.hub.screens.home

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.ImageLoader
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.ReadingBookFacts
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ProgressLine
import com.pocketds.hub.ui.ProgressLine.showFraction
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.typeRole

/**
 * The book you are reading, at the top of Books Home: its cover, where it sits
 * in its series ("Red Rising #6 · Pierce Brown"), how far through, and Resume
 * reading beside Details. The screen owns what the buttons do.
 */
class ContinueReadingView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {

    val resume: TextView = PillButton.create(context, colors, "Resume reading", AppIcon.BOOK, primary = true, heightDp = 36f)
    val details: TextView = PillButton.create(context, colors, "Details", AppIcon.INFO, heightDp = 36f)
    private val cover = ImageView(context)
    private val title = TextView(context)
    private val place = TextView(context)
    private val bar = ProgressLine.create(context, colors)
    private val progress = TextView(context)
    var work: ReadingWork? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = ThemeGradientDrawable.rounded(Styler.dp(context, 18f), colors.cardSurface, dp(1),
            ColorUtils.setAlphaComponent(colors.accent, 90))
        clipChildren = false
        cover.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 8f), colors.posterPlaceholder)
            clipToOutline = true
            elevation = Styler.dp(context, 10f)
        }
        addView(cover, LayoutParams(dp(104), dp(156)))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        column.addView(TextView(context).apply {
            text = "Continue reading"
            isAllCaps = true
            typeRole(Type.Role.EYEBROW)
            setTextColor(colors.accent)
        })
        column.addView(title.apply {
            typeRole(Type.Role.HERO, 26f)
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        column.addView(place.apply {
            textSize = 12f
            setTextColor(colors.mutedText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
        })
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
            addView(bar, LayoutParams(0, dp(6), 1f))
            addView(progress.apply { textSize = 11f; setTextColor(colors.mutedText); setPadding(dp(10), 0, 0, 0) })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            setPadding(0, dp(10), 0, 0)
            addView(resume)
            addView(details, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        })
        addView(column, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        listOf(resume, details).forEach { FocusDecorator.attach(it, ringVisible, scale = false) }
    }

    fun bind(next: ReadingWork, loader: ImageLoader, imageUrl: (String) -> String) {
        val coverChanged = next.artwork != work?.artwork
        work = next
        title.text = next.title
        place.text = SpannableStringBuilder().apply {
            if (next.series.isNotBlank()) {
                append(next.series)
                if (next.seriesNumber.isNotBlank()) {
                    val start = length
                    append(" #").append(next.seriesNumber)
                    setSpan(ForegroundColorSpan(colors.accent), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            if (next.byline.isNotBlank()) {
                if (isNotEmpty()) append("  ·  ")
                append(next.byline)
            }
        }
        place.visibility = if (place.text.isNullOrBlank()) GONE else VISIBLE
        val fraction = next.progress?.let { if (it.completed) 1.0 else it.percentage } ?: 0.0
        bar.showFraction(fraction)
        progress.text = ReadingBookFacts.progress(next).orEmpty()
        resume.contentDescription = "Resume reading ${next.title}"
        details.contentDescription = "Details for ${next.title}"
        if (coverChanged) Artwork.bind(cover, loader, next.artwork.takeIf(String::isNotBlank)?.let(imageUrl), opaque = true)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
}

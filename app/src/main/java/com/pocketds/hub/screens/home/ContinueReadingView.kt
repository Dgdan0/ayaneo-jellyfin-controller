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
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassProgressBar
import com.pocketds.hub.ui.typeRole

/**
 * The book you are reading, at the top of Books Home: its cover, where it sits
 * in its series ("Red Rising #6 · Pierce Brown"), how far through, and Resume
 * reading beside Details. The screen owns what the buttons do.
 *
 * On Glass it is the prototype's (`.reading`): no card, the cover at full
 * size with the words beside its foot, the title in the display face, the
 * series number and the bar in the accent, Resume reading white and Details
 * glass.
 */
class ContinueReadingView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {

    private val glass = Theme.onGlass(colors)
    val resume: TextView = PillButton.create(context, colors, "Resume reading", AppIcon.BOOK, primary = true,
        heightDp = if (glass) GLASS_BUTTON_DP else 36f, glass = glass)
    val details: TextView = PillButton.create(context, colors, "Details", AppIcon.INFO,
        heightDp = if (glass) GLASS_BUTTON_DP else 36f, glass = glass)
    private val cover = ImageView(context)
    private val title = TextView(context)
    private val place = TextView(context)
    private val bar = ProgressLine.create(context, colors)
    private val glassBar = GlassProgressBar(context, colors.accent, GlassColors.TRACK)
    private val progress = TextView(context)
    var work: ReadingWork? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = if (glass) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        clipChildren = false
        if (glass) {
            clipToPadding = false
        } else {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ThemeGradientDrawable.rounded(Styler.dp(context, 18f), colors.cardSurface, dp(1),
                ColorUtils.setAlphaComponent(colors.accent, 90))
        }
        cover.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, if (glass) GLASS_COVER_CORNER_DP else 8f), colors.posterPlaceholder)
            clipToOutline = true
            elevation = Styler.dp(context, if (glass) 14f else 10f)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Glass: its foot level with the pills' (their ring's room lies below them).
        addView(cover, if (glass) LayoutParams(dp(GLASS_COVER_DP), dp(GLASS_COVER_DP * 3 / 2)).apply {
            bottomMargin = dp(PillButton.RING_DP.toInt())
        } else LayoutParams(dp(104), dp(156)))
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(if (glass) GLASS_GAP_DP else 18), 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        column.addView(TextView(context).apply {
            text = "Continue reading"
            isAllCaps = true
            typeRole(Type.Role.EYEBROW, if (glass) 10.5f else Type.Role.EYEBROW.sizeSp)
            setTextColor(if (glass) GlassColors.EYEBROW else colors.accent)
        })
        column.addView(title.apply {
            typeRole(Type.Role.HERO, if (glass) GLASS_TITLE_SP else 26f)
            // The prototype's title is Bricolage at its heaviest, set close.
            if (glass) { typeface = Type.display(context, 800); setLineSpacing(0f, .95f) }
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (glass) GLASS_LINE_GAP_DP else 7), 0, 0)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        column.addView(place.apply {
            textSize = 12f
            setTextColor(if (glass) GlassColors.FACTS else colors.mutedText)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(if (glass) GLASS_LINE_GAP_DP else 7), 0, 0)
        })
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(if (glass) GLASS_LINE_GAP_DP + 2 else 9), 0, 0)
            if (glass) addView(glassBar, LayoutParams(dp(GLASS_BAR_DP), dp(GlassProgressBar.HEIGHT_DP.toInt())))
            else addView(bar, LayoutParams(0, dp(6), 1f))
            addView(progress.apply {
                textSize = if (glass) 12f else 11f
                setTextColor(if (glass) GlassColors.FACTS else colors.mutedText)
                setPadding(dp(if (glass) 12 else 10), 0, 0, 0)
            })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ring = dp(PillButton.RING_DP.toInt())
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            setPadding(0, dp(if (glass) GLASS_BUTTONS_GAP_DP else 10), 0, 0)
            addView(resume)
            addView(details, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = if (glass) dp(GLASS_BUTTON_GAP_DP) - 2 * ring else dp(6)
            })
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // The pill itself lines up with the words; its ring has room to the left.
            if (glass) marginStart = -ring
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
        glassBar.fraction = fraction
        progress.text = ReadingBookFacts.progress(next).orEmpty()
        resume.contentDescription = "Resume reading ${next.title}"
        details.contentDescription = "Details for ${next.title}"
        if (coverChanged) Artwork.bind(cover, loader, next.artwork.takeIf(String::isNotBlank)?.let(imageUrl), opaque = true)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        /**
         * The prototype's Pocket `.reading`: a 112dp cover, the words 18dp
         * beside it and 6dp apart, the 30sp title, a long accent bar, and the
         * 31dp pills 10dp apart, as on Home's hero.
         */
        const val GLASS_COVER_DP = 112
        const val GLASS_COVER_CORNER_DP = 9f
        const val GLASS_GAP_DP = 18
        const val GLASS_LINE_GAP_DP = 6
        const val GLASS_TITLE_SP = 30f
        const val GLASS_BAR_DP = 260
        const val GLASS_BUTTONS_GAP_DP = 8
        const val GLASS_BUTTON_DP = 31f
        const val GLASS_BUTTON_GAP_DP = 10
    }
}

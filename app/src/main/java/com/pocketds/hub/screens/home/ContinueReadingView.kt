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
import coil.ImageLoader
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.screens.library.ReadingBookFacts
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
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
 * It is the prototype's (`.reading`): no card, the cover at full size with
 * the words beside its foot, the title in the display face, the series
 * number and the bar in the accent, Resume reading gold and Details glass.
 */
class ContinueReadingView(
    context: Context,
    private val colors: PocketColors,
    ringVisible: () -> Boolean
) : LinearLayout(context) {

    /** Gold, as the Books side's main action is (PillButton.mainFace). */
    val resume: TextView = PillButton.create(context, colors, "Resume reading", AppIcon.BOOK, primary = true,
        heightDp = BUTTON_DP, side = com.pocketds.hub.state.ContentMode.BOOKS)
    val details: TextView = PillButton.create(context, colors, "Details", AppIcon.INFO,
        heightDp = BUTTON_DP)
    private val cover = ImageView(context)
    private val title = TextView(context)
    private val place = TextView(context)
    private val bar = GlassProgressBar(context, colors.accent, GlassColors.TRACK)
    private val progress = TextView(context)
    var work: ReadingWork? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        clipChildren = false
        clipToPadding = false
        cover.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ThemeGradientDrawable.rounded(Styler.dp(context, COVER_CORNER_DP), colors.posterPlaceholder)
            clipToOutline = true
            elevation = Styler.dp(context, 14f)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // Its foot level with the pills' (their ring's room lies below them).
        addView(cover, LayoutParams(dp(COVER_DP), dp(COVER_DP * 3 / 2)).apply {
            bottomMargin = dp(PillButton.RING_DP.toInt())
        })
        val column = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(GAP_DP), 0, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        column.addView(TextView(context).apply {
            text = "Continue reading"
            isAllCaps = true
            typeRole(Type.Role.EYEBROW, 10.5f)
            setTextColor(GlassColors.EYEBROW)
        })
        column.addView(title.apply {
            typeRole(Type.Role.HERO, TITLE_SP)
            // The prototype's title is Bricolage at its heaviest, set close.
            typeface = Type.display(context, 800); setLineSpacing(0f, .95f)
            setTextColor(colors.primaryText)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(LINE_GAP_DP), 0, 0)
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        })
        column.addView(place.apply {
            textSize = 12f
            setTextColor(GlassColors.FACTS)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(LINE_GAP_DP), 0, 0)
        })
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(LINE_GAP_DP + 2), 0, 0)
            addView(bar, LayoutParams(dp(BAR_DP), dp(GlassProgressBar.HEIGHT_DP.toInt())))
            addView(progress.apply {
                textSize = 12f
                setTextColor(GlassColors.FACTS)
                setPadding(dp(12), 0, 0, 0)
            })
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val ring = dp(PillButton.RING_DP.toInt())
        column.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            setPadding(0, dp(BUTTONS_GAP_DP), 0, 0)
            addView(resume)
            addView(details, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(BUTTON_GAP_DP) - 2 * ring
            })
        }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // The pill itself lines up with the words; its ring has room to the left.
            marginStart = -ring
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
        bar.fraction = fraction
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
        const val COVER_DP = 112
        const val COVER_CORNER_DP = 9f
        const val GAP_DP = 18
        const val LINE_GAP_DP = 6
        const val TITLE_SP = 30f
        const val BAR_DP = 260
        const val BUTTONS_GAP_DP = 8
        const val BUTTON_DP = 31f
        const val BUTTON_GAP_DP = 10
    }
}

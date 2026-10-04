package com.pocketds.hub.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import coil.ImageLoader

/** "S1E4 · Mindy's Back", exactly as the hub writes an episode subtitle. */
object EpisodeLabel {
    fun of(season: Int, episode: Int, title: String): String =
        listOf(code(season, episode), title).filter(String::isNotBlank).joinToString(" · ")

    /** Season 0 is where Jellyfin and Sonarr keep specials. */
    fun season(number: Int): String = if (number == 0) "Specials" else "Season $number"

    /** "S1E4", or empty when the episode has no number. */
    fun code(season: Int, episode: Int): String =
        if (season > 0 || episode > 0) "S${season}E$episode" else ""
}

/**
 * One episode, wherever it appears: a Library season, the release picker, a
 * downloaded season, the download picker.
 *
 * Those were four separate cards -- 270, 270, 230 and 224dp wide, numbered
 * "S1E1", "E01" and "E1", with a progress line on one and a description on
 * two -- so the same episode looked different on every screen that showed it.
 * The still carries the watch progress the way Home's landscape cards do.
 *
 * [glass] is a title page's episode in Glass (GLASS_PLAN.md): Home's tile --
 * 11dp corners, the progress as a white bar inside the still, a tick in the
 * accent, a glass play disc on focus ([GlassStillMarks]) -- with UP NEXT at its
 * top left.
 */
class EpisodeCardView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    /** For the download picker: no description, and room for a selection mark. */
    compact: Boolean = false,
    private val glass: Boolean = false
) : LinearLayout(context) {

    data class Model(
        val title: String,
        val meta: String,
        /** A hub URL or a local file; null shows the placeholder. */
        val still: Any?,
        val progress: Double = 0.0,
        val overview: String = "",
        /** null: no mark. Otherwise a ✓ or ○ in the corner, for pickers. */
        val marked: Boolean? = null,
        val available: Boolean = true,
        val description: String = "",
        /** A word in the corner: "UP NEXT" on the episode Play would start. */
        val badge: String = "",
        /** A small accent tick in the corner, when no badge or picker mark is there. */
        val watched: Boolean = false
    )

    var onFocused: (() -> Unit)? = null
    var onActivate: (() -> Unit)? = null

    private val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val progress = ArtworkProgressView(context, colors.accent)
    private val mark = TextView(context).apply {
        gravity = Gravity.CENTER; textSize = 13f
        background = ThemeGradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor((this@EpisodeCardView.colors.background and 0x00FFFFFF) or 0xC0000000.toInt())
        }
        visibility = GONE
    }
    private val badge = TextView(context).apply {
        textSize = if (glass) 10f else 9f; textWeight(if (glass) 800 else 700); letterSpacing = if (glass) .08f else .04f
        setTextColor(colors.accentText)
        if (glass) setPadding(dp(8), dp(4), dp(8), dp(4)) else setPadding(dp(7), dp(2), dp(7), dp(2))
        background = ThemeGradientDrawable().apply { cornerRadius = Styler.dp(context, 999f); setColor(this@EpisodeCardView.colors.accent) }
        visibility = GONE
    }
    /** The play mark a focused episode shows, where A would start it. */
    private val playMark = android.widget.ImageView(context).apply {
        setImageDrawable(AppIconDrawable(AppIcon.PLAY, colors.inverseText))
        val pad = dp(10)
        setPadding(pad + dp(1), pad, pad - dp(1), pad)
        background = ThemeGradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor((this@EpisodeCardView.colors.primaryText and 0x00FFFFFF) or 0xEB000000.toInt())
        }
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = text(if (glass) 12f else 12.5f, colors.primaryText, 1).apply { textWeight(if (glass) 700 else 600) }
    private val meta = text(11f, if (glass) GLASS_META else colors.mutedText, 1)
    /** Glass: the progress inside the still, the play disc on focus, the accent tick. */
    private var marks: com.pocketds.hub.ui.glass.GlassStillMarks? = null
    private val overview = text(10f, colors.mutedText, 2)
    /** Episode strips that play on A show the play mark on focus; pickers do not. */
    var showsPlayOnFocus = false

    init {
        // Focus is a ring round the still, as on every other card; the words
        // under it are not boxed in.
        orientation = VERTICAL
        background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setPadding(0, 0, 0, dp(4))
        val corner = if (glass) ArtworkFrame.GLASS_CORNER_DP else ArtworkFrame.CORNER_DP
        val art = ArtworkFrame(context, 16f / 9f, corner).apply {
            isDuplicateParentStateEnabled = true
            foreground = Styler.focusOutline(context, colors, corner, if (glass) 3f else 2f)
        }
        art.addView(still, FrameLayout.LayoutParams(MATCH, MATCH))
        if (glass) marks = com.pocketds.hub.ui.glass.GlassStillMarks(context, colors, art)
        else art.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.BOTTOM))
        art.addView(mark, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(6); marginEnd = dp(6)
        })
        // Glass puts UP NEXT at the top left, where the prototype has it, clear of the tick.
        art.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or if (glass) Gravity.START else Gravity.END).apply {
            topMargin = dp(if (glass) 8 else 6); marginEnd = dp(6); marginStart = dp(8)
        })
        if (!glass) art.addView(playMark, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        addView(art, LayoutParams(MATCH, WRAP))
        addView(title, LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        addView(meta, LayoutParams(MATCH, WRAP).apply { topMargin = dp(2) })
        if (!compact) addView(overview, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        FocusDecorator.attach(this, ringVisible)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            playMark.visibility = if (focused && showsPlayOnFocus) VISIBLE else GONE
            if (showsPlayOnFocus) marks?.focus(focused)
            if (focused) onFocused?.invoke()
        }
        activateOnTap { onActivate?.invoke() }
    }

    /**
     * Always its natural height: the still's 16:9 plus its text. The release
     * picker gives its row only the space left over and relies on cards
     * drawing past it, and a card that took that height was squashed to a
     * sliver.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))

    fun bind(model: Model, loader: ImageLoader) {
        title.text = model.title
        meta.text = model.meta
        overview.text = model.overview
        progress.fraction = model.progress
        alpha = if (model.available) 1f else .45f
        badge.text = model.badge
        badge.visibility = if (model.badge.isNotBlank()) VISIBLE else GONE
        val glassMarks = marks
        if (glassMarks != null) {
            // A picker's own mark stays; a watched episode gets the accent tick.
            glassMarks.bind(model.progress, model.watched && model.marked == null)
            setMarked(model.marked)
        } else setMarked(model.marked ?: if (model.watched && model.badge.isBlank()) true else null)
        contentDescription = model.description.ifBlank { listOf(model.title, model.meta).filter(String::isNotBlank).joinToString(", ") }
        Artwork.bind(still, loader, model.still, opaque = true, placeholderColor = colors.posterPlaceholder)
    }

    fun setMarked(marked: Boolean?) {
        mark.visibility = if (marked == null) GONE else VISIBLE
        mark.text = if (marked == true) "✓" else "○"
        mark.setTextColor(if (marked == true) colors.accent else colors.mutedText)
    }

    private fun text(size: Float, color: Int, lines: Int) = TextView(context).apply {
        textSize = size; setTextColor(color); maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        // A Hebrew title starts at the card's left edge like every other, not its right.
        textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
        setPadding(dp(1), 0, dp(1), 0)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val WIDTH_DP = 270
        const val COMPACT_WIDTH_DP = 224
        /** On a detail page, under the tabs: four and a bit across. */
        const val STRIP_WIDTH_DP = 180
        /** Glass: the prototype's Pocket episode, 176dp wide. */
        const val GLASS_STRIP_WIDTH_DP = 176
        /** Glass: an episode's second line, white at 64%. */
        private const val GLASS_META = 0xA3FFFFFF.toInt()
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

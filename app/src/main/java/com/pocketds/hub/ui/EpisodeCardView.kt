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
 */
class EpisodeCardView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    /** For the download picker: no description, and room for a selection mark. */
    compact: Boolean = false
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
        val description: String = ""
    )

    var onFocused: (() -> Unit)? = null
    var onActivate: (() -> Unit)? = null

    private val still = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val progress = ArtworkProgressView(context, colors.accent)
    private val mark = TextView(context).apply {
        gravity = Gravity.CENTER; textSize = 15f
        background = Styler.cardBackground(context, colors, cornerDp = 14f)
        visibility = GONE
    }
    private val title = text(14f, colors.primaryText, 1)
    private val meta = text(11f, colors.mutedText, 1)
    private val overview = text(10f, colors.mutedText, 2)

    init {
        orientation = VERTICAL
        background = Styler.cardBackground(context, colors)
        Styler.makeFocusable(this)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setPadding(dp(7), dp(7), dp(7), dp(9))
        val art = ArtworkFrame(context, 16f / 9f)
        art.addView(still, FrameLayout.LayoutParams(MATCH, MATCH))
        art.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.BOTTOM))
        art.addView(mark, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(6); marginEnd = dp(6)
        })
        addView(art, LayoutParams(MATCH, WRAP))
        addView(title, LayoutParams(MATCH, WRAP).apply { topMargin = dp(7) })
        addView(meta, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        if (!compact) addView(overview, LayoutParams(MATCH, WRAP).apply { topMargin = dp(3) })
        FocusDecorator.attach(this, ringVisible)
        setOnFocusChangeListener { _, focused ->
            FocusDecorator.refresh(this, ringVisible())
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
        setMarked(model.marked)
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
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val WIDTH_DP = 270
        const val COMPACT_WIDTH_DP = 224
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

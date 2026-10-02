package com.pocketds.hub.screens.home

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import coil.dispose
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubEndpoints
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.Artwork
import com.pocketds.hub.ui.ArtworkProgressView
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ScrimDrawable
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.typeRole

/**
 * The top of Home: the focused card's backdrop, title, a line of facts, the
 * start of its overview, and Play / Details.
 *
 * The rows sit over its lower edge, the way a streaming app's home does; this
 * view only draws the art and the words and owns the two buttons.
 */
class HomeHeroView(
    context: Context,
    private val colors: PocketColors,
    private val api: HubApi,
    ringVisible: () -> Boolean
) : FrameLayout(context) {
    var onPlay: (() -> Unit)? = null
    var onDetails: (() -> Unit)? = null
    var onButtonFocused: (() -> Unit)? = null

    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val eyebrow = TextView(context).apply {
        typeRole(Type.Role.EYEBROW)
        setTextColor(colors.accent)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val title = TextView(context).apply {
        typeRole(Type.Role.HERO, 27f)
        setTextColor(colors.primaryText)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = TextView(context).apply {
        textSize = 12f
        setTextColor(soft())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val progress = ArtworkProgressView(context, colors.accent)
    private val progressLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(soft())
        maxLines = 1
    }
    private val progressRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
    }
    private val overview = TextView(context).apply {
        textSize = 12f
        setTextColor(soft())
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, 1.15f)
    }
    val play: TextView = PillButton.create(context, colors, "Play", AppIcon.PLAY, primary = true, heightDp = 36f)
    val details: TextView = PillButton.create(context, colors, "Details", AppIcon.INFO, heightDp = 36f)
    private var shownArt = ""
    var content: HeroContent? = null
        private set

    init {
        // A centre-cropped picture draws past its own edges; the hero must clip
        // it, or the art runs on under the rows below the fade.
        clipChildren = true
        addView(art, LayoutParams(MATCH, MATCH))
        // A solid left edge for the words, the art showing through on the right,
        // and the page colour again at the bottom where the rows begin.
        addView(View(context).apply {
            background = ScrimDrawable(colors, ScrimDrawable.Edge.LEFT, listOf(0f to 1f, .36f to .9f, .74f to .15f, 1f to 0f))
        }, LayoutParams(MATCH, MATCH))
        addView(View(context).apply {
            background = ScrimDrawable(colors, ScrimDrawable.Edge.BOTTOM, listOf(0f to 1f, .14f to .97f, .55f to 0f))
        }, LayoutParams(MATCH, MATCH))

        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        words.addView(eyebrow)
        words.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        words.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        val track = FrameLayout(context).apply {
            background = ThemeGradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(ColorUtils.setAlphaComponent(this@HomeHeroView.colors.primaryText, 0x33))
            }
            clipToOutline = true
            addView(progress, LayoutParams(MATCH, MATCH))
        }
        progressRow.addView(track, LinearLayout.LayoutParams(dp(54), dp(4)).apply { marginEnd = dp(8) })
        progressRow.addView(progressLabel)
        words.addView(progressRow, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(5) })
        words.addView(overview, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        val ring = dp(PillButton.RING_DP.toInt())
        buttons.addView(play, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = -ring })
        buttons.addView(details, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
        words.addView(buttons, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        addView(words, LayoutParams(dp(WORDS_DP), WRAP).apply { leftMargin = dp(24); topMargin = dp(TOP_DP) })

        // A Hebrew title is right-to-left text, but it still starts at the
        // hero's left edge; its own direction would push it to mid-screen.
        listOf(eyebrow, title, meta, overview, progressLabel).forEach { it.textAlignment = TEXT_ALIGNMENT_VIEW_START }
        // These lines are English with a Hebrew part: read left to right, "11 min"
        // stays "11 min" instead of becoming "min 11" in a right-to-left line.
        listOf(eyebrow, meta, progressLabel).forEach { it.textDirection = TEXT_DIRECTION_LTR }
        listOf(play, details).forEach { button ->
            FocusDecorator.attach(button, ringVisible, scale = false)
            FocusDecorator.listen(button, ringVisible) { _, focused -> if (focused) onButtonFocused?.invoke() }
        }
        play.activateOnTap { onPlay?.invoke() }
        details.activateOnTap { onDetails?.invoke() }
    }

    private fun soft() = ColorUtils.blendARGB(colors.mutedText, colors.primaryText, 0.45f)

    fun bind(next: HeroContent?) {
        content = next
        visibility = if (next == null) INVISIBLE else VISIBLE
        if (next == null) return
        eyebrow.text = com.pocketds.hub.ui.Bidi.isolateParts(next.eyebrow, " · ")
        title.text = next.title
        meta.text = com.pocketds.hub.ui.Bidi.join(next.meta, "  ·  ")
        progressRow.visibility = if (next.progress > 0) VISIBLE else GONE
        progress.fraction = next.progress
        progressLabel.text = next.progressLabel
        overview.text = next.overview
        overview.visibility = if (next.overview.isBlank()) GONE else VISIBLE
        play.visibility = if (next.canPlay) VISIBLE else GONE
        // Whichever button comes first lines up with the words above it.
        (details.layoutParams as LinearLayout.LayoutParams).marginStart =
            if (next.canPlay) dp(4) else -dp(PillButton.RING_DP.toInt())
        play.text = next.playLabel
        play.contentDescription = "${next.playLabel} ${next.title}"
        details.contentDescription = "Details for ${next.title}"
        val wanted = HubEndpoints.sized(next.backdrop, ART_WIDTH_PX)
        if (wanted != shownArt) {
            shownArt = wanted
            if (wanted.isBlank()) { art.dispose(); art.setImageDrawable(null) }
            else Artwork.bindHub(art, api, wanted, opaque = true) {
                crossfade(CROSSFADE_MS)
                // Keep the previous picture until the next one is ready, so
                // running along a row never flashes the page colour.
                art.drawable?.let { placeholder(it) }
            }
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val WORDS_DP = 430
        const val TOP_DP = 54
        const val ART_WIDTH_PX = 1920
        const val CROSSFADE_MS = 220
        private const val MATCH = LayoutParams.MATCH_PARENT
        private const val WRAP = LayoutParams.WRAP_CONTENT
    }
}

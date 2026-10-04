package com.pocketds.hub.screens.home

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
import com.pocketds.hub.ui.Bidi
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.PillButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.ScrimDrawable
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.FadedImageView
import com.pocketds.hub.ui.typeRole

/**
 * The top of Home: the focused card's backdrop, title, a line of facts, how
 * far in you are, and Play / Details.
 *
 * The rows sit over its lower edge, the way a streaming app's home does; this
 * view only draws the art and the words and owns the two buttons.
 *
 * Every line keeps its place whatever the card: the progress line is held
 * open (empty) for a title you have not started, and there is no overview.
 * A two-line overview and a progress line that came and went moved the
 * buttons up and down as focus ran along a row, and pushed Play under the
 * first row.
 *
 * In Glass ([glass], GLASS_PLAN.md) the art runs full-bleed under the bar and
 * fades into the page through a mask ([FadedImageView]) rather than into a
 * colour, so the ambient layer shows through where the rows begin. A shade
 * from the left keeps the words readable; the eyebrow's episode code is in the
 * accent; Play is the white pill and Details a glass one, as the prototype has
 * them at 204dp tall.
 */
class HomeHeroView(
    context: Context,
    private val colors: PocketColors,
    private val api: HubApi,
    ringVisible: () -> Boolean,
    private val glass: Boolean = false
) : FrameLayout(context) {
    var onPlay: (() -> Unit)? = null
    var onDetails: (() -> Unit)? = null
    var onButtonFocused: (() -> Unit)? = null

    private val art: ImageView = (if (glass) FadedImageView(context).apply {
        stops = FadedImageView.HERO
        shade = FadedImageView.HERO_SHADE
    } else ImageView(context)).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val eyebrow = TextView(context).apply {
        typeRole(Type.Role.EYEBROW, if (glass) 10.5f else Type.Role.EYEBROW.sizeSp)
        setTextColor(if (glass) GLASS_EYEBROW else colors.accent)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val title = TextView(context).apply {
        typeRole(Type.Role.HERO, if (glass) 30f else 27f)
        // The prototype's hero title is Bricolage at its heaviest.
        if (glass) typeface = Type.display(context, 800)
        setTextColor(colors.primaryText)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meta = TextView(context).apply {
        textSize = 12f
        setTextColor(if (glass) GLASS_WORDS else soft())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val progress = ArtworkProgressView(context, colors.accent)
    private val progressLabel = TextView(context).apply {
        textSize = 12f
        setTextColor(if (glass) GLASS_WORDS else soft())
        maxLines = 1
    }
    private val progressRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = INVISIBLE
    }
    val play: TextView = PillButton.create(context, colors, "Play", AppIcon.PLAY, primary = true,
        heightDp = if (glass) GLASS_BUTTON_DP else 36f, glass = glass)
    val details: TextView = PillButton.create(context, colors, "Details", AppIcon.INFO,
        heightDp = if (glass) GLASS_BUTTON_DP else 36f, glass = glass)
    private var shownArt = ""
    var content: HeroContent? = null
        private set

    init {
        // A centre-cropped picture draws past its own edges; the hero must clip
        // it, or the art runs on under the rows below the fade.
        clipChildren = true
        addView(art, LayoutParams(MATCH, MATCH))
        // Glass shades the words inside the art's own layer (FadedImageView),
        // so the shade thins out with the picture instead of ending in a line.
        if (!glass) {
            // A solid left edge for the words, the art showing through on the right,
            // and the page colour again at the bottom where the rows begin.
            addView(View(context).apply {
                background = ScrimDrawable(colors, ScrimDrawable.Edge.LEFT, listOf(0f to 1f, .36f to .9f, .74f to .15f, 1f to 0f))
            }, LayoutParams(MATCH, MATCH))
            addView(View(context).apply {
                background = ScrimDrawable(colors, ScrimDrawable.Edge.BOTTOM, listOf(0f to 1f, .14f to .97f, .55f to 0f))
            }, LayoutParams(MATCH, MATCH))
        }

        // The prototype sets the hero's lines 6dp apart.
        val gap = if (glass) 6 else 0
        val words = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        words.addView(eyebrow)
        words.addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (glass) gap else 4) })
        words.addView(meta, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (glass) gap else 5) })
        val track = FrameLayout(context).apply {
            background = ThemeGradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(if (glass) GLASS_TRACK else ColorUtils.setAlphaComponent(this@HomeHeroView.colors.primaryText, 0x33))
            }
            clipToOutline = true
            addView(progress, LayoutParams(MATCH, MATCH))
        }
        progressRow.addView(track, LinearLayout.LayoutParams(dp(if (glass) GLASS_PROGRESS_DP else PROGRESS_DP), dp(4)).apply {
            marginEnd = dp(if (glass) 12 else 10)
        })
        progressRow.addView(progressLabel)
        // Held open whether or not there is progress to show, so nothing under it moves.
        words.addView(progressRow, LinearLayout.LayoutParams(WRAP, if (glass) dp(18) else WRAP).apply { topMargin = dp(if (glass) gap else 8) })
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        buttons.addView(play, LinearLayout.LayoutParams(WRAP, WRAP))
        buttons.addView(details, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(detailsGap()) })
        val left = dp(if (glass) GLASS_LEFT_DP else 24)
        addView(words, LayoutParams(dp(if (glass) GLASS_WORDS_DP else WORDS_DP), WRAP).apply {
            leftMargin = left; topMargin = dp(if (glass) GLASS_TOP_DP else TOP_DP)
        })
        // The buttons have a place of their own rather than following the words:
        // a Hebrew title's font stands taller and would nudge them down. They
        // start a ring's width early so the pill itself lines up with the words,
        // and the ring is not clipped at the hero's edge.
        val ring = dp(PillButton.RING_DP.toInt())
        addView(buttons, LayoutParams(WRAP, WRAP).apply {
            leftMargin = left - ring; topMargin = dp(if (glass) GLASS_BUTTONS_TOP_DP else BUTTONS_TOP_DP) - ring
        })

        // A Hebrew title is right-to-left text, but it still starts at the
        // hero's left edge; its own direction would push it to mid-screen.
        listOf(eyebrow, title, meta, progressLabel).forEach { it.textAlignment = TEXT_ALIGNMENT_VIEW_START }
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

    /** The visual gap between the pills, less the ring room each keeps round itself. */
    private fun detailsGap() = if (glass) GLASS_BUTTON_GAP_DP - 2 * PillButton.RING_DP.toInt() else 4

    fun bind(next: HeroContent?) {
        content = next
        visibility = if (next == null) INVISIBLE else VISIBLE
        if (next == null) return
        eyebrow.text = if (glass) glassEyebrow(next) else Bidi.isolateParts(next.eyebrow, " · ")
        title.text = next.title
        meta.text = Bidi.join(next.meta, "  ·  ")
        progressRow.visibility = if (next.progress > 0) VISIBLE else INVISIBLE
        progress.fraction = next.progress
        progressLabel.text = next.progressLabel
        play.visibility = if (next.canPlay) VISIBLE else GONE
        // Whichever button comes first lines up with the words above it. The
        // params are set again: a start margin changed in place is never
        // resolved into the left margin layout reads.
        details.layoutParams = (details.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (next.canPlay) dp(detailsGap()) else 0
        }
        val label = if (glass) next.playAction else next.playLabel
        play.text = label
        play.contentDescription = "$label ${next.title}"
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

    /** "CONTINUE WATCHING · S3E4", the lead in soft white and the code in the accent. */
    private fun glassEyebrow(next: HeroContent): CharSequence {
        val mark = next.eyebrowMark
        val lead = if (mark.isEmpty()) next.eyebrow else next.eyebrow.removeSuffix(mark).removeSuffix(" · ")
        val line = SpannableStringBuilder(Bidi.isolateParts(lead, " · "))
        if (mark.isNotEmpty()) {
            if (lead.isNotEmpty()) line.append(" · ")
            val start = line.length
            line.append(Bidi.isolateParts(mark, " · "))
            line.setSpan(ForegroundColorSpan(colors.accent), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return line
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val WORDS_DP = 430
        /** The progress bar's length: long enough to read at a glance how far in you are. */
        const val PROGRESS_DP = 180
        const val TOP_DP = 54
        /** Below the eyebrow, title, facts and progress lines, whatever their script. */
        const val BUTTONS_TOP_DP = 160
        const val ART_WIDTH_PX = 1920
        const val CROSSFADE_MS = 220
        /**
         * Glass, from the prototype's 204dp Pocket hero: the words from 56dp,
         * 6dp apart, and the pills 31dp tall ending 7dp above the rows.
         */
        const val GLASS_HEIGHT_DP = 204
        const val GLASS_TOP_DP = 56
        const val GLASS_BUTTONS_TOP_DP = 166
        const val GLASS_LEFT_DP = 22
        const val GLASS_WORDS_DP = 520
        const val GLASS_PROGRESS_DP = 120
        const val GLASS_BUTTON_DP = 31f
        const val GLASS_BUTTON_GAP_DP = 10
        /** The prototype's eyebrow (white at 72%), facts (82%) and progress track (25%). */
        private const val GLASS_EYEBROW = com.pocketds.hub.ui.glass.GlassColors.EYEBROW
        private const val GLASS_WORDS = com.pocketds.hub.ui.glass.GlassColors.FACTS
        private const val GLASS_TRACK = com.pocketds.hub.ui.glass.GlassColors.TRACK
        private const val MATCH = LayoutParams.MATCH_PARENT
        private const val WRAP = LayoutParams.WRAP_CONTENT
    }
}

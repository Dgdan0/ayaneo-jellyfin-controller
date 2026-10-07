package com.pocketds.hub.nav

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.playback.PlayerControlIcon
import com.pocketds.hub.playback.PlayerIconButton
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * The mini player (#16, A1): the audiobook playing, in the top bar while you
 * browse the rest of the app. Ⓐ or a tap opens its screen; Ⓧ, or a tap on its
 * symbol, plays and pauses. Hidden while nothing is on the reading-audio
 * player and on the audiobook's own screen. A book with chapters names the
 * one playing under its title (#31), not the file.
 */
class MiniPlayerView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {
    var onOpen: () -> Unit = {}
    var onToggle: () -> Unit = {}
    var playing = false
        private set
    private var title = ""
    private val panel = GlassPanelDrawable(GlassColors.panel(ArtworkPalette.NEUTRAL), Styler.dp(context, 17f))
    private val symbol = PlayerIconButton(context, PlayerControlIcon.PLAY).apply {
        setIconColor(Color.WHITE, halo = false)
        isFocusable = false
        contentDescription = null
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // A tap on the symbol plays or pauses; anywhere else on the pill opens the book.
        setOnClickListener { onToggle() }
    }
    private val words = TextView(context).apply {
        textSize = 12f
        setTextColor(Color.WHITE)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = Styler.dpInt(context, 210f)
    }
    /** The chapter playing and the time left, under the book's title; shown only for a book with chapters. */
    private val under = TextView(context).apply {
        textSize = 10.5f
        setTextColor(GlassColors.FACTS)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = Styler.dpInt(context, 210f)
        visibility = GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Styler.dpInt(context, 6f), 0, Styler.dpInt(context, 14f), 0)
        visibility = GONE
        background = panel
        foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),
                ThemeGradientDrawable.rounded(Styler.dp(context, 17f), Color.TRANSPARENT, Styler.dpInt(context, 2f), colors.focusRing))
            addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
        }
        addView(symbol, LayoutParams(Styler.dpInt(context, 26f), Styler.dpInt(context, 26f)).apply { marginEnd = Styler.dpInt(context, 6f) })
        // Wrapping both ways: a vertical column's default is a full-width child, which a TextView does not lay out again
        // when its words change, so a longer line stayed as short as the one before it.
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(words, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(under, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        })
        // Its ring is the bar's: the foreground above, as on the bar's round buttons.
        Styler.makeFocusable(this)
        activateOnTap { onOpen() }
    }

    /**
     * Shows [book] with [detail] beside it ("4h 10m left"), playing or paused; null hides it.
     * With a [chapter] the book is alone on its line and the chapter, then [detail], sit under it.
     */
    fun show(book: String?, detail: String, isPlaying: Boolean, chapter: String? = null) {
        if (book == null) {
            visibility = GONE
            return
        }
        title = book
        playing = isPlaying
        if (chapter.isNullOrBlank()) {
            words.text = if (detail.isBlank()) book else "$book · $detail"
            under.visibility = GONE
        } else {
            words.text = book
            under.text = if (detail.isBlank()) chapter else "$chapter · $detail"
            under.visibility = VISIBLE
        }
        symbol.setIcon(if (isPlaying) PlayerControlIcon.PAUSE else PlayerControlIcon.PLAY)
        contentDescription = "Open $book, ${chapter?.takeIf(String::isNotBlank)?.let { "$it, " }.orEmpty()}${if (isPlaying) "playing" else "paused"}"
        visibility = VISIBLE
    }

    /** What Ⓧ does here, for the hint bar. */
    val toggleLabel: String get() = if (playing) "Pause" else "Play"

    fun setPalette(palette: ArtworkPalette) {
        panel.retint(GlassColors.panel(palette))
    }
}

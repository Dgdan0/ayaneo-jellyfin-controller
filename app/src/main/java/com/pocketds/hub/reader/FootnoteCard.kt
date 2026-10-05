package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.FocusScrollView
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Theme
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPanelDrawable
import com.pocketds.hub.ui.typeRole

/**
 * A footnote as a card over the page (#18, E5), instead of a jump away from
 * the sentence that cites it: the note's words in the glass of the reader's
 * sheets, with "Go to the note" and Close. Ⓑ or a tap beside the card closes
 * it and the page is where it was; going to the note leaves "Return to
 * previous place" in the menu. Up and down scroll a long note.
 */
class FootnoteCard(context: Context, colors: PocketColors, ringVisible: () -> Boolean) : FrameLayout(context) {
    var onClose: () -> Unit = {}
    var onFollow: () -> Unit = {}
    val isOpen: Boolean get() = visibility == VISIBLE

    private val words = TextView(context).apply {
        textSize = 15f
        setTextColor(Color.WHITE)
        setLineSpacing(0f, 1.25f)
    }
    private val scroll = FocusScrollView(context).apply { addView(words) }
    private val follow: TextView = OverlayButtons.pill(context, colors.focusRing, "Go to the note", "Go to the note in the book", AppIcon.NEXT) { onFollow() }
    private val close: TextView = OverlayButtons.pill(context, colors.focusRing, "Close", "Close the note", AppIcon.CLOSE) { onClose() }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        val pad = dp(18)
        setPadding(pad, dp(14), pad, dp(12))
        // Opaque: a note is read over lines of text, which must not show through it (#20).
        GlassPanelDrawable.attach(this, Styler.dp(context, 16f), GlassColors::card)
        elevation = Styler.dp(context, 12f)
    }

    init {
        visibility = GONE
        isClickable = true
        setOnClickListener { onClose() }
        panel.addView(TextView(context).apply {
            text = "NOTE"
            typeRole(Type.Role.EYEBROW, 10.5f)
            setTextColor(GlassColors.EYEBROW)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        listOf(follow, close).forEach { button ->
            FocusDecorator.attach(button, ringVisible, scale = false)
            actions.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply { marginStart = dp(6) })
        }
        panel.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
    }

    /** The note's [text]; [canFollow] when there is somewhere in the book to go to. Close starts under the cursor. */
    fun show(text: String, canFollow: Boolean) {
        words.text = text
        follow.visibility = if (canFollow) VISIBLE else GONE
        scroll.scrollTo(0, 0)
        // Already over the page and its controls; under Comfort's layer, which dims it too.
        visibility = VISIBLE
        post { if (isOpen) close.requestFocus() }
    }

    fun dismiss() {
        visibility = GONE
    }

    /** The words on the card, for a test. */
    val text: CharSequence get() = words.text

    fun onPad(action: PadAction): Boolean {
        if (!isOpen) return false
        when (action) {
            PadAction.Back -> onClose()
            PadAction.Activate -> (findFocus() ?: close).performClick()
            is PadAction.Step -> when (action.direction) {
                Direction.UP -> scroll.smoothScrollBy(0, -dp(SCROLL_DP))
                Direction.DOWN -> scroll.smoothScrollBy(0, dp(SCROLL_DP))
                Direction.LEFT -> if (follow.visibility == VISIBLE) follow.requestFocus()
                Direction.RIGHT -> close.requestFocus()
            }
            is PadAction.Pan -> scroll.scrollBy(0, (action.dy * scroll.height).toInt())
            else -> Unit
        }
        return true
    }

    /** At most this much of the screen: a long note scrolls inside. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val side = ((width - dp(MAX_WIDTH_DP)) / 2).coerceAtLeast(dp(16))
        (panel.layoutParams as LayoutParams).apply {
            leftMargin = side; rightMargin = side; bottomMargin = dp(16)
        }
        // The words take what is left when the eyebrow and the buttons have theirs.
        scroll.layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val cap = (height * MAX_HEIGHT_SHARE).toInt()
        if (panel.measuredHeight > cap) {
            scroll.layoutParams.height = (scroll.measuredHeight - (panel.measuredHeight - cap)).coerceAtLeast(dp(48))
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private fun dp(value: Int): Int = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MAX_WIDTH_DP = 620
        const val MAX_HEIGHT_SHARE = 0.62f
        const val SCROLL_DP = 72
    }
}

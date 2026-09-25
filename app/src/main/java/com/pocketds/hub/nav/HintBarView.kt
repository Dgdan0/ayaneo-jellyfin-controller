package com.pocketds.hub.nav

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/**
 * What A/B/X/Y do right now -- and, for anyone driving the trackpad, the buttons
 * that do it.
 *
 * This is the largest usability win in the app for the least code, because a
 * gamepad has no affordances: nothing on a controller tells you that Y means
 * "manual search" on this screen and "delete" on the next one. Console UIs all
 * carry a bar like this for exactly that reason.
 *
 * It doubles as the touch action bar. The chips are real clickable controls, so
 * a pointer user reaches every contextual action through the same widget that
 * labels it for a pad user -- one mechanism, one source of truth, and no
 * touch-only parallel UI that drifts out of sync.
 *
 * The chips are deliberately **not focusable**. Letting gamepad focus land on
 * the "Ⓐ Open" chip, so that pressing A activates a picture of the A button,
 * is a small maze; the physical button is already the gamepad path.
 */
class HintBarView(context: Context, private val colors: PocketColors) : HorizontalScrollView(context) {

    var onAction: ((PadAction) -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = Styler.dpInt(context, 8f)
        setPadding(inset, 0, inset, 0)
    }

    init {
        setBackgroundColor(colors.stripBackground)
        isFocusable = false
        isHorizontalScrollBarEnabled = false
        val height = Styler.dpInt(context, 48f)
        minimumHeight = height
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
    }

    fun setHints(hints: List<ButtonHint>) {
        // Keep existing chips in place. On this device, removing and re-adding
        // every child from a focus callback can leave the bar empty for the
        // rest of that layout pass. Updating the small stable row also avoids
        // allocating views on every D-pad move.
        while (row.childCount > hints.size) row.removeViewAt(row.childCount - 1)
        hints.forEachIndexed { index, hint ->
            val view = if (index < row.childCount) row.getChildAt(index) as TextView
            else chip().also(row::addView)
            bind(view, hint)
        }
        requestLayout()
        invalidate()
    }

    private fun chip(): TextView = TextView(context).apply {
        // Glyphs rather than drawables, following the sibling project: an icon
        // set for every button on every screen is a lot of assets to keep
        // consistent, and these read fine at this size.
        textSize = 13f
        isAllCaps = false
        setSingleLine(true)
        minimumHeight = Styler.dpInt(context, 48f)
        background = Styler.chipBackground(context, colors)
        val padH = Styler.dpInt(context, 12f)
        val padV = Styler.dpInt(context, 7f)
        setPadding(padH, padV, padH, padV)
        isFocusable = false
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { rightMargin = Styler.dpInt(context, 8f) }
    }

    private fun bind(view: TextView, hint: ButtonHint) = with(view) {
        text = "${hint.glyph}  ${hint.label}"
        setTextColor(if (hint.enabled) colors.primaryText else colors.mutedText)
        isClickable = hint.enabled
        alpha = if (hint.enabled) 1f else 0.5f
        setOnClickListener(if (hint.enabled) View.OnClickListener { onAction?.invoke(hint.action) } else null)
    }
}

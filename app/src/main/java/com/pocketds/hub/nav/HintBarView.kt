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
import com.pocketds.hub.ui.KeyGlyphDrawable
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type

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
        val inset = Styler.dpInt(context, 14f)
        setPadding(inset, 0, inset, 0)
    }
    private val divider = android.graphics.Paint()

    init {
        setBackgroundColor(colors.background)
        isFocusable = false
        isHorizontalScrollBarEnabled = false
        setWillNotDraw(false)
        val height = Styler.dpInt(context, HEIGHT_DP)
        minimumHeight = height
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        divider.color = (colors.primaryText and 0x00FFFFFF) or 0x10000000
        canvas.drawRect(scrollX.toFloat(), 0f, scrollX + width.toFloat(), Styler.dp(context, 1f), divider)
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
        textSize = 11.5f
        isAllCaps = false
        setSingleLine(true)
        // The bar is slim, but every chip is still a button: it takes the bar's
        // full height for touch even though it draws only the glyph and label.
        background = Styler.chipBackground(context, colors).let { chips ->
            android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), chips)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
        val padH = Styler.dpInt(context, 6f)
        setPadding(padH, 0, padH, 0)
        compoundDrawablePadding = Styler.dpInt(context, 6f)
        isFocusable = false
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { rightMargin = Styler.dpInt(context, 10f) }
    }

    private fun bind(view: TextView, hint: ButtonHint) = with(view) {
        val glyph = KeyGlyphDrawable(colors, hint.glyph, Styler.dpInt(context, 17f), Type.text(context, 800))
        glyph.setBounds(0, 0, glyph.intrinsicWidth, glyph.intrinsicHeight)
        setCompoundDrawables(glyph, null, null, null)
        text = hint.label
        contentDescription = "${glyph.label}: ${hint.label}"
        setTextColor(if (hint.enabled) androidx.core.graphics.ColorUtils.blendARGB(colors.mutedText, colors.primaryText, 0.55f) else colors.mutedText)
        isClickable = hint.enabled
        alpha = if (hint.enabled) 1f else 0.5f
        setOnClickListener(if (hint.enabled) View.OnClickListener { onAction?.invoke(hint.action) } else null)
    }

    companion object {
        const val HEIGHT_DP = 36f
    }
}

package com.pocketds.hub.nav

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.KeyGlyphDrawable
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.Type
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.textWeight

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
 *
 * The bar is a full-width tint of the page's artwork with a hairline along its
 * top, the caps are white chips with dark letters, and Select's Refresh sits
 * apart at the right, quieter, as in the prototype.
 */
class HintBarView(
    context: Context,
    private val colors: PocketColors
) : HorizontalScrollView(context) {

    var onAction: ((PadAction) -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = Styler.dpInt(context, 14f)
        setPadding(inset, 0, inset, 0)
    }
    private val divider = android.graphics.Paint()
    /** What pushes Refresh to the right edge, and Refresh itself. */
    private val gap = Space(context)
    private val refresh: TextView = chip().apply { alpha = REFRESH_ALPHA }
    private val tint = android.graphics.drawable.ColorDrawable(GlassColors.bar(ArtworkPalette.NEUTRAL))

    init {
        background = tint
        isFocusable = false
        isHorizontalScrollBarEnabled = false
        setWillNotDraw(false)
        val height = Styler.dpInt(context, HEIGHT_DP)
        minimumHeight = height
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
        // The row is stretched across the bar so Refresh can sit at the right;
        // it still scrolls when the hints are wider than the screen.
        isFillViewport = true
        addView(row, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
        row.addView(gap, LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(refresh)
        (refresh.layoutParams as? LinearLayout.LayoutParams)?.rightMargin = 0
        GlassPage.follow(this) { setPalette(it) }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        divider.color = GlassColors.BAR_EDGE
        canvas.drawRect(scrollX.toFloat(), 0f, scrollX + width.toFloat(), Styler.dp(context, 1f), divider)
    }

    /** The bar takes the tint of the page's new artwork. */
    fun setPalette(palette: ArtworkPalette) {
        val fill = GlassColors.bar(palette)
        if (tint.color == fill) return
        tint.color = fill
        invalidate()
    }

    fun setHints(hints: List<ButtonHint>) {
        // Keep existing chips in place. On this device, removing and re-adding
        // every child from a focus callback can leave the bar empty for the
        // rest of that layout pass. Updating the small stable row also avoids
        // allocating views on every D-pad move.
        val shown = hints.filterNot { it.action == PadAction.Refresh }
        val chips = row.childCount - 2
        for (index in chips - 1 downTo shown.size) row.removeViewAt(index)
        shown.forEachIndexed { index, hint ->
            val view = if (index < chips) row.getChildAt(index) as TextView
            else chip().also { row.addView(it, index) }
            bind(view, hint)
        }
        val again = hints.firstOrNull { it.action == PadAction.Refresh }
        refresh.visibility = if (again == null) View.GONE else View.VISIBLE
        again?.let { bind(refresh, it) }
        requestLayout()
        invalidate()
    }

    private fun chip(): TextView = TextView(context).apply {
        textSize = 11.5f
        isAllCaps = false
        setSingleLine(true)
        textWeight(600)
        // The bar is slim, but every chip is still a button: it takes the bar's
        // full height for touch even though it draws only the glyph and label.
        background = Styler.chipBackground(context, colors).let { chips ->
            android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), chips)
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
        }
        // The hints the prototype's 16dp apart, each cap 5dp from its word.
        val padH = Styler.dpInt(context, 4f)
        setPadding(padH, 0, padH, 0)
        compoundDrawablePadding = Styler.dpInt(context, 5f)
        isFocusable = false
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { rightMargin = Styler.dpInt(context, 8f) }
    }

    private fun bind(view: TextView, hint: ButtonHint) = with(view) {
        val glyph = KeyGlyphDrawable(hint.glyph, Styler.dpInt(context, 17f), Type.text(context, 800))
        glyph.setBounds(0, 0, glyph.intrinsicWidth, glyph.intrinsicHeight)
        setCompoundDrawables(glyph, null, null, null)
        text = hint.label
        contentDescription = "${glyph.label}: ${hint.label}"
        setTextColor(GlassColors.BAR_TEXT)
        isClickable = hint.enabled
        alpha = (if (hint.enabled) 1f else 0.5f) * if (view === refresh) REFRESH_ALPHA else 1f
        setOnClickListener(if (hint.enabled) View.OnClickListener { onAction?.invoke(hint.action) } else null)
    }

    companion object {
        const val HEIGHT_DP = 36f
        /** Refresh is always there and rarely wanted: the prototype shows it at 60%. */
        private const val REFRESH_ALPHA = 0.6f
    }
}

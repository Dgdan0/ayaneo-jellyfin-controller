package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ButtonHint
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.Type

/**
 * The card at the end of an issue (#16, C6): "End of Fantastic Four #51" over
 * "Next: #52", and the keys: Ⓐ continues to it, Ⓑ stays on the last page.
 * Forward on the last page used to jump to the next issue with no word. The
 * reader decides what the keys do; the card says it, and its key chips are
 * buttons for a pointer, handed to [onAction] as the keys would be.
 */
class EndOfIssueCard(context: Context, colors: PocketColors, onAction: (PadAction) -> Unit) : LinearLayout(context) {
    private val heading = TextView(context).apply {
        Type.apply(this, Type.Role.HEADING, 17f)
        setTextColor(Color.WHITE)
        maxLines = 2
        gravity = Gravity.CENTER
    }
    private val next = TextView(context).apply {
        textSize = 13f
        setTextColor(SOFT_TEXT)
        maxLines = 2
        gravity = Gravity.CENTER
        setPadding(0, Styler.dpInt(context, 4f), 0, 0)
    }
    private val keys = ReaderKeys.row(context, colors, onAction)

    val isOpen: Boolean get() = visibility == VISIBLE

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = Styler.dpInt(context, 18f)
        setPadding(pad, Styler.dpInt(context, 16f), pad, Styler.dpInt(context, 6f))
        OverlayButtons.panel(this, 18f)
        addView(heading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(next, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(keys, LayoutParams(LayoutParams.WRAP_CONTENT, Styler.dpInt(context, 40f)).apply { topMargin = Styler.dpInt(context, 6f) })
        // The hint bar's own tint would draw a second panel inside this one.
        keys.background = null
        visibility = GONE
        isClickable = true
    }

    /** [canContinue] false: nothing follows, so A has nothing to do and only B (stay) is offered. */
    fun show(heading: String, next: String, canContinue: Boolean) {
        this.heading.text = heading
        this.next.text = next
        keys.setHints(listOfNotNull(
            ButtonHint.activate("Continue").takeIf { canContinue },
            ButtonHint.back("Stay"),
            ButtonHint.refresh("Leave")
        ))
        contentDescription = "$heading. $next"
        visibility = VISIBLE
    }

    /** The next issue's name arrived after the card opened. */
    fun updateNext(next: String) {
        this.next.text = next
        contentDescription = "${heading.text}. $next"
    }

    fun hide() {
        visibility = GONE
    }

    private companion object {
        val SOFT_TEXT = Color.rgb(213, 219, 227)
    }
}

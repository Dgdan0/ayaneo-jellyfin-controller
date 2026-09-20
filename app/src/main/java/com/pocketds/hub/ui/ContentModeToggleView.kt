package com.pocketds.hub.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.state.ContentMode

/** Compact, reusable Media | Books switch for content screens. */
class ContentModeToggleView(
    context: Context,
    private val colors: PocketColors
) : LinearLayout(context) {
    private val buttons = linkedMapOf<ContentMode, TextView>()
    var onModeSelected: ((ContentMode) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        ContentMode.entries.forEach { mode ->
            val button = TextView(context).apply {
                text = mode.label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
                val horizontal = Styler.dpInt(context, 18f)
                val vertical = Styler.dpInt(context, 6f)
                setPadding(horizontal, vertical, horizontal, vertical)
                contentDescription = "Show ${mode.label.lowercase()}"
                Styler.makeFocusable(this)
                isClickable = true
                setOnClickListener { onModeSelected?.invoke(mode) }
            }
            buttons[mode] = button
            addView(button, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, Styler.dpInt(context, 5f), 0)
            })
        }
    }

    fun select(mode: ContentMode) {
        buttons.forEach { (candidate, button) ->
            button.background = Styler.chipBackground(context, colors, candidate == mode)
            button.setTextColor(if (candidate == mode) colors.accentText else colors.primaryText)
            button.isSelected = candidate == mode
        }
    }

    fun focus(mode: ContentMode): Boolean = buttons[mode]?.requestFocus() == true
}

package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
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
    var onFocused: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val inset = Styler.dpInt(context, 2f)
        setPadding(inset, inset, inset, inset)
        background = InsetDrawable(ThemeGradientDrawable().apply {
            cornerRadius = Styler.dp(context, 13f)
            setColor(this@ContentModeToggleView.colors.stripBackground)
        }, 0, Styler.dpInt(context, 5f), 0, Styler.dpInt(context, 5f))
        ContentMode.entries.forEach { mode ->
            val button = CenteredIconTextView(context).apply {
                text = mode.label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(colors.primaryText)
                setPadding(Styler.dpInt(context, 10f), 0, Styler.dpInt(context, 10f), 0)
                contentDescription = "Show ${mode.label.lowercase()}"
                Styler.makeFocusable(this)
                isClickable = true
                minimumHeight = Styler.dpInt(context, 48f)
                minimumWidth = Styler.dpInt(context, 70f)
                activateOnTap { onModeSelected?.invoke(mode) }
                setOnFocusChangeListener { _, focused -> if (focused) onFocused?.invoke() }
            }
            buttons[mode] = button
            addView(button, LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, Styler.dpInt(context, 2f), 0)
            })
        }
    }

    fun select(mode: ContentMode) {
        buttons.forEach { (candidate, button) ->
            val selected = candidate == mode
            fun face(fill: Int, border: Int = Color.TRANSPARENT) = ThemeGradientDrawable().apply {
                cornerRadius = Styler.dp(context, 11f)
                setColor(fill)
                if (border != Color.TRANSPARENT) setStroke(Styler.dpInt(context, 2f), border)
            }
            button.background = StateListDrawable().apply {
                val vertical = Styler.dpInt(context, 6f)
                addState(intArrayOf(android.R.attr.state_focused), InsetDrawable(face(
                    if (selected) colors.accent else colors.focusFill,
                    if (selected) colors.primaryText else colors.focusRing), 0, vertical, 0, vertical))
                addState(intArrayOf(), InsetDrawable(face(if (selected) colors.accent else Color.TRANSPARENT),
                    0, vertical, 0, vertical))
            }
            button.setTextColor(if (selected) colors.accentText else colors.mutedText)
            (button as CenteredIconTextView).setCenteredIcon(AppIconDrawable(if(candidate == ContentMode.BOOKS) AppIcon.BOOK else AppIcon.MEDIA, if(selected) colors.accentText else colors.mutedText), Styler.dpInt(context,18f), Styler.dpInt(context,6f))
            button.isSelected = candidate == mode
        }
    }

    fun focus(mode: ContentMode): Boolean = buttons[mode]?.requestFocus() == true
    fun button(mode: ContentMode): TextView = buttons.getValue(mode)
}

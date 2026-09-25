package com.pocketds.hub.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Text-only browse category with a selected underline and a full gamepad/touch target. */
class ReadingCategoryTabView(context: Context, private val colors: PocketColors, title: String) : LinearLayout(context) {
    private val label = TextView(context).apply {
        text = title
        textSize = 12f
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private val underline = View(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        minimumHeight = Styler.dpInt(context, 48f)
        setPadding(Styler.dpInt(context, 11f), 0, Styler.dpInt(context, 11f), 0)
        Styler.makeFocusable(this)
        isClickable = true
        contentDescription = "Show $title"
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, Styler.dpInt(context, 45f)))
        addView(underline, LayoutParams(LayoutParams.MATCH_PARENT, Styler.dpInt(context, 3f)))
        select(false)
    }

    fun select(active: Boolean) {
        isSelected = active
        background = Styler.selectionBackground(context, colors, active)
        label.setTextColor(if (active) colors.primaryText else colors.mutedText)
        underline.setBackgroundColor(colors.accent)
        underline.visibility = if (active) VISIBLE else INVISIBLE
    }
}

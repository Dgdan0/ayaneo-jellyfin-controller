package com.pocketds.hub.nav

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler

/**
 * The top-level sections, switched with L1/R1 or by tapping.
 *
 * Unlike the hint bar's chips these *are* focusable, so the sections stay
 * reachable by pushing the stick up out of the content. That is the fallback for
 * the day a firmware update decides the shoulder buttons belong to the system:
 * no action in this app is ever reachable only by a shoulder button.
 *
 * Built once and updated, never re-inflated per screen -- on a console-style UI
 * the chrome is the constant thing.
 */
class TabBarView(context: Context, private val colors: PocketColors) : LinearLayout(context) {

    var onSelect: ((Int) -> Unit)? = null

    private var current = 0
    private val tabs = mutableListOf<TextView>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(colors.stripBackground)
        val pad = Styler.dpInt(context, 8f)
        setPadding(pad, pad / 2, pad, pad / 2)
    }

    fun setSections(titles: List<String>) {
        removeAllViews()
        tabs.clear()
        titles.forEachIndexed { index, title ->
            val tab = tab(title, index)
            tabs += tab
            addView(tab)
        }
        setCurrent(current.coerceIn(0, (titles.size - 1).coerceAtLeast(0)))
    }

    fun setCurrent(index: Int) {
        current = index
        tabs.forEachIndexed { i, tab ->
            val selected = i == index
            tab.background = Styler.chipBackground(context, colors, selected)
            tab.setTextColor(if (selected) colors.accentText else colors.mutedText)
        }
    }

    private fun tab(title: String, index: Int): TextView = TextView(context).apply {
        text = title
        textSize = 15f
        isAllCaps = false
        val padH = Styler.dpInt(context, 16f)
        val padV = Styler.dpInt(context, 8f)
        setPadding(padH, padV, padH, padV)

        // Deliberately NOT focusable, for the same reason the hint-bar chips are
        // not: sections are switched with L1/R1 or by tapping a tab, so a tab
        // never needs to hold gamepad focus.
        //
        // It also closes a whole class of bug. Android hands focus to the first
        // focusable view in the window whenever the focused one is orphaned --
        // and a RecyclerView detaching the card you are on does exactly that.
        // While the tabs were focusable, running along a poster row silently
        // dumped the selection on "Discover", and from there every directional
        // press was correctly refused, which read as the pad being dead.
        isClickable = true
        isFocusable = false
        isClickable = true
        setOnClickListener { onSelect?.invoke(index) }

        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = Styler.dpInt(context, 6f) }
    }
}

package com.pocketds.hub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.StateListDrawable
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.R
import com.pocketds.hub.state.ContentMode

/** Compact utility destinations outside the five content sections in the rail. */
class UtilityHeaderView(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    var onSelect: ((Int) -> Unit)? = null
    var onModeSelected: ((ContentMode) -> Unit)? = null
    var onFocused: (() -> Unit)? = null
    private val buttons = linkedMapOf<Int, FrameLayout>()
    private val badge: TextView
    private val titleView: TextView
    private val modeToggle: ContentModeToggleView
    private var activeMode: ContentMode? = null
    private var unread = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), 0, dp(16), 0)
        setBackgroundColor(colors.background)
        titleView = TextView(context).apply {
            textSize = 18f
            gravity = Gravity.CENTER_VERTICAL
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(colors.primaryText)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Home"
        }
        addView(titleView, LayoutParams(0, dp(48), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        modeToggle = ContentModeToggleView(context, colors).apply {
            visibility = View.GONE
            onModeSelected = { mode -> this@UtilityHeaderView.onModeSelected?.invoke(mode) }
            onFocused = { this@UtilityHeaderView.onFocused?.invoke() }
        }
        addView(modeToggle, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            rightMargin = dp(12)
        })
        button(5, "Notifications", R.drawable.ic_nav_notifications)
        button(6, "Services", R.drawable.ic_nav_manage)
        button(7, "Settings", R.drawable.ic_nav_settings)
        badge = TextView(context).apply {
            textSize = 9f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(SemanticColor.foreground(colors.badgeFailed))
            background = ThemeGradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(this@UtilityHeaderView.colors.badgeFailed)
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        buttons.getValue(5).addView(badge,
            FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(2); rightMargin = dp(2)
            })
    }

    private fun button(index: Int, label: String, icon: Int) {
        val row = FrameLayout(context).apply {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = iconBackground(false)
            setOnClickListener { onSelect?.invoke(index) }
            setOnFocusChangeListener { _, focused -> if (focused) onFocused?.invoke() }
            addView(ImageView(context).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                imageTintList = ColorStateList.valueOf(colors.mutedText)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        }
        buttons[index] = row
        addView(row, LayoutParams(dp(48), dp(48)))
    }

    private fun iconBackground(selected: Boolean): StateListDrawable = StateListDrawable().apply {
        fun face(focused: Boolean) = ThemeGradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (focused) this@UtilityHeaderView.colors.focusFill else Color.TRANSPARENT)
            if (focused) setStroke(dp(2), if (selected) this@UtilityHeaderView.colors.primaryText else this@UtilityHeaderView.colors.focusRing)
        }
        addState(intArrayOf(android.R.attr.state_focused), face(true))
        addState(intArrayOf(), face(false))
    }

    fun buttonForSection(index: Int): View = buttons.getValue(index)
    fun modeButton(mode: ContentMode): View = modeToggle.button(mode)
    fun setMode(mode: ContentMode?) {
        val wasFocused = modeToggle.hasFocus()
        activeMode = mode
        modeToggle.visibility = if (mode == null) View.GONE else View.VISIBLE
        if (mode != null) modeToggle.select(mode)
        else if (wasFocused) buttons.getValue(5).requestFocus()
    }
    fun focusMode(mode: ContentMode): Boolean = activeMode != null && modeToggle.focus(mode)
    fun setTitle(title: String) { titleView.text = title }
    fun setCurrent(index: Int) {
        buttons.forEach { (section, view) ->
            view.isSelected = section == index
            view.background = iconBackground(section == index)
            (view.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(
                if (section == index) colors.accent else colors.mutedText)
        }
    }
    fun setBadge(count: Int) {
        unread = count.coerceAtLeast(0)
        badge.text = if (unread > 99) "99+" else unread.toString()
        badge.visibility = if (unread > 0) View.VISIBLE else View.GONE
        buttons.getValue(5).contentDescription = if (unread > 0)
            "Notifications, $unread unread" else "Notifications"
    }
    fun focusFirst(): Boolean = (activeMode?.let(modeToggle::focus) == true) ||
        buttons.getValue(5).requestFocus()
    fun moveHorizontal(delta: Int): Boolean {
        val order = buildList<View> {
            if (activeMode != null) ContentMode.entries.forEach { add(modeToggle.button(it)) }
            buttons.values.forEach(::add)
        }
        val current = order.indexOfFirst(View::hasFocus)
        val next = (current + delta).coerceIn(order.indices)
        return next != current && order[next].requestFocus()
    }
    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())
}

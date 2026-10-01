package com.pocketds.hub.nav

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.R
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.textWeight

/**
 * The app's one row of chrome: the five sections as tabs, the Media / Books
 * switch, and Notifications, Services and Settings as icons.
 *
 * Tabs across the top rather than the old side rail: the rail cost 68dp of a
 * 853dp-wide screen on every page, which is a poster column, and L1 / R1 already
 * did the switching. The badges either side of the tabs say so.
 *
 * Over Home's hero the bar is see-through ([setOverArtwork]) so the artwork runs
 * to the top edge; everywhere else it sits on the page colour.
 *
 * Focus enters from the content with Up and leaves with Down or B, exactly as
 * the header it replaces did; [moveHorizontal] walks every control in order.
 */
class TopBarView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val tabTitles: List<String>
) : LinearLayout(context) {
    var onSelect: ((Int) -> Unit)? = null
    var onModeSelected: ((ContentMode) -> Unit)? = null
    var onFocused: (() -> Unit)? = null

    private val mark: FrameLayout
    private val markIcon: ImageView
    private val tabs: BlobSegmentedView
    private val modeToggle: BlobSegmentedView
    private val icons = linkedMapOf<Int, FrameLayout>()
    private val badge: View
    private var activeMode: ContentMode? = null
    private var current = 0
    private var overArtwork = false
    private val scrim = Paint()
    /** [colors] under a name a GradientDrawable's own `colors` property cannot shadow. */
    private val palette get() = colors

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), 0, dp(12), 0)
        setWillNotDraw(false)
        mark = FrameLayout(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        markIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        mark.addView(markIcon, FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
        addView(mark, LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        addView(shoulder("L1"))
        tabs = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.PILL).apply {
            padXDp = 11f
            setOptions(tabTitles.mapIndexed { index, title -> BlobSegmentedView.Option(index.toString(), title) }, "0")
            onPick = { id -> this@TopBarView.onSelect?.invoke(id.toInt()) }
            onOptionFocused = { this@TopBarView.onFocused?.invoke() }
        }
        addView(tabs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(6); marginEnd = dp(6)
        })
        addView(shoulder("R1"))
        addView(View(context), LayoutParams(0, 1, 1f))
        modeToggle = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.ACCENT).apply {
            padXDp = 11f
            heightDp = 32f
            visibility = GONE
            setOptions(ContentMode.entries.map {
                BlobSegmentedView.Option(it.stored, it.label, "Show ${it.label.lowercase()}")
            }, ContentMode.MEDIA.stored)
            onPick = { id -> this@TopBarView.onModeSelected?.invoke(ContentMode.fromStored(id)) }
            onOptionFocused = { this@TopBarView.onFocused?.invoke() }
        }
        addView(modeToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(8)
        })
        icon(NOTIFICATIONS, "Notifications", R.drawable.ic_nav_notifications)
        icon(SERVICES, "Services", R.drawable.ic_nav_manage)
        icon(SETTINGS, "Settings", R.drawable.ic_nav_settings)
        badge = View(context).apply {
            background = ThemeGradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.badgeFailed)
                setStroke(dp(2), palette.background)
            }
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        icons.getValue(NOTIFICATIONS).addView(badge, FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); rightMargin = dp(8)
        })
        paintMark()
        paintIcons()
    }

    private fun shoulder(label: String) = TextView(context).apply {
        text = label
        textSize = 9f
        textWeight(700)
        setTextColor(colors.mutedText)
        includeFontPadding = false
        setPadding(dp(4), dp(1), dp(4), dp(1))
        background = ThemeGradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), (palette.mutedText and 0x00FFFFFF) or 0x55000000)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun icon(index: Int, label: String, drawable: Int) {
        val button = FrameLayout(context).apply {
            contentDescription = label
            Styler.makeFocusable(this)
            activateOnTap { onSelect?.invoke(index) }
            setOnFocusChangeListener { _, focused -> if (focused) onFocused?.invoke() }
            addView(ImageView(context).apply {
                setImageResource(drawable)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        }
        icons[index] = button
        // 44dp of touch around a 32dp circle.
        addView(button, LayoutParams(dp(44), dp(44)))
    }

    private fun paintIcons() {
        icons.forEach { (section, view) ->
            val on = section == current
            view.isSelected = on
            view.background = StateListDrawable().apply {
                fun face(focused: Boolean) = android.graphics.drawable.InsetDrawable(ThemeGradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) palette.primaryText else (palette.stripBackground and 0x00FFFFFF) or 0xB8000000.toInt())
                    if (focused) setStroke(dp(2), if (on) palette.accent else palette.focusRing)
                }, dp(6))
                addState(intArrayOf(android.R.attr.state_focused), face(true))
                addState(intArrayOf(), face(false))
            }
            (view.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (on) colors.background else colors.primaryText)
        }
    }

    private fun paintMark() {
        mark.background = ThemeGradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.accent)
        }
        markIcon.setImageDrawable(AppIconDrawable(if (activeMode == ContentMode.BOOKS) AppIcon.BOOK else AppIcon.MEDIA, colors.accentText))
    }

    /** See-through over a hero image, with a scrim so the tabs stay readable on a bright poster. */
    fun setOverArtwork(over: Boolean) {
        if (overArtwork == over && background != null) return
        overArtwork = over
        if (over) setBackgroundColor(Color.TRANSPARENT) else setBackgroundColor(colors.background)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (overArtwork) {
            scrim.shader = android.graphics.LinearGradient(0f, 0f, 0f, height.toFloat(),
                (colors.background and 0x00FFFFFF) or 0x99000000.toInt(), colors.background and 0x00FFFFFF,
                android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        }
        super.onDraw(canvas)
    }

    fun buttonForSection(index: Int): View = icons[index] ?: tabs.optionView(index.toString())!!
    fun modeButton(mode: ContentMode): View = modeToggle.optionView(mode.stored)!!

    fun setMode(mode: ContentMode?) {
        val wasFocused = modeToggle.hasFocus()
        activeMode = mode
        modeToggle.visibility = if (mode == null) GONE else VISIBLE
        if (mode != null) modeToggle.select(mode.stored)
        else if (wasFocused) tabs.focus()
        paintMark()
    }

    fun focusMode(mode: ContentMode): Boolean = activeMode != null && modeToggle.focus(mode.stored)

    /** A utility page (Notifications, Services, Settings) lights its icon and leaves the tabs unselected. */
    fun setCurrent(index: Int) {
        current = index
        tabs.select(if (index < tabTitles.size) index.toString() else null)
        paintIcons()
        paintMark()
    }

    fun setBadge(count: Int) {
        val unread = count.coerceAtLeast(0)
        badge.visibility = if (unread > 0) VISIBLE else GONE
        icons.getValue(NOTIFICATIONS).contentDescription = if (unread > 0) "Notifications, $unread unread" else "Notifications"
    }

    private fun order(): List<View> = buildList {
        tabs.optionIds.forEach { add(tabs.optionView(it)!!) }
        if (activeMode != null) modeToggle.optionIds.forEach { add(modeToggle.optionView(it)!!) }
        icons.values.forEach(::add)
    }

    /** Up from the content lands on the current tab, or the lit icon on a utility page. */
    fun focusFirst(): Boolean = (if (current < tabTitles.size) tabs.focus(current.toString()) else icons[current]?.requestFocus() == true) ||
        order().firstOrNull()?.requestFocus() == true

    fun moveHorizontal(delta: Int): Boolean {
        val order = order()
        val now = order.indexOfFirst(View::hasFocus)
        val next = (now + delta).coerceIn(order.indices)
        return next != now && order[next].requestFocus()
    }


    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    companion object {
        const val NOTIFICATIONS = 5
        const val SERVICES = 6
        const val SETTINGS = 7
        const val HEIGHT_DP = 48f
    }
}

package com.pocketds.hub.nav

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.pocketds.hub.R
import com.pocketds.hub.state.ContentMode
import com.pocketds.hub.state.Fmt
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.BlobSegmentedView
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable
import com.pocketds.hub.ui.activateOnTap
import com.pocketds.hub.ui.glass.ArtworkPalette
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable
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
 * In Glass ([glass], GLASS_PLAN.md) there is no solid strip at all: the tabs
 * sit in a capsule of tinted glass between the L1 and R1 caps, the selected one
 * a white pill; Media / Books is a glass pill whose chosen side is the accent;
 * the icons are round glass buttons, white while their page is open. A shade
 * behind the bar keeps them readable over bright artwork, and [setPalette]
 * re-tints the glass as the page takes the colour of other artwork.
 *
 * Focus enters from the content with Up and leaves with Down or B, exactly as
 * the header it replaces did; [moveHorizontal] walks every control in order.
 * Both looks are the same views in the same order, so these rules hold in each.
 */
class TopBarView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val tabTitles: List<String>,
    private val glass: Boolean = false
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
    private var scrimHeight = -1
    /** [colors] under a name a GradientDrawable's own `colors` property cannot shadow. */
    private val palette get() = colors

    /** Glass: the page's colours, and the panels tinted with them. */
    private var pagePalette = ArtworkPalette.NEUTRAL
    private val tabsPanel = GlassPanelDrawable(GlassColors.panel(pagePalette), dp(CAPSULE_RADIUS_DP).toFloat())
    private val modePanel = GlassPanelDrawable(GlassColors.panel(pagePalette), dp(CAPSULE_RADIUS_DP).toFloat())
    private val iconPanels = HashMap<Int, GlassPanelDrawable>()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(if (glass) 12 else 16), 0, dp(12), 0)
        setWillNotDraw(false)
        mark = FrameLayout(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        markIcon = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        mark.addView(markIcon, FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER))
        // Glass names the content in the Media / Books pill itself, with its icons.
        if (!glass) addView(mark, LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(10) })
        addView(shoulder("L1"))
        tabs = BlobSegmentedView(context, colors, ringVisible, BlobSegmentedView.Style.PILL).apply {
            padXDp = 11f
            if (glass) {
                textSp = 12.5f
                trackDrawable = tabsPanel
            }
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
            padXDp = if (glass) 10f else 11f
            heightDp = if (glass) 34f else 32f
            if (glass) trackDrawable = modePanel
            visibility = GONE
            setOptions(ContentMode.entries.map {
                BlobSegmentedView.Option(it.stored, it.label, "Show ${it.label.lowercase()}",
                    icon = if (glass) modeIcon(it) else null)
            }, ContentMode.MEDIA.stored)
            onPick = { id -> this@TopBarView.onModeSelected?.invoke(ContentMode.fromStored(id)) }
            onOptionFocused = { this@TopBarView.onFocused?.invoke() }
        }
        addView(modeToggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(if (glass) 4 else 8)
        })
        icon(NOTIFICATIONS, "Notifications", R.drawable.ic_nav_notifications)
        icon(SERVICES, "Services", R.drawable.ic_nav_manage)
        icon(SETTINGS, "Settings", R.drawable.ic_nav_settings)
        badge = if (glass) glassBadge() else View(context).apply {
            background = ThemeGradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.badgeFailed)
                setStroke(dp(2), palette.background)
            }
            visibility = GONE
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        icons.getValue(NOTIFICATIONS).addView(badge, if (glass) {
            // The prototype's count sits over the button's corner.
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(15), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(1); rightMargin = dp(1)
            }
        } else FrameLayout.LayoutParams(dp(10), dp(10), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8); rightMargin = dp(8)
        })
        paintMark()
        paintIcons()
        if (glass) GlassPage.follow(this) { setPalette(it) }
    }

    private fun modeIcon(mode: ContentMode) = if (mode == ContentMode.BOOKS) AppIcon.BOOK else AppIcon.MEDIA

    private fun shoulder(label: String) = TextView(context).apply {
        text = label
        textSize = 9f
        textWeight(if (glass) 800 else 700)
        setTextColor(if (glass) GlassColors.SHOULDER_TEXT else colors.mutedText)
        includeFontPadding = false
        val padV = dp(if (glass) 2 else 1)
        setPadding(dp(4), padV, dp(4), padV)
        background = ThemeGradientDrawable().apply {
            cornerRadius = dp(if (glass) 6 else 5).toFloat()
            setColor(Color.TRANSPARENT)
            if (glass) setStroke(Styler.dpInt(context, 1.5f), GlassColors.SHOULDER_EDGE)
            else setStroke(dp(1), (palette.mutedText and 0x00FFFFFF) or 0x55000000)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Glass: the unread count in a small red pill rather than a dot. */
    private fun glassBadge() = TextView(context).apply {
        textSize = 9f
        textWeight(800)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = dp(15)
        setPadding(dp(4), 0, dp(4), 0)
        background = ThemeGradientDrawable.rounded(dp(8).toFloat(), GlassColors.BADGE)
        visibility = GONE
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
            }, FrameLayout.LayoutParams(dp(if (glass) 16 else 17), dp(if (glass) 16 else 17), Gravity.CENTER))
        }
        icons[index] = button
        // Around a 32dp circle: 44dp of touch in Classic; 40dp in Glass, which
        // sets its buttons closer together, as the prototype does.
        val target = dp(if (glass) 40 else 44)
        addView(button, LayoutParams(target, target))
    }

    private fun paintIcons() {
        if (glass) return paintGlassIcons()
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
                ColorStateList.valueOf(if (on) colors.inverseText else colors.primaryText)
        }
    }

    /**
     * Round glass buttons, white while their page is open. Focus is a white
     * ring standing 2dp outside the button, the prototype's outline, drawn as
     * the foreground so the glass under it is never shared between states.
     */
    private fun paintGlassIcons() {
        icons.forEach { (section, view) ->
            val on = section == current
            view.isSelected = on
            val face = if (on) ThemeGradientDrawable.oval(Color.WHITE)
                else iconPanels.getOrPut(section) { GlassPanelDrawable(GlassColors.panel(pagePalette), dp(16).toFloat()) }
            view.background = InsetDrawable(face, dp(4))
            view.foreground = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), ThemeGradientDrawable.oval(Color.TRANSPARENT, dp(2), Color.WHITE))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            (view.getChildAt(0) as ImageView).imageTintList =
                ColorStateList.valueOf(if (on) GlassColors.INK else Color.WHITE)
        }
    }

    private fun paintMark() {
        mark.background = ThemeGradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.accent)
        }
        markIcon.setImageDrawable(AppIconDrawable(if (activeMode == ContentMode.BOOKS) AppIcon.BOOK else AppIcon.MEDIA, colors.accentText))
    }

    /**
     * Glass: re-tints the capsules and the buttons with the page's new colours.
     * Classic has nothing to tint.
     */
    fun setPalette(next: ArtworkPalette) {
        if (!glass || next == pagePalette) return
        pagePalette = next
        val fill = GlassColors.panel(next)
        tabsPanel.retint(fill)
        modePanel.retint(fill)
        iconPanels.values.forEach { it.retint(fill) }
        // The capsules are drawn by their segmented views rather than set as a
        // background, so nothing else would ask them to redraw.
        tabs.invalidate()
        modeToggle.invalidate()
        icons.values.forEach(View::invalidate)
    }

    /**
     * See-through over a hero image, with a scrim so the tabs stay readable on a
     * bright poster. Glass never has solid ground: its capsules carry their own.
     */
    fun setOverArtwork(over: Boolean) {
        if (glass) {
            overArtwork = over
            return
        }
        if (overArtwork == over && background != null) return
        overArtwork = over
        if (over) setBackgroundColor(Color.TRANSPARENT) else setBackgroundColor(colors.background)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (glass) {
            // The prototype's shade runs 24dp below the bar, over the top of the
            // page; the frame the bar sits in does not clip it.
            val reach = height + dp(24)
            if (reach != scrimHeight) {
                scrimHeight = reach
                scrim.shader = android.graphics.LinearGradient(0f, 0f, 0f, reach.toFloat(),
                    GlassColors.BAR_SCRIM, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            }
            canvas.drawRect(0f, 0f, width.toFloat(), reach.toFloat(), scrim)
        } else if (overArtwork) {
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
        (badge as? TextView)?.text = Fmt.badge(unread)
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
        /** Round ends on a 34dp capsule. */
        private const val CAPSULE_RADIUS_DP = 17
    }
}

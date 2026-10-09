package com.pocketds.hub.reader

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.pocketds.hub.ui.AppIcon
import com.pocketds.hub.ui.AppIconDrawable
import com.pocketds.hub.ui.FocusDecorator
import com.pocketds.hub.ui.OverlayButtons
import com.pocketds.hub.ui.PocketColors
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.activateOnTap

/**
 * The one mode button (#62), in the reader's top bar and on the audiobook screen: it shows only the mode you are in, as its icon in the
 * accent colour, and opens out to every mode the book has (Ebook, Audio, Read along) with the one in focus named ([onCaption]). It
 * closes after five seconds, on a tap beside it, or with Ⓑ; Ⓨ opens it, ◀▶ choose, Ⓐ switches ([ModePicker] holds the rules).
 * A book with one format has no button.
 */
class ModeButtonView(context: Context, private val colors: PocketColors, private val ringVisible: () -> Boolean) : LinearLayout(context) {
    /** Called with the mode to switch to. */
    var onPick: (ReadingMode) -> Unit = {}
    /** Called with the name of the mode in focus while it is open, and with "" when it closes. */
    var onCaption: (String) -> Unit = {}
    /** Called when it opens and when it closes, so the keys and the hint bar follow. */
    var onChanged: () -> Unit = {}
    /** Called when the mode in focus is the one you are in and is picked: a screen with more than one narration offers them. */
    var onPickCurrent: () -> Unit = {}
    /** Called with the segment that took the focus, for the menu's order of controls. */
    var onFocus: (View) -> Unit = {}

    private var picker = ModePicker(emptyList(), ReadingMode.EBOOK)
    private val segments = LinkedHashMap<ReadingMode, ImageView>()
    private val scrim = View(context)
    private val closer = Runnable { tick() }

    val isOpen: Boolean get() = picker.isOpen
    val focusedMode: ReadingMode get() = picker.focused
    val current: ReadingMode get() = picker.current
    val worthShowing: Boolean get() = picker.worthShowing

    /** The segments that are showing, for a test. */
    val shown: List<ReadingMode> get() = segments.filterValues { it.visibility == VISIBLE }.keys.toList()

    /** The segment of the mode you are in: the button when it is closed. */
    val currentSegment: View? get() = segments[picker.current]

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        scrim.visibility = GONE
        scrim.isClickable = true
        scrim.setOnClickListener { close() }
    }

    /** The scrim that closes it on a tap beside it: put in [parent] under [above], the bar the button is in. */
    fun attachScrim(parent: FrameLayout, above: View) {
        (scrim.parent as? ViewGroup)?.removeView(scrim)
        parent.addView(scrim, parent.indexOfChild(above).coerceAtLeast(0), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** The modes the book has and the one this screen is. */
    fun configure(modes: List<ReadingMode>, current: ReadingMode) {
        removeAllViews()
        segments.clear()
        picker = ModePicker(modes, current)
        visibility = if (picker.worthShowing) VISIBLE else GONE
        modes.forEach { mode ->
            val segment = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = mode.label
                isFocusable = false
                Styler.makeFocusable(this)
                FocusDecorator.attach(this, ringVisible, scale = false)
                FocusDecorator.listen(this, ringVisible) { view, focused -> if (focused) onFocus(view) }
                activateOnTap { tapped(mode) }
            }
            segments[mode] = segment
            addView(segment, LayoutParams(dp(44), dp(44)).apply { if (childCount > 1) marginStart = dp(2) })
        }
        render()
    }

    private fun tapped(mode: ReadingMode) {
        if (!picker.isOpen) { open(); return }
        if (mode == picker.current) { close(); return }
        picker.point(mode, SystemClock.uptimeMillis())
        pick()
    }

    fun open() {
        if (!picker.worthShowing || picker.isOpen) return
        picker.open(SystemClock.uptimeMillis())
        scrim.visibility = VISIBLE
        render()
        focusSegment()
        scheduleClose()
        onChanged()
    }

    fun close() {
        if (!picker.isOpen) return
        removeCallbacks(closer)
        picker.close()
        scrim.visibility = GONE
        render()
        onCaption("")
        onChanged()
    }

    /** ◀ or ▶: the next mode over; the caption names it and the five seconds begin again. */
    fun move(delta: Int) {
        if (!picker.isOpen) return
        picker.move(delta, SystemClock.uptimeMillis())
        render()
        focusSegment()
        scheduleClose()
    }

    /** Ⓐ: switch to the mode in focus, which closes the button; one that is the mode you are in just closes it. */
    fun pick() {
        val wasCurrent = picker.isOpen && picker.focused == picker.current
        val chosen = picker.pick()
        removeCallbacks(closer)
        scrim.visibility = GONE
        render()
        onCaption("")
        onChanged()
        if (chosen != null) onPick(chosen) else if (wasCurrent) onPickCurrent()
    }

    private fun scheduleClose() { removeCallbacks(closer); postDelayed(closer, CHECK_MS) }

    private fun tick() {
        if (!picker.isOpen) return
        if (picker.expired(SystemClock.uptimeMillis())) close() else postDelayed(closer, CHECK_MS)
    }

    private fun focusSegment() { segments[picker.focused]?.requestFocus() }

    /** The segments as they are now: the current one in the accent, the rest as glass and only while open. */
    private fun render() {
        val open = picker.isOpen
        segments.forEach { (mode, segment) ->
            val here = mode == picker.current
            segment.visibility = if (here || open) VISIBLE else GONE
            val icon = when (mode) { ReadingMode.EBOOK -> AppIcon.BOOK; ReadingMode.AUDIO -> AppIcon.HEADPHONES; ReadingMode.ALONG -> AppIcon.READ_ALONG }
            if (here) {
                segment.background = OverlayButtons.ringed(context, colors.focusRing, GradientDrawable.OVAL, colors.accent)
                segment.setImageDrawable(AppIconDrawable(icon, colors.accentText).also { it.setBounds(0, 0, dp(20), dp(20)) })
                segment.contentDescription = if (open) "${mode.label}, the mode you are in" else "Reading mode: ${mode.label}. Choose another"
            } else {
                OverlayButtons.dressDisc(segment, colors.focusRing)
                segment.setImageDrawable(AppIconDrawable(icon, Color.WHITE).also { it.setBounds(0, 0, dp(20), dp(20)) })
                segment.contentDescription = mode.label
            }
            val pad = dp(12)
            segment.setPadding(pad, pad, pad, pad)
        }
        if (open) onCaption(picker.focused.label)
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object { const val CHECK_MS = 250L }
}

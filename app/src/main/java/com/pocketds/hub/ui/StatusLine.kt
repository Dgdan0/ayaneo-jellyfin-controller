package com.pocketds.hub.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import com.pocketds.hub.state.StatusMessage
import com.pocketds.hub.state.StatusText
import com.pocketds.hub.state.StatusTone
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * Shows a [StatusMessage], text and colour together.
 *
 * Setting both in one call is the point: screens that set only the text left
 * "Refreshing…" in red after an earlier failure.
 *
 * The line shows only when it has news ([StatusText.shows]), as a quiet chip
 * of the page's glass round its words; otherwise it is gone, so a status line
 * never sits over the artwork saying "4 rows".
 */
fun TextView.showStatus(message: StatusMessage, colors: PocketColors) {
    val shown = StatusText.shows(message)
    text = if (shown) message.text else ""
    setTextColor(
        when (message.tone) {
            StatusTone.NORMAL -> StatusChip.TEXT
            StatusTone.WARNING -> colors.badgePending
            StatusTone.ERROR -> colors.dangerText
        }
    )
    StatusChip.dress(this)
    visibility = if (shown) View.VISIBLE else View.GONE
}

/**
 * A page's own line under its heading ("12 unread notifications", "All 13
 * running"): always shown, in plain words rather than the status chip, because
 * it says what the page holds rather than how fresh it is. A warning or a
 * failure still takes its colour. Lines that should speak only with news use
 * [showStatus].
 */
fun TextView.showSummary(message: StatusMessage, colors: PocketColors) {
    text = message.text
    setTextColor(
        when (message.tone) {
            StatusTone.NORMAL -> SettingsCard.GLASS_QUIET
            StatusTone.WARNING -> colors.badgePending
            StatusTone.ERROR -> colors.dangerText
        }
    )
    visibility = if (message.text.isEmpty()) View.GONE else View.VISIBLE
}

/**
 * The status chip: drawn round the words wherever the line puts them
 * (start, end, one line or two), so every screen's status line becomes a chip
 * without changing its layout. Its tint follows the page.
 */
private class StatusChip(private val view: TextView) : Drawable() {
    private val panel = GlassPanelDrawable(GlassColors.panel(GlassPage.palette(view.context)), Styler.dp(view.context, 999f))
    private val padH = Styler.dpInt(view.context, PAD_H_DP)
    private val padV = Styler.dpInt(view.context, PAD_V_DP)

    fun retint(fill: Int) {
        panel.retint(fill)
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val layout = view.layout ?: return
        if (view.text.isNullOrEmpty() || layout.lineCount == 0) return
        var left = Float.MAX_VALUE
        var right = 0f
        for (line in 0 until layout.lineCount) {
            left = minOf(left, layout.getLineLeft(line))
            right = maxOf(right, layout.getLineRight(line))
        }
        val x = view.totalPaddingLeft - view.scrollX
        val y = view.extendedPaddingTop
        panel.setBounds(
            (x + left - padH).toInt().coerceAtLeast(0),
            (y - padV).coerceAtLeast(0),
            (x + right + padH).toInt().coerceAtMost(view.width),
            (y + layout.height + padV).coerceAtMost(view.height)
        )
        panel.draw(canvas)
    }

    override fun setAlpha(alpha: Int) = panel.setAlpha(alpha)
    override fun setColorFilter(colorFilter: ColorFilter?) = panel.setColorFilter(colorFilter)
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** The words on the chip: white at 85%. */
        const val TEXT = 0xD9FFFFFF.toInt()
        const val PAD_H_DP = 9f
        const val PAD_V_DP = 3f

        /** Once per line: the chip behind it, room round the words for it, and the one follow of the page. */
        fun dress(view: TextView) {
            if (view.background is StatusChip) return
            val chip = StatusChip(view)
            view.background = chip
            val h = Styler.dpInt(view.context, PAD_H_DP)
            val v = Styler.dpInt(view.context, PAD_V_DP)
            view.setPadding(maxOf(view.paddingLeft, h), maxOf(view.paddingTop, v), maxOf(view.paddingRight, h), maxOf(view.paddingBottom, v))
            GlassPage.follow(view) { page -> chip.retint(GlassColors.panel(page)) }
        }
    }
}

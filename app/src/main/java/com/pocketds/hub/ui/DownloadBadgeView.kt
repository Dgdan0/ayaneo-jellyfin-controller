package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.pocketds.hub.offline.EpisodeDownloadMarks

/**
 * An episode card's download corner (#48): a small disc of dark glass with an arrow, a ring that fills with the
 * transfer, a waiting mark, or, once the episode is on the device, an accent disc with an arrow dropping into a tray
 * ([MediaActionIconDrawable.downloadDone]; a tick means watched). [EpisodeDownloadMarks] says which; this only draws it. The disc is [DISC_DP] across and the view round it finger-sized, so a tap lands.
 * It is not a focus stop: on the pad the card's own menu (Ⓨ) is the way to download.
 */
class DownloadBadgeView(context: Context, private val colors: PocketColors) : View(context) {
    private var badge: EpisodeDownloadMarks.Badge = EpisodeDownloadMarks.Badge(EpisodeDownloadMarks.Mark.ARROW)
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(168, 12, 14, 20) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = Styler.dp(context, 1.6f)
    }
    private val done = MediaActionIconDrawable.downloadDone(context, colors)

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun bind(value: EpisodeDownloadMarks.Badge) {
        if (value == badge) return
        badge = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = Styler.dpInt(context, TOUCH_DP)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = Styler.dp(context, DISC_DP) / 2f
        if (badge.mark != EpisodeDownloadMarks.Mark.DONE) canvas.drawCircle(cx, cy, radius, disc)
        val glyph = Styler.dpInt(context, 14f)
        when (badge.mark) {
            EpisodeDownloadMarks.Mark.ARROW -> MediaActionIconDrawable(context, MediaActionIcon.DOWNLOAD, Color.WHITE)
                .also { it.setBounds((cx - glyph / 2).toInt(), (cy - glyph / 2).toInt(), (cx + glyph / 2).toInt(), (cy + glyph / 2).toInt()) }.draw(canvas)
            EpisodeDownloadMarks.Mark.RING -> {
                val ring = Styler.dpInt(context, 19f)
                MediaActionIconDrawable(context, MediaActionIcon.DOWNLOADING, Color.WHITE, badge.progress, colors.accent, 0x59FFFFFF)
                    .also { it.setBounds((cx - ring / 2).toInt(), (cy - ring / 2).toInt(), (cx + ring / 2).toInt(), (cy + ring / 2).toInt()) }.draw(canvas)
            }
            EpisodeDownloadMarks.Mark.WAITING -> {
                // A clock: it will start by itself.
                line.color = Color.argb(230, 255, 255, 255)
                val r = Styler.dp(context, 5.5f)
                canvas.drawCircle(cx, cy, r, line)
                canvas.drawLine(cx, cy, cx, cy - r * .6f, line)
                canvas.drawLine(cx, cy, cx + r * .45f, cy + r * .2f, line)
            }
            EpisodeDownloadMarks.Mark.DONE -> {
                done.setBounds((cx - radius).toInt(), (cy - radius).toInt(), (cx + radius).toInt(), (cy + radius).toInt())
                done.draw(canvas)
            }
        }
    }

    companion object {
        /** The disc the eye sees, and the finger-sized view around it. */
        const val DISC_DP = 22f
        const val TOUCH_DP = 34f
    }
}

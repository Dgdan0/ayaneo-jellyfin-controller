package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.pocketds.hub.offline.StorageBar

/**
 * The storage bar (#48), one view for every place it appears: the series page when a download starts, the choices
 * panel (previewing what a choice adds) and select mode's bottom bar. A line of words over a thin line split into
 * other apps, JellyHub, what is coming or being added (the theme's accent) and what is free; what each share is
 * is [StorageBar]'s.
 */
class StorageBarView(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    private val words = TextView(context).apply {
        textSize = 12f; textWeight(600); setTextColor(colors.primaryText); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val bar = Bar(context, colors)
    private var model = StorageBar.of(0, 0, 0, 0)

    init {
        orientation = VERTICAL
        addView(words, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, Styler.dpInt(context, BAR_DP)).apply { topMargin = Styler.dpInt(context, 6f) })
    }

    /** The figures, and the line beside them. */
    fun bind(value: StorageBar.Model, line: String) {
        model = value
        words.text = line
        bar.model = value
        contentDescription = line
    }

    val line: String get() = words.text.toString()
    val segments: StorageBar.Segments get() = model.segments

    private class Bar(context: Context, private val colors: PocketColors) : View(context) {
        var model: StorageBar.Model = StorageBar.of(0, 0, 0, 0)
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(0f, 0f, w, h)
            clip.reset(); clip.addRoundRect(rect, h / 2, h / 2, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            // The track, then the shares left to right: other apps, JellyHub, coming, and free as what is left of the track.
            paint.color = ColorUtils.setAlphaComponent(colors.primaryText, FREE_ALPHA)
            canvas.drawRect(rect, paint)
            val s = model.segments
            var x = 0f
            // A download is a sliver of a disk: JellyHub's and what is coming never draw thinner than a few dp, so they show.
            fun segment(share: Double, color: Int, minDp: Float = 0f) {
                if (share <= 0.0) return
                paint.color = color
                val width = maxOf((share * w).toFloat(), Styler.dp(context, minDp))
                canvas.drawRect(x, 0f, (x + width).coerceAtMost(w), h, paint)
                x += width
            }
            segment(s.other, ColorUtils.setAlphaComponent(colors.primaryText, OTHER_ALPHA))
            segment(s.app, ColorUtils.setAlphaComponent(colors.primaryText, APP_ALPHA), minDp = 4f)
            segment(s.coming, if (model.overflow) colors.dangerText else colors.accent, minDp = 6f)
            canvas.restore()
        }
    }

    companion object {
        const val BAR_DP = 6f
        private const val FREE_ALPHA = 0x1F
        private const val OTHER_ALPHA = 0x59
        private const val APP_ALPHA = 0xB3
    }
}

package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.pocketds.hub.screens.library.SeriesFan
import com.pocketds.hub.screens.library.SeriesFan.Part

/**
 * The thin bar under a series' caption (#54): the whole series at a glance, the same width for every series, cut
 * into a part for each book: gold for one you have read, white for the one you are on, grey for one to read, an
 * outline for one you do not have. Past [SeriesFan.CONTINUOUS_AFTER] books it is one line, gold as far as you
 * have read, with a white mark where you are on. [SeriesFan.plan] says which; this draws it.
 */
class SeriesBarView(context: Context, private val colors: PocketColors) : View(context) {
    private var bar: SeriesFan.Bar = SeriesFan.Bar.Segments(emptyList())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val density = resources.displayMetrics.density

    fun show(next: SeriesFan.Bar) {
        if (bar == next) return
        bar = next
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (HEIGHT_DP * density).toInt())

    override fun onDraw(canvas: Canvas) {
        val height = HEIGHT_DP * density
        val radius = height / 2
        when (val value = bar) {
            is SeriesFan.Bar.Segments -> {
                val parts = value.parts
                if (parts.isEmpty()) return
                val gap = minOf(GAP_DP * density, width / parts.size * 0.4f)
                val each = (width - gap * (parts.size - 1)) / parts.size
                parts.forEachIndexed { i, part ->
                    val left = i * (each + gap)
                    box.set(left, 0f, left + each, height)
                    fill(canvas, part, radius.coerceAtMost(each / 2))
                }
            }
            is SeriesFan.Bar.Continuous -> {
                box.set(0f, 0f, width.toFloat(), height)
                paint.style = Paint.Style.FILL
                paint.color = GREY
                canvas.drawRoundRect(box, radius, radius, paint)
                if (value.readFraction > 0f) {
                    box.set(0f, 0f, (width * value.readFraction).coerceAtLeast(height), height)
                    paint.color = colors.accent
                    canvas.drawRoundRect(box, radius, radius, paint)
                }
                value.mark?.let { at ->
                    val mark = MARK_DP * density
                    val x = (width * at).coerceIn(mark / 2, width - mark / 2)
                    box.set(x - mark / 2, -1.5f * density, x + mark / 2, height + 1.5f * density)
                    paint.color = Color.WHITE
                    canvas.drawRoundRect(box, mark / 2, mark / 2, paint)
                }
            }
        }
    }

    private fun fill(canvas: Canvas, part: Part, radius: Float) {
        when (part) {
            Part.MISSING -> {
                // Inside the part's own place, so the outline does not run into its neighbours.
                val stroke = 1f * density
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = stroke
                paint.color = OUTLINE
                box.inset(stroke / 2, stroke / 2)
                canvas.drawRoundRect(box, radius, radius, paint)
            }
            else -> {
                paint.style = Paint.Style.FILL
                paint.color = when (part) {
                    Part.READ -> colors.accent
                    Part.ON -> Color.WHITE
                    else -> GREY
                }
                canvas.drawRoundRect(box, radius, radius, paint)
            }
        }
    }

    private companion object {
        const val HEIGHT_DP = 4f
        const val GAP_DP = 2f
        const val MARK_DP = 3f
        /** White at 24%: a book to read. */
        val GREY = Color.argb(61, 255, 255, 255)
        /** White at 55%: the outline of a book you do not have. */
        val OUTLINE = Color.argb(140, 255, 255, 255)
    }
}

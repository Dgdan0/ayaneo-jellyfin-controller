package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.min

/** Icon-only action whose artwork is centred in its touch target, independent of text padding. */
class CenteredIconTextView(context: Context) : TextView(context) {
    private var icon: Drawable? = null
    private var iconSize = 0
    private var iconGap = 0

    fun setCenteredIcon(drawable: Drawable, size: Int = drawable.intrinsicWidth, gap: Int = 0) {
        icon = drawable
        iconSize = size.coerceAtLeast(1)
        iconGap = gap.coerceAtLeast(0)
        requestLayout()
        invalidate()
    }

    fun recolorIcon(replacements: Map<Int, Int>) { (icon as? AppIconDrawable)?.recolor(replacements); (icon as? MediaActionIconDrawable)?.recolor(replacements); invalidate() }

    fun centeredIconBounds(): Rect {
        val half = iconSize / 2
        val labelWidth = if (text.isNullOrEmpty()) 0f else paint.measureText(text.toString())
        val left = if (labelWidth > 0f) ((width - iconSize - iconGap - labelWidth) / 2f).toInt()
            else width / 2 - half
        val top = height / 2 - half
        return Rect(left, top, left + iconSize, top + iconSize)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (icon == null || text.isNullOrEmpty()) return
        val desired = ceil(paint.measureText(text.toString())).toInt() + iconSize + iconGap + paddingLeft + paddingRight
        val width = when (View.MeasureSpec.getMode(widthMeasureSpec)) {
            View.MeasureSpec.EXACTLY -> measuredWidth
            View.MeasureSpec.AT_MOST -> min(View.MeasureSpec.getSize(widthMeasureSpec), maxOf(measuredWidth, desired))
            else -> maxOf(measuredWidth, desired)
        }
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        if (icon == null || text.isNullOrEmpty()) super.onDraw(canvas)
        else {
            val metrics = paint.fontMetrics
            val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
            paint.color = currentTextColor
            canvas.drawText(text.toString(), centeredIconBounds().right + iconGap.toFloat(), baseline, paint)
        }
        icon?.apply {
            bounds = centeredIconBounds()
            draw(canvas)
        }
    }
}

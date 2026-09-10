package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Small media-action symbols used beside labels on item detail buttons. */
enum class MediaActionIcon {
    PLAY,
    START_OVER,
    OPTIONS,
    WATCHED,
    UNWATCHED,
    FAVOURITE,
    NOT_FAVOURITE,
    DOWNLOAD,
    DOWNLOADED
}

class MediaActionIconDrawable(
    context: Context,
    private val icon: MediaActionIcon,
    color: Int
) : Drawable() {
    private val size = Styler.dpInt(context, 21f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val scale = min(bounds.width(), bounds.height()) / 24f
        canvas.save()
        canvas.translate(bounds.exactCenterX(), bounds.exactCenterY())
        canvas.scale(scale, scale)
        paint.strokeWidth = 1.9f
        when (icon) {
            MediaActionIcon.PLAY -> play(canvas)
            MediaActionIcon.START_OVER -> startOver(canvas)
            MediaActionIcon.OPTIONS -> options(canvas)
            MediaActionIcon.WATCHED -> eye(canvas, crossed = false)
            MediaActionIcon.UNWATCHED -> eye(canvas, crossed = true)
            MediaActionIcon.FAVOURITE -> star(canvas, filled = true)
            MediaActionIcon.NOT_FAVOURITE -> star(canvas, filled = false)
            MediaActionIcon.DOWNLOAD -> download(canvas, complete = false)
            MediaActionIcon.DOWNLOADED -> download(canvas, complete = true)
        }
        canvas.restore()
    }

    private fun play(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(-6f, -9f)
        path.lineTo(9f, 0f)
        path.lineTo(-6f, 9f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun startOver(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(-8f, -8f, 8f, 8f), -52f, 288f, false, paint)
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(-9.5f, -8.5f)
        path.lineTo(-2.5f, -8.2f)
        path.lineTo(-7.1f, -2.5f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun options(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        for (y in listOf(-7f, 0f, 7f)) canvas.drawLine(-9f, y, 9f, y, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(-3.5f, -7f, 2.5f, paint)
        canvas.drawCircle(4f, 0f, 2.5f, paint)
        canvas.drawCircle(-1f, 7f, 2.5f, paint)
    }

    private fun eye(canvas: Canvas, crossed: Boolean) {
        paint.style = Paint.Style.STROKE
        path.reset()
        path.moveTo(-10f, 0f)
        path.cubicTo(-5.5f, -6.5f, 5.5f, -6.5f, 10f, 0f)
        path.cubicTo(5.5f, 6.5f, -5.5f, 6.5f, -10f, 0f)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(0f, 0f, 3.1f, paint)
        if (crossed) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f
            canvas.drawLine(-8f, -8f, 8f, 8f, paint)
        }
    }

    private fun star(canvas: Canvas, filled: Boolean) {
        path.reset()
        for (point in 0 until 10) {
            val radius = if (point % 2 == 0) 9.5f else 4.2f
            val angle = -PI / 2 + point * PI / 5
            val x = (cos(angle) * radius).toFloat()
            val y = (sin(angle) * radius).toFloat()
            if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        canvas.drawPath(path, paint)
    }

    private fun download(canvas: Canvas, complete: Boolean) {
        paint.style = Paint.Style.STROKE
        canvas.drawLine(0f, -9f, 0f, 5f, paint)
        canvas.drawLine(-5f, 0f, 0f, 5f, paint)
        canvas.drawLine(5f, 0f, 0f, 5f, paint)
        canvas.drawLine(-8f, 9f, 8f, 9f, paint)
        if (complete) {
            paint.strokeWidth = 2.4f
            canvas.drawLine(-7f, -2f, -3f, 2f, paint)
            canvas.drawLine(-3f, 2f, 5f, -6f, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = size
    override fun getIntrinsicHeight(): Int = size
}

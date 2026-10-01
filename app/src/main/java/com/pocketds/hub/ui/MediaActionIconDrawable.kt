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
    DOWNLOADING,
    DOWNLOADED,
    MORE;

    /**
     * A state that is on: watched, favourite, downloaded. On is drawn filled in
     * the accent colour and off as an outline in the text colour, so the three
     * toggles beside Play read the same way. A tick drawn over the download
     * arrow was hard to read at 21dp and meant nothing next to the others.
     */
    val isOn: Boolean get() = this == WATCHED || this == FAVOURITE || this == DOWNLOADED
}

class MediaActionIconDrawable(
    context: Context,
    private val icon: MediaActionIcon,
    color: Int,
    /** DOWNLOADING only: how far the ring is filled, 0..1. */
    private val progress: Float = 0f,
    private val ringColor: Int = color,
    private val ringTrackColor: Int = color
) : Drawable() {
    companion object {
        /** The icon in its state's colours: accent when on, text colour when off. */
        fun of(context: Context, icon: MediaActionIcon, colors: PocketColors, progress: Float = 0f) =
            MediaActionIconDrawable(context, icon, if (icon.isOn) colors.accent else colors.primaryText,
                progress, colors.accent, colors.mutedText)
    }

    private val size = Styler.dpInt(context, 21f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    fun recolor(replacements:Map<Int,Int>) { replacements[paint.color]?.let {paint.color=it;invalidateSelf()} }

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
            MediaActionIcon.WATCHED -> eye(canvas, filled = true)
            MediaActionIcon.UNWATCHED -> eye(canvas, filled = false)
            MediaActionIcon.FAVOURITE -> star(canvas, filled = true)
            MediaActionIcon.NOT_FAVOURITE -> star(canvas, filled = false)
            MediaActionIcon.DOWNLOAD -> download(canvas)
            MediaActionIcon.DOWNLOADING -> downloading(canvas)
            MediaActionIcon.DOWNLOADED -> downloaded(canvas)
            MediaActionIcon.MORE -> {
                paint.style = Paint.Style.FILL
                for (x in listOf(-7f, 0f, 7f)) canvas.drawCircle(x, 0f, 1.6f, paint)
            }
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

    private fun eye(canvas: Canvas, filled: Boolean) {
        path.reset()
        path.moveTo(-10.5f, 0f)
        path.cubicTo(-5.5f, -7f, 5.5f, -7f, 10.5f, 0f)
        path.cubicTo(5.5f, 7f, -5.5f, 7f, -10.5f, 0f)
        path.close()
        if (filled) {
            // A solid eye with the pupil cut out, so it still reads as an eye.
            path.addCircle(0f, 0f, 2.8f, Path.Direction.CW)
            path.fillType = Path.FillType.EVEN_ODD
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            path.fillType = Path.FillType.WINDING
        } else {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(0f, 0f, 3.1f, paint)
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

    private fun download(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        canvas.drawLine(0f, -9f, 0f, 5f, paint)
        canvas.drawLine(-5f, 0f, 0f, 5f, paint)
        canvas.drawLine(5f, 0f, 0f, 5f, paint)
        canvas.drawLine(-8f, 9f, 8f, 9f, paint)
    }

    /** The same arrow and tray, solid. */
    private fun downloaded(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(-2.4f, -9.5f); path.lineTo(2.4f, -9.5f); path.lineTo(2.4f, -1f)
        path.lineTo(7f, -1f); path.lineTo(0f, 6.5f); path.lineTo(-7f, -1f); path.lineTo(-2.4f, -1f)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.8f
        canvas.drawLine(-8.5f, 9.5f, 8.5f, 9.5f, paint)
    }

    /** A ring that fills with the transfer, around a smaller arrow. */
    private fun downloading(canvas: Canvas) {
        val color = paint.color
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = ringTrackColor
        canvas.drawCircle(0f, 0f, 10.8f, paint)
        paint.color = ringColor
        canvas.drawArc(RectF(-10.8f, -10.8f, 10.8f, 10.8f), -90f, 360f * progress.coerceIn(0f, 1f), false, paint)
        paint.color = color
        paint.strokeWidth = 1.7f
        canvas.drawLine(0f, -5.5f, 0f, 3.5f, paint)
        canvas.drawLine(-3.5f, 0f, 0f, 3.5f, paint)
        canvas.drawLine(3.5f, 0f, 0f, 3.5f, paint)
        canvas.drawLine(-4.5f, 6f, 4.5f, 6f, paint)
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

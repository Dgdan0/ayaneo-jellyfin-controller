package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Media control icons drawn in-app so their appearance does not depend on device fonts. */
internal enum class PlayerControlIcon {
    AUDIO,
    SUBTITLES,
    OPTIONS,
    PICTURE_IN_PICTURE,
    CLOSE,
    PREVIOUS,
    REWIND,
    PLAY,
    PAUSE,
    FORWARD,
    NEXT
}

internal class PlayerIconButton(
    context: Context,
    icon: PlayerControlIcon
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    var icon: PlayerControlIcon = icon
        private set

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        // A tight dark halo keeps the white symbol legible over bright video
        // while allowing top-row controls to remain box-free.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        paint.setShadowLayer(2.5f, 0f, 0f, Color.BLACK)
    }

    fun setIcon(value: PlayerControlIcon) {
        if (icon == value) return
        icon = value
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.alpha = if (isEnabled) 255 else 105
        val unit = min(width, height) * 0.56f
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(unit, unit)
        paint.strokeWidth = 0.075f

        when (icon) {
            PlayerControlIcon.AUDIO -> drawAudio(canvas)
            PlayerControlIcon.SUBTITLES -> drawSubtitles(canvas)
            PlayerControlIcon.OPTIONS -> drawOptions(canvas)
            PlayerControlIcon.PICTURE_IN_PICTURE -> drawPictureInPicture(canvas)
            PlayerControlIcon.CLOSE -> drawClose(canvas)
            PlayerControlIcon.PREVIOUS -> drawPrevious(canvas)
            PlayerControlIcon.REWIND -> drawRewind(canvas)
            PlayerControlIcon.PLAY -> drawPlay(canvas)
            PlayerControlIcon.PAUSE -> drawPause(canvas)
            PlayerControlIcon.FORWARD -> drawForward(canvas)
            PlayerControlIcon.NEXT -> drawNext(canvas)
        }
        canvas.restore()
    }

    private fun drawAudio(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(-0.36f, -0.12f)
        path.lineTo(-0.18f, -0.12f)
        path.lineTo(0.02f, -0.31f)
        path.lineTo(0.02f, 0.31f)
        path.lineTo(-0.18f, 0.12f)
        path.lineTo(-0.36f, 0.12f)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(-0.08f, -0.24f, 0.31f, 0.24f), -48f, 96f, false, paint)
        canvas.drawArc(RectF(-0.10f, -0.36f, 0.47f, 0.36f), -47f, 94f, false, paint)
    }

    private fun drawSubtitles(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        canvas.drawRoundRect(RectF(-0.39f, -0.29f, 0.39f, 0.29f), 0.08f, 0.08f, paint)
        paint.strokeWidth = 0.065f
        canvas.drawLine(-0.27f, 0.04f, -0.06f, 0.04f, paint)
        canvas.drawLine(0.05f, 0.04f, 0.27f, 0.04f, paint)
        canvas.drawLine(-0.27f, 0.17f, 0.02f, 0.17f, paint)
        canvas.drawLine(0.12f, 0.17f, 0.27f, 0.17f, paint)
    }

    private fun drawOptions(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.065f
        canvas.drawLine(-0.34f, -0.22f, 0.34f, -0.22f, paint)
        canvas.drawLine(-0.34f, 0f, 0.34f, 0f, paint)
        canvas.drawLine(-0.34f, 0.22f, 0.34f, 0.22f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(-0.12f, -0.22f, 0.09f, paint)
        canvas.drawCircle(0.15f, 0f, 0.09f, paint)
        canvas.drawCircle(-0.03f, 0.22f, 0.09f, paint)
    }

    private fun drawPictureInPicture(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.065f
        canvas.drawRoundRect(RectF(-0.39f, -0.3f, 0.39f, 0.3f), 0.06f, 0.06f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(0.02f, 0.01f, 0.31f, 0.22f), 0.035f, 0.035f, paint)
    }

    private fun drawClose(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.085f
        canvas.drawLine(-0.27f, -0.27f, 0.27f, 0.27f, paint)
        canvas.drawLine(0.27f, -0.27f, -0.27f, 0.27f, paint)
    }

    private fun drawPrevious(canvas: Canvas) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.08f
        canvas.drawLine(-0.31f, -0.29f, -0.31f, 0.29f, paint)
        triangle(canvas, 0.24f, -0.31f, -0.20f, 0f, 0.24f, 0.31f)
    }

    private fun drawRewind(canvas: Canvas) {
        triangle(canvas, 0.34f, -0.29f, -0.04f, 0f, 0.34f, 0.29f)
        triangle(canvas, -0.02f, -0.29f, -0.40f, 0f, -0.02f, 0.29f)
    }

    private fun drawPlay(canvas: Canvas) =
        triangle(canvas, -0.22f, -0.34f, 0.33f, 0f, -0.22f, 0.34f)

    private fun drawPause(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(-0.28f, -0.33f, -0.08f, 0.33f), 0.045f, 0.045f, paint)
        canvas.drawRoundRect(RectF(0.08f, -0.33f, 0.28f, 0.33f), 0.045f, 0.045f, paint)
    }

    private fun drawForward(canvas: Canvas) {
        triangle(canvas, -0.34f, -0.29f, 0.04f, 0f, -0.34f, 0.29f)
        triangle(canvas, 0.02f, -0.29f, 0.40f, 0f, 0.02f, 0.29f)
    }

    private fun drawNext(canvas: Canvas) {
        triangle(canvas, -0.24f, -0.31f, 0.20f, 0f, -0.24f, 0.31f)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.08f
        canvas.drawLine(0.31f, -0.29f, 0.31f, 0.29f, paint)
    }

    private fun triangle(
        canvas: Canvas,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float
    ) {
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
        canvas.drawPath(path, paint)
    }
}

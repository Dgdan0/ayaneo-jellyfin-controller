package com.pocketds.hub.playback

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.pocketds.hub.ui.Styler
import kotlin.math.roundToInt

/** Compact vertical feedback for brightness and media volume gestures. */
internal class PlayerLevelView(context: Context) : View(context) {
    enum class Kind(val label: String) { BRIGHTNESS("Brightness"), VOLUME("Volume") }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var level = 0.5f
    private var kind = Kind.BRIGHTNESS

    init {
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = Styler.dp(context, 17f)
            setColor(Color.argb(220, 16, 18, 23))
            setStroke(Styler.dpInt(context, 1f), Color.argb(115, 255, 255, 255))
        }
    }

    fun show(kind: Kind, value: Float) {
        this.kind = kind
        level = value.coerceIn(0f, 1f)
        contentDescription = "${kind.label}, ${(level * 100).roundToInt()} percent"
        visibility = VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val top = Styler.dp(context, 56f)
        val bottom = height - Styler.dp(context, 28f)
        val radius = Styler.dp(context, 5f)
        val fillTop = bottom - (bottom - top) * level

        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = Styler.dp(context, 12f)
        paint.color = Color.WHITE
        canvas.drawText("${(level * 100).roundToInt()}%", cx, Styler.dp(context, 25f), paint)
        paint.typeface = android.graphics.Typeface.DEFAULT
        paint.textSize = Styler.dp(context, 9f)
        paint.color = Color.argb(205, 255, 255, 255)
        canvas.drawText(kind.label, cx, Styler.dp(context, 42f), paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(105, 255, 255, 255)
        canvas.drawRoundRect(RectF(cx - radius, top, cx + radius, bottom), radius, radius, paint)
        paint.color = Color.rgb(49, 213, 202)
        canvas.drawRoundRect(RectF(cx - radius, fillTop, cx + radius, bottom), radius, radius, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx, fillTop, Styler.dp(context, 7f), paint)
    }
}

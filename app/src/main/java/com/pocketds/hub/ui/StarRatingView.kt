package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.PathParser
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.screens.library.ReadingStars

/**
 * Your stars under a book's cover (#39): five, filled in the accent up to your rating. It is one control
 * on the pad: when it has focus the cursor stands on a star, Left and Right move it and Ⓐ rates with it
 * (the star you already gave takes the rating away, [ReadingStars.after]); a finger on a star rates with
 * that star. What a choice means is [ReadingStars]'; this only draws and reports it through [onRate].
 */
class StarRatingView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : View(context) {
    /** Your rating, 0 for none. */
    var rating = 0
        set(value) { if (field != value) { field = value; describe(); invalidate() } }
    /** A star chosen, or null for the rating taken away. */
    var onRate: (Int?) -> Unit = {}
    /** The cursor moved, for the hint bar. */
    var onCursor: () -> Unit = {}
    var cursor = ReadingStars.cursor(0)
        private set

    private val star: Path = PathParser.createPathFromPathData(AppIcon.STAR_PATH)!!
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val scratch = Path()
    private val matrix = Matrix()
    private val box = RectF()

    init {
        Styler.makeFocusable(this)
        FocusDecorator.attach(this, ringVisible, scale = false)
        FocusDecorator.listen(this, ringVisible) { _, focused ->
            if (focused) cursor = ReadingStars.cursor(rating)
            describe(); invalidate(); onCursor()
        }
        describe()
    }

    private fun describe() { contentDescription = ReadingStars.description(rating, if (isFocused) cursor else null) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(WIDTH_DP), widthMeasureSpec), resolveSize(dp(HEIGHT_DP), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(RING_DP).toFloat()
        val across = width - 2 * pad
        val slot = across / ReadingStars.COUNT
        val size = minOf(slot - dp(GAP_DP), height - 2 * pad)
        val directional = isFocused && ringVisible()
        outline.strokeWidth = dp(1).toFloat() * 1.4f
        for (index in 1..ReadingStars.COUNT) {
            val onCursor = directional && index == cursor
            val lit = index <= rating
            val scale = (if (onCursor) 1.18f else 1f) * size / 24f
            val left = pad + (index - 1) * slot + (slot - size) / 2f
            val top = (height - size) / 2f
            matrix.setScale(scale, scale)
            matrix.postTranslate(left - (if (onCursor) size * 0.09f else 0f), top - (if (onCursor) size * 0.09f else 0f))
            scratch.reset(); star.transform(matrix, scratch)
            if (lit) { fill.color = colors.accent; canvas.drawPath(scratch, fill) }
            outline.color = when { onCursor -> android.graphics.Color.WHITE; lit -> colors.accent; else -> QUIET }
            canvas.drawPath(scratch, outline)
        }
        if (directional) {
            ring.color = colors.focusRing
            ring.strokeWidth = dp(2).toFloat()
            box.set(ring.strokeWidth / 2, ring.strokeWidth / 2, width - ring.strokeWidth / 2, height - ring.strokeWidth / 2)
            canvas.drawRoundRect(box, dp(10).toFloat(), dp(10).toFloat(), ring)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                if (event.x in 0f..width.toFloat() && event.y in 0f..height.toFloat()) {
                    val pad = dp(RING_DP).toFloat()
                    choose(ReadingStars.starAt(event.x - pad, width - 2 * pad))
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** A star chosen, by finger or by Ⓐ. */
    private fun choose(star: Int) {
        onRate(ReadingStars.after(rating, star))
    }

    /** @return true when [action] was for the stars. Only while they have focus. */
    fun onPad(action: PadAction): Boolean {
        if (!isFocused) return false
        return when {
            action is PadAction.Step && action.direction == Direction.LEFT -> { moveCursor(-1); true }
            action is PadAction.Step && action.direction == Direction.RIGHT -> { moveCursor(1); true }
            action == PadAction.Activate -> { choose(cursor); true }
            else -> false
        }
    }

    private fun moveCursor(delta: Int) {
        cursor = ReadingStars.step(cursor, delta)
        describe(); invalidate(); onCursor()
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val WIDTH_DP = 112
        const val HEIGHT_DP = 30
        const val GAP_DP = 3
        const val RING_DP = 3
        /** An empty star: white at 40%. */
        const val QUIET = 0x66FFFFFF
    }
}

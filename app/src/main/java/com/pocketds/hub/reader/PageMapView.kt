package com.pocketds.hub.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.pocketds.hub.ui.Styler

/**
 * The small map of a comic page in a corner (#16, C2): the page's outline at
 * its own shape, the steps down it as faint bands, and the part on screen
 * outlined in white. It shows for a moment after each step or pan, so you
 * know where on the page you are; a tap on a band goes to that step.
 */
class PageMapView(context: Context) : View(context) {
    /** A tap chose this step. */
    var onStep: ((Int) -> Unit)? = null

    private var pageAspect = 1.5f
    private var steps: List<NormalizedViewport> = emptyList()
    private var current = 0
    /** What shows when it is not a whole step: a pan or a zoom. */
    private var visible: NormalizedViewport? = null
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Styler.dp(context, 1f); color = Color.argb(150, 255, 255, 255)
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Styler.dp(context, 1f); color = Color.argb(90, 255, 255, 255)
    }
    private val viewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Styler.dp(context, 2f); color = Color.WHITE
    }
    private val viewFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val page = RectF()
    private val rect = RectF()
    private val hide = Runnable { visibility = GONE }

    init {
        visibility = GONE
        contentDescription = "Where on the page"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Step [current] of [steps] on a page [pageHeight]/[pageWidth] tall, shown for a moment. */
    fun showStep(pageWidth: Int, pageHeight: Int, steps: List<NormalizedViewport>, current: Int) {
        pageAspect = aspect(pageWidth, pageHeight)
        this.steps = steps
        this.current = current
        visible = null
        flash()
    }

    /** A free view of the page (a pan, a zoom): [view] is the part on screen. */
    fun showView(pageWidth: Int, pageHeight: Int, view: NormalizedViewport) {
        pageAspect = aspect(pageWidth, pageHeight)
        steps = emptyList()
        visible = view
        flash()
    }

    fun dismiss() {
        removeCallbacks(hide)
        visibility = GONE
    }

    private fun flash() {
        visibility = VISIBLE
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, SHOW_MS)
    }

    private fun aspect(width: Int, height: Int): Float = if (width > 0 && height > 0) height.toFloat() / width else 1.5f

    override fun onDraw(canvas: Canvas) {
        // The page at its own shape, as large as the view allows.
        val w = width.toFloat()
        val h = height.toFloat()
        val pageWidth = minOf(w, h / pageAspect)
        val pageHeight = pageWidth * pageAspect
        page.set(w - pageWidth, 0f, w, pageHeight)
        val corner = Styler.dp(context, 3f)
        canvas.drawRoundRect(page, corner, corner, pagePaint)
        canvas.drawRoundRect(page, corner, corner, edgePaint)
        steps.forEachIndexed { index, step ->
            if (index == current) return@forEachIndexed
            place(step); canvas.drawRect(rect, bandPaint)
        }
        val shown = visible ?: steps.getOrNull(current) ?: return
        place(shown)
        canvas.drawRect(rect, viewFill)
        canvas.drawRect(rect, viewPaint)
    }

    private fun place(view: NormalizedViewport) {
        rect.set(
            page.left + page.width() * view.left.toFloat().coerceIn(0f, 1f),
            page.top + page.height() * view.top.toFloat().coerceIn(0f, 1f),
            page.left + page.width() * view.right.toFloat().coerceIn(0f, 1f),
            page.top + page.height() * view.bottom.toFloat().coerceIn(0f, 1f)
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (steps.size < 2 || visibility != VISIBLE) return false
        if (event.actionMasked == MotionEvent.ACTION_UP && page.height() > 0f) {
            val y = ((event.y - page.top) / page.height()).coerceIn(0f, 1f)
            val step = steps.indexOfFirst { y >= it.top && y <= it.bottom }.takeIf { it >= 0 }
                ?: steps.indices.minByOrNull { kotlin.math.abs((steps[it].top + steps[it].bottom) / 2 - y) } ?: return true
            onStep?.invoke(step)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        super.onDetachedFromWindow()
    }

    companion object {
        const val SHOW_MS = 1_400L
    }
}

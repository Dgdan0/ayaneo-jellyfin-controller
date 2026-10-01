package com.pocketds.hub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * A short vertical list of places, such as Settings' sections, with the same
 * sliding blob as [BlobSegmentedView], standing up: the chosen row has a raised
 * pill and a coloured dot, and moving focus along the list picks as it goes.
 */
class SideNavView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {
    data class Item(val id: String, val label: String)

    var onPick: ((String) -> Unit)? = null
    var selected: String? = null
        private set
    private val rows = mutableListOf<TextView>()
    private var items = emptyList<Item>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var blobTop = -1f
    private var animator: ValueAnimator? = null

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    fun setItems(next: List<Item>, chosen: String) {
        items = next
        removeAllViews()
        rows.clear()
        next.forEach { item ->
            val row = TextView(context).apply {
                text = item.label
                textSize = 13.5f
                textWeight(600)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(30), 0, dp(12), 0)
                contentDescription = item.label
                Styler.makeFocusable(this)
                setOnFocusChangeListener { _, focused ->
                    this@SideNavView.invalidate()
                    if (focused && item.id != selected) pick(item.id)
                }
                activateOnTap { pick(item.id) }
            }
            rows += row
            addView(row, LayoutParams(MATCH, dp(ROW_DP)).apply { bottomMargin = dp(GAP_DP) })
        }
        select(chosen, animate = false)
    }

    fun focus(id: String? = selected): Boolean = rows.getOrNull(items.indexOfFirst { it.id == id })?.requestFocus() == true

    fun select(id: String, animate: Boolean = true) {
        val index = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        selected = id
        rows.forEachIndexed { i, row -> row.setTextColor(if (i == index) colors.primaryText else colors.mutedText); row.isSelected = i == index }
        val target = index * (dp(ROW_DP) + dp(GAP_DP)).toFloat()
        animator?.cancel()
        if (!animate || blobTop < 0 || !ValueAnimator.areAnimatorsEnabled()) {
            blobTop = target
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(blobTop, target).apply {
            duration = 380L
            interpolator = PathInterpolator(0.3f, 1.35f, 0.5f, 1f)
            addUpdateListener { blobTop = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun pick(id: String) {
        select(id)
        onPick?.invoke(id)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (blobTop >= 0 && rows.isNotEmpty()) {
            val h = dp(ROW_DP).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.blendARGB(colors.cardSurface, colors.primaryText, 0.06f)
            rect.set(0f, blobTop, width.toFloat(), blobTop + h)
            canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), paint)
            paint.color = colors.accent
            canvas.drawCircle(dp(15).toFloat(), blobTop + h / 2, dp(4).toFloat(), paint)
        }
        super.dispatchDraw(canvas)
        rows.firstOrNull { it.isFocused && ringVisible() }?.let { row ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = colors.focusRing
            rect.set(row.left + 1f, row.top + 1f, row.right - 1f, row.bottom - 1f)
            canvas.drawRoundRect(rect, dp(12).toFloat(), dp(12).toFloat(), paint)
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        const val ROW_DP = 40
        const val GAP_DP = 4
    }
}

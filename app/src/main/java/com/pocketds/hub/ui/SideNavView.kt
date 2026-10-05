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

/**
 * A short vertical list of places, such as Settings' sections, with the same
 * sliding blob as [BlobSegmentedView], standing up: the chosen row has a raised
 * pill and a coloured dot, and moving focus along the list picks as it goes.
 *
 * On Glass it is the prototype's `.snav`: each place with its icon, the chosen
 * one lit white at 14% with no dot, the rows the Pocket's smaller ones.
 */
class SideNavView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean
) : LinearLayout(context) {
    data class Item(val id: String, val label: String, val icon: AppIcon? = null)

    var onPick: ((String) -> Unit)? = null
    var selected: String? = null
        private set
    private val rows = mutableListOf<TextView>()
    private var items = emptyList<Item>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var blobTop = -1f
    private var animator: ValueAnimator? = null
    private val rowDp = ROW_DP
    private val cornerDp = 10

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
                textSize = 12f
                textWeight(600)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(10), 0)
                item.icon?.let { icon ->
                    compoundDrawablePadding = dp(10)
                    setCompoundDrawables(AppIconDrawable(icon, ICON).apply { setBounds(0, 0, dp(15), dp(15)) }, null, null, null)
                }
                contentDescription = item.label
                Styler.makeFocusable(this)
                setOnFocusChangeListener { _, focused ->
                    this@SideNavView.invalidate()
                    if (focused && item.id != selected) pick(item.id)
                }
                activateOnTap { pick(item.id) }
            }
            rows += row
            addView(row, LayoutParams(MATCH, dp(rowDp)).apply { bottomMargin = dp(GAP_DP) })
        }
        select(chosen, animate = false)
    }

    fun focus(id: String? = selected): Boolean = rows.getOrNull(items.indexOfFirst { it.id == id })?.requestFocus() == true

    fun select(id: String, animate: Boolean = true) {
        val index = items.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        selected = id
        rows.forEachIndexed { i, row ->
            row.setTextColor(if (i == index) colors.primaryText else QUIET)
            row.isSelected = i == index
        }
        val target = index * (dp(rowDp) + dp(GAP_DP)).toFloat()
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
            val h = dp(rowDp).toFloat()
            paint.style = Paint.Style.FILL
            paint.color = LIT
            rect.set(0f, blobTop, width.toFloat(), blobTop + h)
            canvas.drawRoundRect(rect, dp(cornerDp).toFloat(), dp(cornerDp).toFloat(), paint)
        }
        super.dispatchDraw(canvas)
        rows.firstOrNull { it.isFocused && ringVisible() }?.let { row ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = colors.focusRing
            rect.set(row.left + 1f, row.top + 1f, row.right - 1f, row.bottom - 1f)
            canvas.drawRoundRect(rect, dp(cornerDp).toFloat(), dp(cornerDp).toFloat(), paint)
        }
    }

    private fun dp(value: Int) = Styler.dpInt(context, value.toFloat())

    private companion object {
        const val MATCH = LayoutParams.MATCH_PARENT
        /** The prototype's Pocket rows, 12sp in 7dp of padding. */
        const val ROW_DP = 32
        const val GAP_DP = 4
        /** The chosen place white at 14%, the others' words at 74% and icons at 85%. */
        const val LIT = 0x24FFFFFF
        const val QUIET = 0xBDFFFFFF.toInt()
        const val ICON = 0xD9FFFFFF.toInt()
    }
}

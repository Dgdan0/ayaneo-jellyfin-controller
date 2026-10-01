package com.pocketds.hub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * Pick one of a few: tabs, seasons, a skip distance, Media or Books.
 *
 * The chosen option grows a little and a pill slides over to it, overshooting
 * slightly before it settles, so a change reads as one object moving rather than
 * two labels swapping colour. Every pick-one row in the app is this view, which
 * is the point of having it: the gamepad moves through the options, A or a tap
 * picks, and [followFocus] makes focus alone pick (detail-page tabs).
 *
 * The options are real focusable children, so the hint bar, focus search and
 * accessibility treat each one as a button.
 */
class BlobSegmentedView(
    context: Context,
    private val colors: PocketColors,
    private val ringVisible: () -> Boolean,
    private val style: Style = Style.PILL
) : ViewGroup(context) {

    data class Option(val id: String, val label: String, val description: String = label, val enabled: Boolean = true)

    enum class Style {
        /** A quiet track with a light blob and dark text on it: top tabs, chips. */
        PILL,
        /** The blob is the accent: a setting's value, Media or Books. */
        ACCENT,
        /** No track; an accent bar slides under the chosen label: detail-page tabs. */
        UNDERLINE
    }

    var heightDp = 34f
    var textSp = 12f
    var padXDp = 12f
    var growDp = 10f
    var trackPadDp = 3f
    /** Picks the option as soon as focus lands on it. */
    var followFocus = false
    var onPick: ((String) -> Unit)? = null
    var onOptionFocused: ((String) -> Unit)? = null
    /** A fill for [Style.ACCENT] other than the screen's accent, e.g. the other media type's colour. */
    var accentOverride: Int? = null
    var inkOverride: Int? = null

    var selected: String? = null
        private set
    private var options = emptyList<Option>()
    private val labels = mutableListOf<TextView>()
    private var from = -1
    private var to = -1
    private var progress = 1f
    private var animator: ValueAnimator? = null
    private var spans = emptyList<SegmentGeometry.Span>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
    }

    fun setOptions(options: List<Option>, selected: String?) {
        animator?.cancel()
        val focusedId = labels.indexOfFirst { it.isFocused }.takeIf { it >= 0 }?.let { this.options.getOrNull(it)?.id }
        removeAllViews()
        labels.clear()
        this.options = options
        options.forEach { option -> labels += label(option).also(::addView) }
        this.selected = selected?.takeIf { id -> options.any { it.id == id } }
        to = options.indexOfFirst { it.id == this.selected }
        from = -1
        progress = 1f
        paintLabels()
        requestLayout()
        invalidate()
        focusedId?.let { id -> optionView(id)?.requestFocus() }
    }

    /** Moves the blob. Does not call [onPick]: this is how a screen reflects a change made elsewhere. */
    fun select(id: String?, animate: Boolean = true) {
        val next = options.indexOfFirst { it.id == id }
        if (id == selected && next == to) return
        selected = id?.takeIf { next >= 0 }
        animator?.cancel()
        from = to
        to = next
        if (!animate || from < 0 || to < 0 || !ValueAnimator.areAnimatorsEnabled() || !isLaidOut) {
            progress = 1f
            paintLabels()
            requestLayout()
            invalidate()
            return
        }
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            // The CSS curve the prototype used: cubic-bezier(0.3, 1.35, 0.5, 1).
            interpolator = PathInterpolator(0.3f, 1.35f, 0.5f, 1f)
            addUpdateListener {
                progress = it.animatedValue as Float
                paintLabels()
                requestLayout()
                invalidate()
            }
            start()
        }
    }

    /** Changes one option's words in place: "Season 1" becomes "Season 1 · 13 episodes" once known. */
    fun relabel(id: String, label: String) {
        val index = options.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        if (options[index].label == label) return
        options = options.toMutableList().also { it[index] = it[index].copy(label = label) }
        labels[index].text = label
        paintLabels()
        requestLayout()
    }

    fun optionView(id: String): View? = options.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(labels::get)
    fun focus(id: String? = selected): Boolean =
        (id?.let(::optionView) ?: labels.firstOrNull { it.isFocusable })?.requestFocus() == true
    val focusedId: String? get() = labels.indexOfFirst { it.isFocused }.takeIf { it >= 0 }?.let { options[it].id }
    val optionIds: List<String> get() = options.map { it.id }

    private fun label(option: Option) = TextView(context).apply {
        text = option.label
        textSize = textSp
        textWeight(600)
        gravity = Gravity.CENTER
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        contentDescription = option.description
        val pad = dp(padXDp)
        setPadding(pad, 0, pad, 0)
        alpha = if (option.enabled) 1f else 0.4f
        Styler.makeFocusable(this)
        setOnFocusChangeListener { _, focused ->
            this@BlobSegmentedView.invalidate()
            if (focused) {
                onOptionFocused?.invoke(option.id)
                if (followFocus && option.enabled && option.id != selected) pick(option)
            }
        }
        activateOnTap { if (option.enabled) pick(option) }
    }

    private fun pick(option: Option) {
        select(option.id)
        onPick?.invoke(option.id)
    }

    private fun weightOf(index: Int): Float = when (index) {
        to -> progress.coerceIn(0f, 1f)
        from -> (1f - progress).coerceIn(0f, 1f)
        else -> 0f
    }

    private fun onInk(): Int = when (style) {
        Style.PILL -> colors.background
        Style.ACCENT -> inkOverride ?: colors.accentText
        Style.UNDERLINE -> colors.primaryText
    }

    private fun offInk(): Int = when (style) {
        Style.UNDERLINE -> colors.mutedText
        else -> ColorUtils.blendARGB(colors.mutedText, colors.primaryText, 0.45f)
    }

    private fun paintLabels() {
        val on = onInk()
        val off = offInk()
        labels.forEachIndexed { index, view ->
            val weight = weightOf(index)
            view.setTextColor(if (weight <= 0f) off else if (weight >= 1f) on else ColorUtils.blendARGB(off, on, weight))
            view.isSelected = index == to
            view.contentDescription = options[index].description + if (index == to) ", selected" else ""
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = dp(heightDp)
        val inner = height - 2 * dp(trackPadDp)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val natural = labels.map { view ->
            view.measure(unspecified, MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY))
            view.measuredWidth.toFloat()
        }
        spans = SegmentGeometry.spans(natural, SegmentGeometry.growth(labels.size, from, to, progress),
            dp(growDp).toFloat(), dp(trackPadDp).toFloat())
        labels.forEachIndexed { index, view ->
            view.measure(MeasureSpec.makeMeasureSpec(spans[index].width.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY))
        }
        val total = SegmentGeometry.total(spans, dp(trackPadDp).toFloat()).toInt()
        setMeasuredDimension(resolveSize(total, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val pad = dp(trackPadDp)
        labels.forEachIndexed { index, view ->
            val left = spans.getOrNull(index)?.left?.toInt() ?: return@forEachIndexed
            view.layout(left, pad, left + view.measuredWidth, pad + view.measuredHeight)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        val h = height.toFloat()
        val pad = dp(trackPadDp).toFloat()
        val trackRight = SegmentGeometry.total(spans, pad).coerceAtMost(width.toFloat())
        if (style != Style.UNDERLINE) {
            paint.style = Paint.Style.FILL
            paint.color = ColorUtils.setAlphaComponent(colors.stripBackground, 0xE0)
            rect.set(0f, 0f, trackRight, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f).toFloat()
            paint.color = ColorUtils.setAlphaComponent(colors.primaryText, 0x10)
            rect.inset(0.5f, 0.5f)
            canvas.drawRoundRect(rect, h / 2, h / 2, paint)
        }
        val focused = labels.indexOfFirst { it.isFocused }.takeIf { it >= 0 && ringVisible() }
        SegmentGeometry.blob(spans, from, to, progress)?.let { blob ->
            paint.style = Paint.Style.FILL
            when (style) {
                Style.UNDERLINE -> {
                    val inset = dp(padXDp).toFloat()
                    paint.color = accentOverride ?: colors.accent
                    rect.set(blob.left + inset, h - dp(3f), blob.right - inset, h)
                    canvas.drawRoundRect(rect, dp(1.5f).toFloat(), dp(1.5f).toFloat(), paint)
                }
                else -> {
                    paint.color = if (style == Style.PILL) colors.primaryText else accentOverride ?: colors.accent
                    rect.set(blob.left, pad, blob.right, h - pad)
                    // A ring around the chosen option needs a gap, or it merges into the blob.
                    if (focused == to) rect.inset(dp(2.5f).toFloat(), dp(2.5f).toFloat())
                    canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
                }
            }
        }
        super.dispatchDraw(canvas)
        focused?.let { index ->
            val view = labels[index]
            val stroke = dp(2f).toFloat()
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = colors.focusRing
            rect.set(view.left + stroke / 2, view.top + stroke / 2, view.right - stroke / 2, view.bottom - stroke / 2)
            val radius = if (style == Style.UNDERLINE) dp(8f).toFloat() else rect.height() / 2
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        progress = 1f
        paintLabels()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Float) = Styler.dpInt(context, value)

    private companion object {
        const val DURATION_MS = 380L
    }
}

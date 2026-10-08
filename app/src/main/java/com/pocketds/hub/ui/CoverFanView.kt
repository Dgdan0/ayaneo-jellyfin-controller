package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader
import com.pocketds.hub.screens.library.SeriesFan

/**
 * A series' books fanned out, the one fan of the app (#54): the Books library's Series view, Books Home's "Your
 * series" and the top of a series page. It only draws a [SeriesFan.Plan]: the first book in front on the left and the
 * others fanning right, symmetrical slots, the book you are on lit in its slot (a gold edge, bigger, raised, on top),
 * the others layered and darkened by their distance from it, the books you do not have dimmed. What stands where is
 * the plan's.
 *
 * A fan has [slots] slots (five in the Series view, three where the box is smaller) in a box [boxDp] across, and its
 * covers lean out of the box a little. Focus opens it ([spread]) as far as [roomDp] allows.
 */
class CoverFanView(
    context: Context,
    private val colors: PocketColors,
    private val coverWidthDp: Int,
    slots: Int = SeriesFan.SLOTS,
    boxWidthDp: Int? = null
) : FrameLayout(context) {

    val covers = List(slots) { ImageView(context) }
    /** The cover in front, which carries the focus ring where the fan is focusable. */
    val front: ImageView get() = plan?.let { covers[it.slots.indexOfFirst { slot -> slot.front }.coerceAtLeast(0)] } ?: covers[0]
    private val scale = coverWidthDp / BASE_COVER_DP
    /** The room the fan asks its parent for, width then height in dp. */
    val boxDp: Pair<Int, Int> = planSizeDp(coverWidthDp, slots).let { (boxWidthDp ?: it.first) to it.second }
    private var spread = false
    /** The plan the fan stands to. */
    private var plan: SeriesFan.Plan? = null
    /** The ring a focused fan puts on its front cover, over whatever else that cover carries. */
    private var ring: Drawable? = null
    /**
     * How far from the fan's middle its covers may reach when it opens with focus, in dp: the card's half width and a
     * small margin, set by the card once it has a width. Unbounded until then.
     */
    var roomDp: Float = Float.POSITIVE_INFINITY
        set(value) {
            if (field == value) return
            field = value
            placePlan()
        }

    init {
        clipChildren = false
        clipToPadding = false
        covers.forEach { cover ->
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.background = ThemeGradientDrawable.rounded(Styler.dp(context, CORNER_DP * scale), colors.posterPlaceholder)
            cover.clipToOutline = true
            cover.visibility = View.GONE
            addView(cover, LayoutParams(Styler.dpInt(context, coverWidthDp.toFloat()), Styler.dpInt(context, coverWidthDp * 1.5f)))
        }
    }

    /** Stand the fan to [plan]: each slot's book's cover. */
    fun bindPlan(plan: SeriesFan.Plan, loader: ImageLoader, imageUrl: (String) -> String) {
        this.plan = plan
        covers.forEachIndexed { i, view ->
            val slot = plan.slots.getOrNull(i)
            view.visibility = if (slot != null) View.VISIBLE else View.GONE
            if (slot != null) Artwork.bind(view, loader, slot.book.cover.takeIf(String::isNotBlank)?.let(imageUrl), opaque = true)
        }
        placePlan()
    }

    /** The fan opens a little while it has focus. */
    fun spread(open: Boolean) {
        if (spread == open) return
        spread = open
        placePlan()
    }

    /** The ring a focused fan puts on its front cover (null takes it off). */
    fun setRing(drawable: Drawable?) {
        ring = drawable
        placePlan()
    }

    /**
     * The plan's slots: where each stands across the fan (its offset from the middle, opened a little with focus),
     * how it leans, how high it is raised and how large, which is in front of which, how dark and how dim.
     */
    private fun placePlan() {
        val plan = plan ?: return
        val opening = SeriesFan.opening(spread, plan, coverWidthDp.toFloat(), roomDp)
        val (widthDp, heightDp) = boxDp
        val centre = Styler.dp(context, widthDp / 2f)
        val bottom = Styler.dp(context, heightDp - FOOT_DP)
        plan.slots.forEachIndexed { i, slot ->
            val cover = covers[i]
            val widthPx = Styler.dp(context, coverWidthDp.toFloat())
            val heightPx = Styler.dp(context, SeriesFan.coverHeightDp(coverWidthDp.toFloat(), slot.square))
            (cover.layoutParams as LayoutParams).let {
                it.width = widthPx.toInt(); it.height = heightPx.toInt()
                it.leftMargin = (centre + slot.offset * SeriesFan.STEP_FRACTION * widthPx * opening - widthPx / 2).toInt()
                it.topMargin = (bottom - heightPx).toInt()
                cover.layoutParams = it
            }
            // About its own foot, so a square one's lean starts where a tall one's does.
            cover.pivotX = widthPx / 2f
            cover.pivotY = heightPx
            cover.rotation = slot.angleDeg * opening
            cover.scaleX = if (slot.lit) SeriesFan.LIT_SCALE else 1f
            cover.scaleY = cover.scaleX
            cover.translationY = if (slot.lit) -SeriesFan.LIT_RAISE * heightPx else 0f
            cover.elevation = Styler.dp(context, 2f + slot.layer * 2f)
            cover.alpha = if (slot.dimmed) DIMMED else 1f
            cover.foreground = planForeground(slot, plan)
        }
    }

    /** A cover's foreground over its picture: its shade, or the lit one's gold edge, the finished tick, the focus ring. */
    private fun planForeground(slot: SeriesFan.Slot, plan: SeriesFan.Plan): Drawable? {
        val layers = mutableListOf<Drawable>()
        if (slot.shade > 0f) layers += ColorDrawable(Color.argb((slot.shade * 255).toInt(), 0, 0, 0))
        if (slot.lit) layers += ThemeGradientDrawable.rounded(Styler.dp(context, CORNER_DP * scale), Color.TRANSPARENT, Styler.dpInt(context, 2f), colors.accent)
        if (slot.front && plan.finished) layers += FinishedTick.drawable(colors, resources.displayMetrics.density)
        if (slot.front) ring?.let { layers += it }
        return when (layers.size) {
            0 -> null
            1 -> layers[0]
            else -> LayerDrawable(layers.toTypedArray())
        }
    }

    companion object {
        private const val BASE_COVER_DP = 64f
        private const val CORNER_DP = 7f
        /** A book you do not have. */
        private const val DIMMED = 0.42f
        /** How far the covers stand off the bottom of the box, so the lean's lowest corner stays inside it. */
        private const val FOOT_DP = 4f

        /**
         * The box a fan of [slots] covers [coverWidthDp] across stands in, width then height, in dp: the slots at
         * rest, and the lit one raised, bigger and leaning over the tallest cover. The covers lean and open past its
         * sides; [roomDp] is how far they may.
         */
        fun planSizeDp(coverWidthDp: Int, slots: Int = SeriesFan.SLOTS): Pair<Int, Int> {
            val width = SeriesFan.widthDp(coverWidthDp.toFloat(), slots)
            val tallest = coverWidthDp * 1.5f * SeriesFan.LIT_SCALE
            val rise = coverWidthDp * 1.5f * SeriesFan.LIT_RAISE + SeriesFan.leanRiseDp(coverWidthDp.toFloat(), slots)
            return Math.ceil(width.toDouble()).toInt() to Math.ceil((tallest + rise + FOOT_DP).toDouble()).toInt()
        }

        /** The smaller fans' covers (Books Home's series, a series page's header): [SeriesFan.SMALL_SLOTS] of them fit [BOX_DP]. */
        const val COVER_DP = 56
        /** Their box across, as Books Home's fan always had. */
        const val BOX_DP = 130
        /** How far past that box the lit book of an outer slot leans: room to leave at a page's edge. */
        const val OVERHANG_DP = 4
    }
}

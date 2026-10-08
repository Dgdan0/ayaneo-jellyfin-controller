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
 * A series' covers fanned out: a series at a glance, on Books Home, at the top of a series page and, in the
 * Books library's Series view, for every series. Covers that are missing still show as cards, so a series of one
 * book still reads as a series.
 *
 * Two ways to stand them, one view. [bind] is the prototype's fan (`.fan`): up to four covers leaning from left
 * to right about their feet, the book you are on last and on top at the right, and the outer two leaning further
 * while the fan has focus ([spread]). [bindPlan] is the Series view's (#54): a [SeriesFan.Plan] of up to five
 * slots, symmetrical about the middle, with the book you are on lit in its slot (a gold edge, bigger, raised, on
 * top) and the others layered and darkened by their distance from it, the books you do not have dimmed; what
 * stands where is the plan's, and this only draws it.
 */
class CoverFanView(context: Context, private val colors: PocketColors, private val coverWidthDp: Int) : FrameLayout(context) {

    val covers = List(MAX) { ImageView(context) }
    /** The cover in front, which carries the focus ring where the fan is focusable. */
    val front: ImageView get() = plan?.let { covers[it.slots.indexOfFirst { slot -> slot.front }.coerceAtLeast(0)] } ?: covers[0]
    private val scale = coverWidthDp / BASE_COVER_DP
    /** How many covers stand in the fan, and so which slot each takes. */
    private var shown = 0
    private var spread = false
    /** The plan the Series view's fan stands to, or null for the prototype's. */
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
            if (plan != null) placePlan()
        }

    init {
        clipChildren = false
        clipToPadding = false
        val width = Styler.dpInt(context, coverWidthDp.toFloat())
        val height = Styler.dpInt(context, coverWidthDp * 1.5f)
        covers.forEach { cover ->
            cover.scaleType = ImageView.ScaleType.CENTER_CROP
            cover.background = ThemeGradientDrawable.rounded(Styler.dp(context, CORNER_DP * scale), colors.posterPlaceholder)
            cover.clipToOutline = true
            cover.visibility = View.GONE
            // They lean about their feet, as the prototype's do.
            cover.pivotX = width / 2f
            cover.pivotY = height.toFloat()
            addView(cover, LayoutParams(width, height).apply {
                topMargin = Styler.dpInt(context, (HEIGHT_DP - FOOT_DP) * scale) - height
            })
        }
    }

    fun bind(paths: List<String>, loader: ImageLoader, imageUrl: (String) -> String) {
        plan = null
        // Even a series with no artwork shows one card.
        shown = paths.size.coerceIn(1, COUNT)
        covers.forEachIndexed { i, view ->
            view.visibility = if (i < shown) View.VISIBLE else View.GONE
            if (i < shown) Artwork.bind(view, loader, paths.getOrNull(i)?.let(imageUrl), opaque = true)
        }
        place()
    }

    /** The Series view's fan: [plan]'s slots, each with its book's cover. */
    fun bindPlan(plan: SeriesFan.Plan, loader: ImageLoader, imageUrl: (String) -> String) {
        this.plan = plan
        covers.forEachIndexed { i, view ->
            val slot = plan.slots.getOrNull(i)
            view.visibility = if (slot != null) View.VISIBLE else View.GONE
            if (slot != null) Artwork.bind(view, loader, slot.book.cover.takeIf(String::isNotBlank)?.let(imageUrl), opaque = true)
        }
        placePlan()
    }

    /** The outer covers lean further while the fan has focus. */
    fun spread(open: Boolean) {
        if (spread == open) return
        spread = open
        if (plan != null) placePlan() else place()
    }

    /** The ring a focused fan puts on its front cover (null takes it off). */
    fun setRing(drawable: Drawable?) {
        ring = drawable
        if (plan != null) placePlan() else front.foreground = drawable
    }

    /**
     * The book you are on takes the last slot, on top at the right;
     * the others fill the slots before it in order. A fan of two leans like
     * the first two of four, as the prototype's does. A fan of one known cover
     * is not a fan: it stands upright in the middle of its box, never at the
     * first slot's angle.
     */
    private fun place() {
        covers.forEach { resetPlanLook(it) }
        val width = Styler.dpInt(context, coverWidthDp.toFloat())
        val height = Styler.dpInt(context, coverWidthDp * 1.5f)
        if (shown == 1) {
            val (boxWidth, boxHeight) = sizeDp(coverWidthDp)
            val (left, top) = SeriesFan.centred(boxWidth.toFloat(), boxHeight.toFloat(), coverWidthDp.toFloat())
            stand(covers[0], width, height, Styler.dpInt(context, left), Styler.dpInt(context, top))
            covers[0].rotation = 0f
            covers[0].elevation = Styler.dp(context, 6f)
            return
        }
        for (i in 0 until shown) {
            val slot = if (i == 0) shown - 1 else i - 1
            val cover = covers[i]
            // On its foot, whatever stood here before (a lone cover is centred, the Series view's slots are its own).
            stand(cover, width, height, Styler.dpInt(context, LEFT_DP[slot] * scale), Styler.dpInt(context, (HEIGHT_DP - FOOT_DP) * scale) - height)
            cover.rotation = ANGLES[slot] + when {
                !spread || shown < COUNT -> 0f
                slot == 0 -> -SPREAD
                slot == COUNT - 1 -> SPREAD
                else -> 0f
            }
            cover.elevation = Styler.dp(context, 6f + slot * 2f)
        }
    }

    private fun stand(cover: ImageView, width: Int, height: Int, left: Int, top: Int) {
        val params = cover.layoutParams as LayoutParams
        if (params.width == width && params.height == height && params.leftMargin == left && params.topMargin == top) return
        params.width = width; params.height = height; params.leftMargin = left; params.topMargin = top
        cover.layoutParams = params
    }

    /** What a plan changes of a cover, undone for the prototype's fan. */
    private fun resetPlanLook(cover: ImageView) {
        cover.scaleX = 1f; cover.scaleY = 1f; cover.translationY = 0f; cover.alpha = 1f
    }

    /**
     * The plan's slots: where each stands across the fan (its offset from the middle, opened a little with focus),
     * how it leans, how high it is raised and how large, which is in front of which, how dark and how dim.
     */
    private fun placePlan() {
        val plan = plan ?: return
        val opening = SeriesFan.opening(spread, plan, coverWidthDp.toFloat(), roomDp)
        val (widthDp, heightDp) = planSizeDp(coverWidthDp)
        val centre = Styler.dp(context, widthDp / 2f)
        val bottom = Styler.dp(context, heightDp - PLAN_FOOT_DP)
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
        /** The prototype's Pocket fan: 64dp covers in a 130 x 108dp box, 4dp off its foot. */
        private const val COUNT = 4
        /** The most covers any fan holds: the Series view's five. */
        private const val MAX = SeriesFan.SLOTS
        private const val BASE_COVER_DP = 64f
        private const val WIDTH_DP = 130f
        private const val HEIGHT_DP = 108f
        private const val FOOT_DP = 4f
        private const val CORNER_DP = 7f
        private val LEFT_DP = floatArrayOf(0f, 22f, 44f, 64f)
        private val ANGLES = floatArrayOf(-13f, -5f, 4f, 12f)
        /** How much further the outer two lean with focus: -13 to -18, 12 to 17. */
        private const val SPREAD = 5f
        /** A book you do not have, in the Series view's fan. */
        private const val DIMMED = 0.42f
        private const val PLAN_FOOT_DP = 4f

        /** The room a fan of [coverWidthDp] covers needs, width then height, in dp. */
        fun sizeDp(coverWidthDp: Int): Pair<Int, Int> =
            (coverWidthDp * WIDTH_DP / BASE_COVER_DP).toInt() to (coverWidthDp * HEIGHT_DP / BASE_COVER_DP).toInt()

        /**
         * The box the Series view's fan of covers [coverWidthDp] across stands in, width then height, in dp: five
         * slots at rest, and the lit one raised, bigger and leaning over the tallest cover. The covers lean and open
         * past its sides; [roomDp] is how far they may.
         */
        fun planSizeDp(coverWidthDp: Int): Pair<Int, Int> {
            val width = SeriesFan.widthDp(coverWidthDp.toFloat(), SeriesFan.SLOTS)
            val tallest = coverWidthDp * 1.5f * SeriesFan.LIT_SCALE
            val rise = coverWidthDp * 1.5f * SeriesFan.LIT_RAISE + SeriesFan.leanRiseDp(coverWidthDp.toFloat())
            return Math.ceil(width.toDouble()).toInt() to Math.ceil((tallest + rise + PLAN_FOOT_DP).toDouble()).toInt()
        }

        /** A fan's covers: the prototype's 64dp on the Pocket. */
        const val COVER_DP = 64
        /** How far past its box the outer cover of a fan leans: room to leave at a page's edge. */
        const val LEAN_DP = 8
    }
}

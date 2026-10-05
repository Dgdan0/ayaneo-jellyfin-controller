package com.pocketds.hub.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader

/**
 * A series' covers fanned out: a series at a glance, on Books Home and at the
 * top of a series page. Covers that are missing still show as cards, so a
 * series of one book still reads as a series.
 *
 * It is the prototype's fan (`.fan`): up to four covers leaning from left to
 * right about their feet, the book you are on last and on top at the right,
 * and the outer two leaning further while the fan has focus ([spread]).
 */
class CoverFanView(context: Context, colors: PocketColors, coverWidthDp: Int) : FrameLayout(context) {

    val covers = List(COUNT) { ImageView(context) }
    /** The cover in front, which carries the focus ring where the fan is focusable. */
    val front: ImageView get() = covers[0]
    private val scale = coverWidthDp / BASE_COVER_DP
    /** How many covers stand in the fan, and so which slot each takes. */
    private var shown = 0
    private var spread = false

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
        // Even a series with no artwork shows one card.
        shown = paths.size.coerceIn(1, COUNT)
        covers.forEachIndexed { i, view ->
            view.visibility = if (i < shown) View.VISIBLE else View.GONE
            if (i < shown) Artwork.bind(view, loader, paths.getOrNull(i)?.let(imageUrl), opaque = true)
        }
        place()
    }

    /** The outer covers lean further while the fan has focus. */
    fun spread(open: Boolean) {
        if (spread == open) return
        spread = open
        place()
    }

    /**
     * The book you are on takes the last slot, on top at the right;
     * the others fill the slots before it in order. A fan of two leans like
     * the first two of four, as the prototype's does.
     */
    private fun place() {
        for (i in 0 until shown) {
            val slot = if (i == 0) shown - 1 else i - 1
            val cover = covers[i]
            (cover.layoutParams as LayoutParams).let {
                val left = Styler.dpInt(context, LEFT_DP[slot] * scale)
                if (it.leftMargin != left) { it.leftMargin = left; cover.layoutParams = it }
            }
            cover.rotation = ANGLES[slot] + when {
                !spread || shown < COUNT -> 0f
                slot == 0 -> -SPREAD
                slot == COUNT - 1 -> SPREAD
                else -> 0f
            }
            cover.elevation = Styler.dp(context, 6f + slot * 2f)
        }
    }

    companion object {
        /** The prototype's Pocket fan: 64dp covers in a 130 x 108dp box, 4dp off its foot. */
        private const val COUNT = 4
        private const val BASE_COVER_DP = 64f
        private const val WIDTH_DP = 130f
        private const val HEIGHT_DP = 108f
        private const val FOOT_DP = 4f
        private const val CORNER_DP = 7f
        private val LEFT_DP = floatArrayOf(0f, 22f, 44f, 64f)
        private val ANGLES = floatArrayOf(-13f, -5f, 4f, 12f)
        /** How much further the outer two lean with focus: -13 to -18, 12 to 17. */
        private const val SPREAD = 5f

        /** The room a fan of [coverWidthDp] covers needs, width then height, in dp. */
        fun sizeDp(coverWidthDp: Int): Pair<Int, Int> =
            (coverWidthDp * WIDTH_DP / BASE_COVER_DP).toInt() to (coverWidthDp * HEIGHT_DP / BASE_COVER_DP).toInt()

        /** A fan's covers: the prototype's 64dp on the Pocket. */
        const val COVER_DP = 64
        /** How far past its box the outer cover of a fan leans: room to leave at a page's edge. */
        const val LEAN_DP = 8
    }
}

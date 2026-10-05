package com.pocketds.hub.ui

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader

/**
 * Up to three covers fanned out, the first in front: a series at a glance, on
 * Books Home and at the top of a series page. Covers that are missing still
 * show as cards, so a series of one book still reads as a series.
 *
 * [glass] is the prototype's fan (`.fan`): up to four covers leaning from left
 * to right about their feet, the book you are on last and on top at the right,
 * and the outer two leaning further while the fan has focus ([spread]).
 */
class CoverFanView(context: Context, colors: PocketColors, coverWidthDp: Int, private val glass: Boolean = false) : FrameLayout(context) {

    val covers = List(if (glass) GLASS_COUNT else 3) { ImageView(context) }
    /** The cover in front, which carries the focus ring where the fan is focusable. */
    val front: ImageView get() = covers[0]
    private val scale = coverWidthDp / if (glass) GLASS_COVER_DP else BASE_COVER_DP
    /** Glass: how many covers stand in the fan, and so which slot each takes. */
    private var shown = 0
    private var spread = false

    init {
        clipChildren = false
        clipToPadding = false
        if (glass) {
            val width = Styler.dpInt(context, coverWidthDp.toFloat())
            val height = Styler.dpInt(context, coverWidthDp * 1.5f)
            covers.forEach { cover ->
                cover.scaleType = ImageView.ScaleType.CENTER_CROP
                cover.background = ThemeGradientDrawable.rounded(Styler.dp(context, GLASS_CORNER_DP * scale), colors.posterPlaceholder)
                cover.clipToOutline = true
                cover.visibility = View.GONE
                // They lean about their feet, as the prototype's do.
                cover.pivotX = width / 2f
                cover.pivotY = height.toFloat()
                addView(cover, LayoutParams(width, height).apply {
                    topMargin = Styler.dpInt(context, (GLASS_HEIGHT_DP - GLASS_FOOT_DP) * scale) - height
                })
            }
        } else {
            // Back to front: the third book, the second, then the one you are on.
            val placement = listOf(Triple(44f, 4f, 8f), Triple(22f, 2f, -3f), Triple(4f, 6f, -9f))
            placement.forEachIndexed { i, (left, top, angle) ->
                val cover = covers[2 - i].apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ThemeGradientDrawable.rounded(Styler.dp(context, 6f * scale), colors.posterPlaceholder)
                    clipToOutline = true
                    rotation = angle
                    elevation = Styler.dp(context, 6f + i * 2f)
                }
                addView(cover, LayoutParams(Styler.dpInt(context, coverWidthDp.toFloat()), Styler.dpInt(context, coverWidthDp * 1.5f)).apply {
                    leftMargin = Styler.dpInt(context, left * scale)
                    topMargin = Styler.dpInt(context, top * scale)
                })
            }
        }
    }

    fun bind(paths: List<String>, loader: ImageLoader, imageUrl: (String) -> String) {
        if (!glass) {
            covers.forEachIndexed { i, view -> Artwork.bind(view, loader, paths.getOrNull(i)?.let(imageUrl), opaque = true) }
            return
        }
        // Even a series with no artwork shows one card.
        shown = paths.size.coerceIn(1, GLASS_COUNT)
        covers.forEachIndexed { i, view ->
            view.visibility = if (i < shown) View.VISIBLE else View.GONE
            if (i < shown) Artwork.bind(view, loader, paths.getOrNull(i)?.let(imageUrl), opaque = true)
        }
        place()
    }

    /** Glass: the outer covers lean further while the fan has focus. */
    fun spread(open: Boolean) {
        if (!glass || spread == open) return
        spread = open
        place()
    }

    /**
     * Glass: the book you are on takes the last slot, on top at the right;
     * the others fill the slots before it in order. A fan of two leans like
     * the first two of four, as the prototype's does.
     */
    private fun place() {
        for (i in 0 until shown) {
            val slot = if (i == 0) shown - 1 else i - 1
            val cover = covers[i]
            (cover.layoutParams as LayoutParams).let {
                val left = Styler.dpInt(context, GLASS_LEFT_DP[slot] * scale)
                if (it.leftMargin != left) { it.leftMargin = left; cover.layoutParams = it }
            }
            cover.rotation = GLASS_ANGLES[slot] + when {
                !spread || shown < GLASS_COUNT -> 0f
                slot == 0 -> -GLASS_SPREAD
                slot == GLASS_COUNT - 1 -> GLASS_SPREAD
                else -> 0f
            }
            cover.elevation = Styler.dp(context, 6f + slot * 2f)
        }
    }

    companion object {
        private const val BASE_COVER_DP = 78f
        /** The prototype's Pocket fan: 64dp covers in a 130 x 108dp box, 4dp off its foot. */
        private const val GLASS_COUNT = 4
        private const val GLASS_COVER_DP = 64f
        private const val GLASS_WIDTH_DP = 130f
        private const val GLASS_HEIGHT_DP = 108f
        private const val GLASS_FOOT_DP = 4f
        private const val GLASS_CORNER_DP = 7f
        private val GLASS_LEFT_DP = floatArrayOf(0f, 22f, 44f, 64f)
        private val GLASS_ANGLES = floatArrayOf(-13f, -5f, 4f, 12f)
        /** How much further the outer two lean with focus: -13 to -18, 12 to 17. */
        private const val GLASS_SPREAD = 5f

        /** The room a fan of [coverWidthDp] covers needs, width then height, in dp. */
        fun sizeDp(coverWidthDp: Int, glass: Boolean = false): Pair<Int, Int> = if (glass) {
            (coverWidthDp * GLASS_WIDTH_DP / GLASS_COVER_DP).toInt() to (coverWidthDp * GLASS_HEIGHT_DP / GLASS_COVER_DP).toInt()
        } else (coverWidthDp * 150f / BASE_COVER_DP).toInt() to (coverWidthDp * 128f / BASE_COVER_DP).toInt()

        /** A Glass fan's covers: the prototype's 64dp on the Pocket. */
        const val GLASS_COVER = 64
        /** How far past its box the outer cover of a Glass fan leans: room to leave at a page's edge. */
        const val GLASS_LEAN_DP = 8
    }
}

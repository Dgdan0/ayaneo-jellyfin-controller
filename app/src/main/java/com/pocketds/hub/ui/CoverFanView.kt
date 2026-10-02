package com.pocketds.hub.ui

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import coil.ImageLoader

/**
 * Up to three covers fanned out, the first in front: a series at a glance, on
 * Books Home and at the top of a series page. Covers that are missing still
 * show as cards, so a series of one book still reads as a series.
 */
class CoverFanView(context: Context, colors: PocketColors, coverWidthDp: Int) : FrameLayout(context) {

    val covers = List(3) { ImageView(context) }
    /** The cover in front, which carries the focus ring where the fan is focusable. */
    val front: ImageView get() = covers[0]

    init {
        clipChildren = false
        clipToPadding = false
        val scale = coverWidthDp / BASE_COVER_DP
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

    fun bind(paths: List<String>, loader: ImageLoader, imageUrl: (String) -> String) {
        covers.forEachIndexed { i, view -> Artwork.bind(view, loader, paths.getOrNull(i)?.let(imageUrl), opaque = true) }
    }

    companion object {
        private const val BASE_COVER_DP = 78f

        /** The room a fan of [coverWidthDp] covers needs, width then height, in dp. */
        fun sizeDp(coverWidthDp: Int): Pair<Int, Int> =
            (coverWidthDp * 150f / BASE_COVER_DP).toInt() to (coverWidthDp * 128f / BASE_COVER_DP).toInt()
    }
}

package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.pocketds.hub.screens.library.ReadingBookFacts.CoverMark

/**
 * The small round mark on the corner of a book's cover that is an ebook and an audiobook too (#54): a book with
 * sound once it is aligned for read along, headphones until then. An icon and no words: a cover already carries a
 * finished tick (top right) and a progress bar (along the foot), and a worded tag would sit on them, so this is
 * 18dp, in the top left corner, where nothing else of a book's cover is. A comic's kind pill (foot, left) is its own.
 *
 * It is a drawable laid over the cover picture ([drawable]): a poster card puts it on its picture, a book in a
 * series row in the foreground the cover already has for its marks. Which mark a book gets is
 * `ReadingBookFacts.formatMark`; this draws it.
 */
object FormatMark {
    const val SIZE_DP = 18f
    /** From the cover's corner, as the finished tick and the kind pill are. */
    const val EDGE_DP = 6f
    private const val GLYPH_DP = 10f
    /** Dark glass, as the comic's kind pill (black at 62%): it reads over any cover. */
    private const val FILL = 0x9E000000.toInt()

    fun icon(mark: CoverMark): AppIcon? = when (mark) {
        CoverMark.READ_ALONG -> AppIcon.READ_ALONG
        CoverMark.HEADPHONES -> AppIcon.HEADPHONES
        CoverMark.NONE -> null
    }

    /** What the mark says to a screen reader, to add to the book's own description; null for no mark. */
    fun words(mark: CoverMark): String? = when (mark) {
        CoverMark.READ_ALONG -> "read along"
        CoverMark.HEADPHONES -> "ebook and audiobook"
        CoverMark.NONE -> null
    }

    /** The mark to lay over a cover, drawn at its top left; null where there is none. */
    fun drawable(context: Context, mark: CoverMark): Drawable? =
        icon(mark)?.let { FormatMarkDrawable(context.resources.displayMetrics.density, it) }

    private class FormatMarkDrawable(private val density: Float, icon: AppIcon) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL }
        private val glyph = AppIconDrawable(icon, Color.WHITE)

        override fun draw(canvas: Canvas) {
            val size = SIZE_DP * density
            val cx = bounds.left + EDGE_DP * density + size / 2
            val cy = bounds.top + EDGE_DP * density + size / 2
            canvas.drawCircle(cx, cy, size / 2, paint)
            val half = GLYPH_DP * density / 2
            glyph.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            glyph.draw(canvas)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

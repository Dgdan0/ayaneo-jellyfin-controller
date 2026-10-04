package com.pocketds.hub.ui.glass

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.pocketds.hub.ui.ArtworkProgressView
import com.pocketds.hub.ui.Styler
import com.pocketds.hub.ui.ThemeGradientDrawable

/**
 * How far in, drawn inside a picture as the prototype draws it (`.pbar`): a
 * 4dp white bar on a faint track with round ends, hidden while there is
 * nothing to show. Stills ([GlassStillMarks]), posters and book covers carry
 * this one, so they cannot drift apart. [color] is the accent where the
 * prototype fills it so (a book being read, `.mini .b`).
 */
class GlassProgressBar(context: Context, color: Int = Color.WHITE, track: Int = TRACK) : FrameLayout(context) {
    private val fill = ArtworkProgressView(context, color)

    /** 0..1; nothing to show hides the whole bar, track and all. */
    var fraction: Double
        get() = fill.fraction
        set(value) {
            fill.fraction = value
            visibility = if (fill.fraction > 0.0) View.VISIBLE else View.GONE
        }

    init {
        background = ThemeGradientDrawable.rounded(Styler.dp(context, 2f), track)
        clipToOutline = true
        visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(fill, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    companion object {
        /** The track inside a picture: white at 28%. */
        const val TRACK = 0x47FFFFFF
        /** Its height, and how far in from a poster's edges it sits (6% of 82dp, as `.pbar`'s 6cqw). */
        const val HEIGHT_DP = 4f
        const val POSTER_INSET_DP = 6f
    }
}

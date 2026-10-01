package com.pocketds.hub.playback

import android.graphics.Color
import android.graphics.Typeface
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

/** Puts a [SubtitleLook] on a Media3 SubtitleView: the player's and the Settings preview's. */
@androidx.media3.common.util.UnstableApi
object SubtitleLooks {
    /**
     * Roboto Medium: mpv draws its outline round the system sans, and a regular
     * weight thins out under a 2dp black edge.
     */
    private val typeface: Typeface by lazy { Typeface.create("sans-serif-medium", Typeface.NORMAL) }

    fun apply(view: SubtitleView, look: SubtitleLook) {
        // Italics and colours written into the file survive; its sizes do not,
        // or the size choice would only work on plain SRT.
        view.setApplyEmbeddedStyles(true)
        view.setApplyEmbeddedFontSizes(false)
        view.setStyle(style(look.style))
        view.setFractionalTextSize(look.size.textFraction)
    }

    fun style(style: SubtitleStyle): CaptionStyleCompat = when (style) {
        SubtitleStyle.OUTLINE -> CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, typeface)
        SubtitleStyle.BOX -> CaptionStyleCompat(Color.WHITE, Color.argb(185, 0, 0, 0), Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_NONE, Color.TRANSPARENT, typeface)
    }
}

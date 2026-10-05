package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import kotlin.math.roundToInt

/**
 * A reader's [ScreenComfort], drawn (#16, X3). It lies over the whole reader,
 * its page and its controls, so the reader dims and warms as a backlight
 * would: the warmth multiplied into what is under it, which turns white amber
 * and leaves black black on the OLED, then the dim as black laid over.
 *
 * Touches, focus and accessibility pass through it. It is never translucent
 * itself: a view with its own alpha is drawn into a layer of its own, and a
 * multiply there would mix with nothing.
 */
class ComfortLayerView(context: Context) : View(context) {
    var comfort: ScreenComfort = ScreenComfort()
        private set

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        visibility = GONE
    }

    fun apply(value: ScreenComfort) {
        comfort = value
        visibility = if (value.drawsNothing) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (comfort.warmColor != ScreenComfort.WHITE) canvas.drawColor(comfort.warmColor, PorterDuff.Mode.MULTIPLY)
        val dim = (comfort.dimAlpha * 255).roundToInt()
        if (dim > 0) canvas.drawColor(Color.argb(dim, 0, 0, 0))
    }

    override fun hasOverlappingRendering() = false
}

package com.pocketds.hub.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable

/** Android exposes gradient fills but not stroke getters. Remember our stroke for theme rebinding. */
class ThemeGradientDrawable : GradientDrawable {
    constructor() : super()
    constructor(orientation: Orientation, colors: IntArray) : super(orientation, colors)
    private var strokeSize=0
    private var strokeTint:ColorStateList?=null
    override fun setStroke(width:Int,color:Int) { strokeSize=width;strokeTint=ColorStateList.valueOf(color);super.setStroke(width,color) }
    override fun setStroke(width:Int,color:ColorStateList?) { strokeSize=width;strokeTint=color;super.setStroke(width,color) }
    fun rebindStroke(transform:(ColorStateList?)->ColorStateList?) { strokeTint?.let { setStroke(strokeSize,transform(it)) } }

    /**
     * Shapes built from plain arguments. Inside `ThemeGradientDrawable().apply {}`
     * a bare `colors` is GradientDrawable's own gradient array, not the palette,
     * which has failed the build five times; these take the colours outside it.
     */
    companion object {
        fun rounded(radiusPx: Float, fill: Int, strokePx: Int = 0, stroke: Int = 0) = ThemeGradientDrawable().apply {
            cornerRadius = radiusPx; setColor(fill); if (strokePx > 0) setStroke(strokePx, stroke)
        }
        fun oval(fill: Int, strokePx: Int = 0, stroke: Int = 0) = ThemeGradientDrawable().apply {
            shape = OVAL; setColor(fill); if (strokePx > 0) setStroke(strokePx, stroke)
        }
    }
}

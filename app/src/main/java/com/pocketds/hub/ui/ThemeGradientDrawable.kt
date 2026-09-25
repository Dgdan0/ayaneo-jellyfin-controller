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
}

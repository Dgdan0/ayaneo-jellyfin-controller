package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** One measured row, shared density and text hierarchy for settings and other utility entries. */
class UtilityRowView(context:Context,colors:PocketColors,private val label:String,initialDetail:String,icon:AppIcon):LinearLayout(context) {
    private val leading=ImageView(context).apply {
        setImageDrawable(AppIconDrawable(icon,colors.mutedText))
        imageTintList=android.content.res.ColorStateList.valueOf(colors.mutedText)
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun setIconResource(resource:Int){leading.setImageResource(resource)}
    private val detailView=TextView(context).apply {textSize=12f;setTextColor(colors.mutedText)}
    var detail:String
        get()=detailView.text.toString()
        set(value){detailView.text=value;detailView.visibility=if(value.isBlank())GONE else VISIBLE;contentDescription="$label, $value"}
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56)
        setPadding(dp(12),dp(10),dp(12),dp(10));Styler.makeFocusable(this)
        background=Styler.cardBackground(context,colors,10f,Color.TRANSPARENT,2f)
        addView(leading,LayoutParams(dp(24),dp(24)).apply{marginEnd=dp(16)})
        addView(LinearLayout(context).apply {
            orientation=VERTICAL
            addView(TextView(context).apply{text=label;textSize=14f;setTextColor(colors.primaryText)})
            addView(detailView,LayoutParams(-1,-2).apply{topMargin=dp(3)})
        },LayoutParams(0,-2,1f))
        addView(ImageView(context).apply{setImageDrawable(AppIconDrawable(AppIcon.NEXT,colors.mutedText));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LayoutParams(dp(18),dp(18)).apply{marginStart=dp(10)})
        detail=initialDetail
    }
    private fun dp(value:Int)=Styler.dpInt(context,value.toFloat())
}

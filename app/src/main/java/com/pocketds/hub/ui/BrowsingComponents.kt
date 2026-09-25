package com.pocketds.hub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import com.pocketds.hub.model.SearchHit
import com.pocketds.hub.state.LibraryGridSizing

/** The image determines neither layout size nor crop ratio when its request completes. */
class ArtworkFrame(context: Context, private val ratio: Float) : FrameLayout(context) {
    init {
        background=ThemeGradientDrawable().apply { cornerRadius=Styler.dp(context,7f);setColor(Theme.colors(context).posterPlaceholder) }
        clipToOutline=true
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((width/ratio).toInt(),MeasureSpec.EXACTLY))
    }
}

class LandscapeCardView(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    private val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val progress=View(context).apply {setBackgroundColor(colors.accent)}
    private val badge=TextView(context).apply {
        text="✓";textSize=12f;gravity=Gravity.CENTER
        setTextColor(SemanticColor.foreground(colors.badgeAvailable))
        background=ThemeGradientDrawable().apply {cornerRadius=dp(12).toFloat();setColor(this@LandscapeCardView.colors.badgeAvailable)}
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleView=caption(14f,colors.primaryText)
    private val subtitle=caption(12f,colors.mutedText)
    private var fraction=0.0
    init {
        foreground=Styler.focusOutline(context,colors)
        orientation=VERTICAL; minimumHeight=dp(48)
        background=Styler.cardBackground(context,colors,8f,Color.TRANSPARENT,2f)
        Styler.makeFocusable(this); isClickable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        val art=ArtworkFrame(context,16f/9f)
        art.addView(image,FrameLayout.LayoutParams(-1,-1))
        art.addView(progress,FrameLayout.LayoutParams(0,dp(3),Gravity.BOTTOM or Gravity.START))
        art.addView(badge,FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP or Gravity.END).apply {setMargins(dp(6),dp(6),dp(6),dp(6))})
        addView(art,LayoutParams(-1,-2))
        addView(titleView,LayoutParams(-1,-2).apply {topMargin=dp(7)})
        addView(subtitle,LayoutParams(-1,-2).apply {topMargin=dp(3);bottomMargin=dp(6)})
        image.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_-> updateProgress()}
    }
    private fun caption(size: Float,color: Int)=TextView(context).apply {
        textSize=size;setTextColor(color);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        includeFontPadding=false;setPadding(dp(3),0,dp(3),0)
    }
    fun bind(hit: SearchHit,loader: ImageLoader,url: (String)->String) {
        titleView.text=hit.media.title;subtitle.text=hit.subtitle
        fraction=if(hit.played) 0.0 else hit.progress.coerceIn(0.0,1.0)
        progress.visibility=if(fraction>0) VISIBLE else GONE
        badge.visibility=if(hit.played) VISIBLE else GONE
        updateProgress()
        contentDescription=listOf(hit.media.title,hit.subtitle,if(hit.played) "Watched" else if(fraction>0) "${(fraction*100).toInt()} percent watched" else "").filter(String::isNotBlank).joinToString(", ")
        DetailStyler.image(image,url(hit.media.backdrop.ifBlank {hit.media.poster}).takeIf(String::isNotBlank),loader)
    }
    private fun updateProgress() {if(image.width>0) progress.layoutParams=progress.layoutParams.apply {width=(image.width*fraction).toInt()}}
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())
}

/** Width changes are layout work; do not reload data or recreate adapters when the rail toggles. */
fun RecyclerView.useResponsivePosterColumns(maxColumns: Int=7) {
    addOnLayoutChangeListener { _,left,_,right,_,oldLeft,_,oldRight,_->
        if(right-left==oldRight-oldLeft) return@addOnLayoutChangeListener
        val manager=layoutManager as? GridLayoutManager ?: return@addOnLayoutChangeListener
        val columns=LibraryGridSizing.columns(width,paddingLeft+paddingRight,resources.displayMetrics.density,maxColumns)
        if(manager.spanCount!=columns) manager.spanCount=columns
    }
}

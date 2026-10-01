package com.pocketds.hub.ui

import com.pocketds.hub.playback.ResumeRules
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
    companion object {
        /** Every poster, still and cover, and the focus ring drawn around one. */
        const val CORNER_DP = 10f
    }
    init {
        background=ThemeGradientDrawable().apply { cornerRadius=Styler.dp(context,CORNER_DP);setColor(Theme.colors(context).posterPlaceholder) }
        clipToOutline=true
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((width/ratio).toInt(),MeasureSpec.EXACTLY))
    }
}

class LandscapeCardView(context: Context, private val colors: PocketColors) : LinearLayout(context) {
    private val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val progress=ArtworkProgressView(context,colors.accent)
    private val badge=TextView(context).apply {
        text="✓";textSize=12f;gravity=Gravity.CENTER
        setTextColor(SemanticColor.foreground(colors.badgeAvailable))
        background=ThemeGradientDrawable().apply {cornerRadius=dp(12).toFloat();setColor(this@LandscapeCardView.colors.badgeAvailable)}
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleView=caption(12.5f,colors.primaryText).apply { textWeight(600) }
    private val subtitle=caption(11f,colors.mutedText)
    init {
        // Focus is a ring around the frame, as on a poster, with the words
        // under it left alone rather than boxed in a tinted card.
        orientation=VERTICAL; minimumHeight=dp(48)
        background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        Styler.makeFocusable(this); isClickable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        val art=ArtworkFrame(context,16f/9f).apply {
            isDuplicateParentStateEnabled=true
            foreground=Styler.focusOutline(context,colors)
        }
        art.addView(image,FrameLayout.LayoutParams(-1,-1))
        art.addView(progress,FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
        art.addView(badge,FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP or Gravity.END).apply {setMargins(dp(6),dp(6),dp(6),dp(6))})
        addView(art,LayoutParams(-1,-2))
        addView(titleView,LayoutParams(-1,-2).apply {topMargin=dp(6)})
        addView(subtitle,LayoutParams(-1,-2).apply {topMargin=dp(2);bottomMargin=dp(2)})
    }
    private fun caption(size: Float,color: Int)=TextView(context).apply {
        textSize=size;setTextColor(color);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        includeFontPadding=false;setPadding(dp(1),0,dp(1),0)
    }
    fun bind(hit: SearchHit,loader: ImageLoader,url: (String)->String) {
        titleView.text=hit.media.title;subtitle.text=hit.subtitle
        progress.fraction=hit.progress
        badge.visibility=if(ResumeRules.showsWatched(hit.played,hit.progress)) VISIBLE else GONE
        contentDescription=listOf(hit.media.title,hit.subtitle,ResumeRules.watchLabel(hit.played,hit.progress).orEmpty()).filter(String::isNotBlank).joinToString(", ")
        DetailStyler.image(image,url(hit.media.backdrop.ifBlank {hit.media.poster}).takeIf(String::isNotBlank),loader)
    }
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

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
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/** The image determines neither layout size nor crop ratio when its request completes. */
class ArtworkFrame(context: Context, private val ratio: Float, cornerDp: Float = CORNER_DP) : FrameLayout(context) {
    companion object {
        /** Every poster, still and cover, and the focus ring drawn around one. */
        const val CORNER_DP = 10f
        /** A Glass card's corner, the prototype's Pocket radius. */
        const val GLASS_CORNER_DP = 11f
    }
    init {
        background=ThemeGradientDrawable().apply { cornerRadius=Styler.dp(context,cornerDp);setColor(Theme.colors(context).posterPlaceholder) }
        clipToOutline=true
    }
    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        val width=MeasureSpec.getSize(widthMeasureSpec)
        super.onMeasure(widthMeasureSpec,MeasureSpec.makeMeasureSpec((width/ratio).toInt(),MeasureSpec.EXACTLY))
    }
}

/**
 * A 16:9 card with the title and "S1E4 · Title" under it: Home's Continue
 * watching and Next up.
 *
 * [glass] is the Glass tile (GLASS_PLAN.md): 11dp corners, the progress as a
 * white bar inside the still's lower edge on a faint track, a tick in the
 * accent, and a play disc of the page's glass on the focused tile, as the
 * prototype draws them.
 */
class LandscapeCardView(context: Context, private val colors: PocketColors, private val glass: Boolean = false) : LinearLayout(context) {
    private val image=ImageView(context).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    private val progress=ArtworkProgressView(context,if(glass) Color.WHITE else colors.accent)
    /** Glass: the track the progress runs along, inside the still. */
    private val progressTrack:FrameLayout?=if(glass) FrameLayout(context).apply {
        background=ThemeGradientDrawable.rounded(dp(2).toFloat(),GLASS_TRACK);clipToOutline=true;visibility=GONE
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    } else null
    /** Glass: where X would start it, shown on the focused tile only. */
    private val playDisc:FrameLayout?=if(glass) FrameLayout(context).apply {
        val disc=GlassPanelDrawable(GlassColors.panel(GlassPage.palette(context)),dp(23).toFloat())
        background=disc;alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(ImageView(context).apply {setImageDrawable(AppIconDrawable(AppIcon.PLAY,Color.WHITE))},FrameLayout.LayoutParams(dp(18),dp(18),Gravity.CENTER).apply {leftMargin=dp(2)})
        GlassPage.follow(this) { page -> disc.retint(GlassColors.panel(page)) }
    } else null
    private val badge=TextView(context).apply {
        text="✓";textSize=12f;gravity=Gravity.CENTER
        val fill=if(glass) colors.accent else colors.badgeAvailable
        setTextColor(if(glass) colors.accentText else SemanticColor.foreground(fill))
        background=ThemeGradientDrawable().apply {cornerRadius=dp(12).toFloat();setColor(fill)}
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val titleView=caption(if(glass) 12f else 12.5f,colors.primaryText).apply { textWeight(if(glass) 700 else 600) }
    private val subtitle=caption(11f,if(glass) GLASS_SUBTITLE else colors.mutedText)
    init {
        // Focus is a ring around the frame, as on a poster, with the words
        // under it left alone rather than boxed in a tinted card.
        orientation=VERTICAL; minimumHeight=dp(48)
        background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        Styler.makeFocusable(this); isClickable=true;descendantFocusability=FOCUS_BLOCK_DESCENDANTS
        val corner=if(glass) ArtworkFrame.GLASS_CORNER_DP else ArtworkFrame.CORNER_DP
        val art=ArtworkFrame(context,16f/9f,corner).apply {
            isDuplicateParentStateEnabled=true
            foreground=Styler.focusOutline(context,colors,corner,if(glass) 3f else 2f)
        }
        art.addView(image,FrameLayout.LayoutParams(-1,-1))
        if(progressTrack!=null) {
            // The prototype's 6% inset from the edges of a 186dp tile.
            progressTrack.addView(progress,FrameLayout.LayoutParams(-1,-1))
            art.addView(progressTrack,FrameLayout.LayoutParams(-1,dp(4),Gravity.BOTTOM).apply {setMargins(dp(11),0,dp(11),dp(11))})
        } else art.addView(progress,FrameLayout.LayoutParams(-1,dp(3),Gravity.BOTTOM))
        playDisc?.let {art.addView(it,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.CENTER))}
        art.addView(badge,FrameLayout.LayoutParams(dp(22),dp(22),Gravity.TOP or Gravity.END).apply {setMargins(dp(6),dp(6),dp(6),dp(6))})
        addView(art,LayoutParams(-1,-2))
        addView(titleView,LayoutParams(-1,-2).apply {topMargin=dp(6)})
        addView(subtitle,LayoutParams(-1,-2).apply {topMargin=dp(if(glass) 1 else 2);bottomMargin=dp(2)})
    }
    private fun caption(size: Float,color: Int)=TextView(context).apply {
        textSize=size;setTextColor(color);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        // A Hebrew title starts at the card's left edge like every other, not its right.
        textAlignment=android.view.View.TEXT_ALIGNMENT_VIEW_START
        includeFontPadding=false;setPadding(dp(1),0,dp(1),0)
    }
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus,direction,previouslyFocusedRect)
        playDisc?.animate()?.alpha(if(gainFocus) 1f else 0f)?.setDuration(200)?.start()
    }
    fun bind(hit: SearchHit,loader: ImageLoader,url: (String)->String) {
        titleView.text=hit.media.title;subtitle.text=hit.subtitle
        progress.fraction=hit.progress
        progressTrack?.visibility=if(hit.progress>0.0) VISIBLE else GONE
        badge.visibility=if(ResumeRules.showsWatched(hit.played,hit.progress)) VISIBLE else GONE
        contentDescription=listOf(hit.media.title,hit.subtitle,ResumeRules.watchLabel(hit.played,hit.progress).orEmpty()).filter(String::isNotBlank).joinToString(", ")
        DetailStyler.image(image,url(hit.media.backdrop.ifBlank {hit.media.poster}).takeIf(String::isNotBlank),loader)
    }
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())

    private companion object {
        /** The prototype's track under a tile's progress, white at 28%, and its second caption line at 64%. */
        const val GLASS_TRACK = 0x47FFFFFF
        const val GLASS_SUBTITLE = 0xA3FFFFFF.toInt()
    }
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

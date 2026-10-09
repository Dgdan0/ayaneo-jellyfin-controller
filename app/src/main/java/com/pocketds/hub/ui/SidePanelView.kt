package com.pocketds.hub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.ui.glass.GlassColors
import com.pocketds.hub.ui.glass.GlassPage
import com.pocketds.hub.ui.glass.GlassPanelDrawable

/**
 * Modal in the existing window: stable content underneath, bounded body and real accessible focus.
 *
 * The side panel is a sheet down the right edge with a heading and a small
 * round close. Its rows sit on raised cards: consecutive [choice] and
 * [setting] rows share one card, and a [section] heading, a [note] or anything
 * added to [body] by hand starts the next. Short questions use the centred
 * form of the same panel.
 *
 * The sheet and its cards are glass panels tinted by the artwork the page
 * shows when the panel opens ([GlassPage]), in the look of the page under it.
 */
open class SidePanelView(context:Context, protected val colors:PocketColors, private val ringVisible:()->Boolean,
    private val side:Boolean=true) : FrameLayout(context) {
    val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(0,dp(2),0,dp(12))}
    val footer=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
    /** The page's tint, taken again each time the panel opens: nearly solid for the sheet, a panel for its cards. */
    private var glassFill=GlassColors.panel(GlassPage.palette(context))
    private val glassCard=GlassPanelDrawable(GlassColors.sheet(GlassPage.palette(context)),if(side) 0f else Styler.dp(context,16f))
    private val card=LinearLayout(context).apply {
        orientation=LinearLayout.VERTICAL;isClickable=true
        setPadding(dp(18),dp(14),dp(18),dp(6))
        background=glassCard
    }
    private val titleView=TextView(context).apply {
        typeRole(Type.Role.HEADING,18f);setTextColor(colors.primaryText);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
    }
    /** A small round close, the size of the design's; the view around it stays finger-sized. */
    private val close=ImageView(context).apply {
        setImageDrawable(AppIconDrawable(AppIcon.CLOSE,colors.primaryText));scaleType=ImageView.ScaleType.CENTER_INSIDE
        val pad=dp(11);setPadding(pad,pad,pad,pad);contentDescription="Close panel"
        background=android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),ThemeGradientDrawable.oval(
                ColorUtils.setAlphaComponent(this@SidePanelView.colors.primaryText,0x29),dp(2),this@SidePanelView.colors.focusRing))
            addState(intArrayOf(),ThemeGradientDrawable.oval(ColorUtils.setAlphaComponent(this@SidePanelView.colors.primaryText,0x1A)))
        }
        Styler.makeFocusable(this)
    }
    private val subtitle=TextView(context).apply {textSize=12f;setTextColor(colors.mutedText);setPadding(0,dp(2),0,dp(10));maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val tabRow=FrameLayout(context)
    private val scroll=FocusScrollView(context).apply {clipToPadding=false;addView(body)}
    private var opener:View?=null
    private var dismissed:(()->Unit)?=null
    private val hiddenAccessibility=mutableMapOf<View,Int>()
    /**
     * The row last chosen in each menu, by title and tab. Backing out of a
     * submenu rebuilds its parent, which used to put the cursor back on the
     * first row: Audio & subtitles -> Quality -> Back landed on Play rather
     * than Quality. A menu reopened without an explicit start row now lands
     * where it was left. Danger rows are not remembered, so a stray A cannot
     * land on Delete.
     */
    private val lastChosen=mutableMapOf<String,Int>()
    /** The row a submenu was opened from; it beats a menu's own start row once, on the way back. */
    private var returning:Pair<String,Int>?=null
    private var menuKey=""
    private val choiceRows=mutableListOf<View>()
    /** The choice and setting rows, in order: what a test or a screen means by "the first row". */
    val rows:List<View> get()=choiceRows
    open val isOpen get()=visibility==VISIBLE
    /** Optional reader preview hook; called for both cancel and successful selection. */
    var onPanelGeometryChanged: (() -> Unit)? = null
    val panelStartX: Int get() = if (card.isLaidOut) card.left else
        (width - (card.layoutParams as LayoutParams).rightMargin - card.layoutParams.width).coerceAtLeast(0)
    init {
        visibility=GONE;isClickable=true;setBackgroundColor(Color.argb(if(side) 75 else 145,0,0,0))
        setOnClickListener {cancel()}
        val header=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(40)}
        header.addView(titleView,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
        header.addView(close,LinearLayout.LayoutParams(dp(36),dp(36)))
        close.activateOnTap(::cancel);FocusDecorator.attach(close,ringVisible,scale=false)
        card.addView(header,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(4)})
        card.addView(subtitle);card.addView(tabRow,LinearLayout.LayoutParams(-1,-2))
        card.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        card.addView(footer,LinearLayout.LayoutParams(-1,-2))
        addView(card,if(side) LayoutParams(dp(320),-1,Gravity.END) else
            LayoutParams(dp(420),-1,Gravity.CENTER).apply {setMargins(dp(12),dp(12),dp(12),dp(12))})
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if(isOpen) onPanelGeometryChanged?.invoke() }
    }
    /** The centred card's width, for a panel whose body is not rows (the profile picker's tiles). */
    protected open val centredWidthDp:Int get()=420
    /** The centred card takes its content's height, up to the screen's, instead of the rows' estimate. */
    protected open val wrapsHeight:Boolean get()=false
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=MeasureSpec.getSize(widthMeasureSpec)
        card.layoutParams.width=if(side) dp(PanelGeometry.width((available/resources.displayMetrics.density).toInt())) else minOf(dp(centredWidthDp),(available-dp(24)).coerceAtLeast(1))
        card.layoutParams.height=when {
            side -> -1
            wrapsHeight -> LayoutParams.WRAP_CONTENT
            else -> minOf(dp(minOf(360,100+choiceRows.size*54+(body.childCount-groups())*44)),(MeasureSpec.getSize(heightMeasureSpec)-dp(24)).coerceAtLeast(1))
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    /**
     * A question that is the whole heading ("Who is watching?"): centred, in
     * the display face at [sizeSp], with no close button; B or a tap outside
     * the card still cancels.
     */
    protected fun centreHeading(sizeSp:Float) {
        titleView.typeRole(Type.Role.HERO,sizeSp)
        titleView.gravity=Gravity.CENTER;titleView.textAlignment=TEXT_ALIGNMENT_CENTER
        close.visibility=GONE
    }
    private fun groups()=(0 until body.childCount).count {body.getChildAt(it) is RowGroup}
    fun open(title:String,detail:String="",onDismiss:()->Unit={}) {
        if(!isOpen) {
            opener=rootView.findFocus()
            (parent as? ViewGroup)?.let {parent-> for(i in 0 until parent.childCount) {
                val sibling=parent.getChildAt(i)
                if(sibling!=this){hiddenAccessibility[sibling]=sibling.importantForAccessibility;sibling.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            }}
        }
        val page=GlassPage.palette(context);glassFill=GlassColors.panel(page);glassCard.retint(GlassColors.sheet(page))
        // Cards a menu built before opening take the same tint.
        (0 until body.childCount).forEach {(body.getChildAt(it).background as? GlassPanelDrawable)?.retint(glassFill)}
        // A side sheet on a page that draws under the top bar starts below it.
        if(side) (card.layoutParams as LayoutParams).let {lp->
            val inset=TopChrome.overlap((parent as? View) ?: this)
            if(lp.topMargin!=inset){lp.topMargin=inset;card.layoutParams=lp}
        }
        dismissed=onDismiss;titleView.text=title;menuKey=title;subtitle.text=detail;subtitle.visibility=if(detail.isBlank()) GONE else VISIBLE
        visibility=VISIBLE;bringToFront();ViewCompat.setAccessibilityPaneTitle(this,title)
        onPanelGeometryChanged?.invoke()
        if(ValueAnimator.areAnimatorsEnabled()){
            card.alpha=0f;card.translationX=if(side) dp(24).toFloat() else 0f
            card.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }
    }
    /** [keepScroll]: a caller redrawing its own rows on every press (the request form) stays where it was. */
    fun resetBody(keepScroll:Boolean=false) {body.removeAllViews();tabRow.removeAllViews();footer.removeAllViews();choiceRows.clear();trailers.clear();if(!keepScroll)scroll.scrollTo(0,0)}
    /** A card for rows the caller builds and selects itself (the request form's); a [section] before it starts a new one. */
    fun group():LinearLayout=currentGroup()
    /**
     * Scrolls the body so [view], somewhere inside it, is on screen. Only the
     * latest call scrolls, and only while [view] is still in the body: the
     * request form rebuilds its rows on every press, and two quick presses
     * left the first call measuring a row the second had already removed,
     * which crashed the app (IllegalArgumentException, "parameter must be a
     * descendant of this view").
     */
    fun reveal(view:View) {
        revealing=view
        scroll.post {
            if(revealing!==view||!holds(body,view)) return@post
            revealing=null
            val rect=android.graphics.Rect();view.getDrawingRect(rect);body.offsetDescendantRectToMyCoords(view,rect)
            val top=scroll.scrollY;val bottom=top+scroll.height-scroll.paddingBottom
            when {
                rect.top<top -> scroll.smoothScrollTo(0,(rect.top-dp(28)).coerceAtLeast(0))
                rect.bottom>bottom -> scroll.smoothScrollTo(0,rect.bottom-scroll.height+dp(10))
            }
        }
    }
    private var revealing:View?=null
    private fun holds(group:ViewGroup,view:View):Boolean {
        var parent:android.view.ViewParent?=view.parent
        while(parent!=null){if(parent===group) return true;parent=parent.parent}
        return false
    }
    /** Focuses [preferred], else the row last chosen in this menu, else the first row. */
    fun focusBody(preferred:View?=null) {
        val back=returning?.takeIf {it.first==menuKey}?.let {returning=null;choiceRows.getOrNull(it.second)}
        val remembered=lastChosen[menuKey]?.let(choiceRows::getOrNull)
        post {if(isOpen)(back ?: preferred ?: remembered ?: body.getFocusables(FOCUS_FORWARD).firstOrNull() ?: close).requestFocus()}
    }
    fun tabs(values:List<Pair<String,String>>,selected:String,onPick:(String)->Unit) { tabs(values, selected, false, onPick = onPick) }

    /**
     * Two to four views of one menu, as the app's pick-one pill; a fourth tightens each label's padding so the pill
     * stays inside the panel. [dividers] is no longer drawn; the pill separates them.
     */
    /**
     * [page] names a page of its own under the [selected] tab (the reader's Read-along highlight under Themes): its rows are
     * not the tab's, so the row the tab last chose, or the one it was opened from, must not pick its cursor (#66).
     */
    @Suppress("UNUSED_PARAMETER")
    fun tabs(values:List<Pair<String,String>>,selected:String,dividers:Boolean,page:String=selected,onPick:(String)->Unit) {
        tabRow.removeAllViews()
        menuKey="${titleView.text}/$page"
        val current=selected;val pick=onPick
        tabRow.addView(BlobSegmentedView(context,colors,ringVisible).apply {
            heightDp=36f;textSp=13f;padXDp=if(values.size>3)9f else 14f
            setOptions(values.map {(id,label)->BlobSegmentedView.Option(id,label)},current)
            this.onPick={id->if(id!=current)pick(id)}
        },FrameLayout.LayoutParams(-2,-2).apply {bottomMargin=dp(12)})
    }
    /** A small capital heading; the rows after it start a new card. */
    fun section(label:String) {
        body.addView(TextView(context).apply {
            text=label.uppercase();typeRole(Type.Role.EYEBROW,10.5f);setTextColor(colors.mutedText)
            setPadding(dp(4),dp(if(body.childCount==0) 2 else 10),dp(4),dp(7))
        })
    }
    /** The next row starts a new card, with no heading between. */
    fun startGroup() { body.addView(Space(context),LinearLayout.LayoutParams(-1,0)) }
    /** Small print under the rows: what the panel does not cover, and where that lives instead. */
    fun note(text:String) {
        body.addView(TextView(context).apply {
            this.text=text;textSize=11f;setTextColor(colors.mutedText);setLineSpacing(0f,1.2f)
            setPadding(dp(4),dp(2),dp(4),dp(8))
        })
    }
    /**
     * One option. [leading] sits before the words: a chapter's frame. A
     * selected option shows the check mark; there is no "Selected" text.
     * [trailing] is a figure at the row's right edge, past the check mark, as a printed contents list ends
     * each line with its page (#55): muted, in tabular figures so a column of them lines up. An empty one keeps
     * the place for a figure that is not known yet, which [setTrailing] fills in; null has no place.
     */
    fun choice(label:String,detail:String="",selected:Boolean=false,danger:Boolean=false,leading:View?=null,icon:android.graphics.drawable.Drawable?=null,trailing:String?=null,trailingSpoken:String=trailing.orEmpty(),onPick:()->Unit):View {
        val row=row(label,detail,selected,danger,onPick)
        leading?.let {row.addView(it,0,LinearLayout.LayoutParams(dp(96),dp(54)).apply {marginEnd=dp(12)})}
        // A small symbol before the words (#48): the card menu's rows, the done mark on Remove download.
        icon?.let {row.addView(ImageView(context).apply {setImageDrawable(it);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},0,LinearLayout.LayoutParams(dp(22),dp(22)).apply {marginEnd=dp(12)})}
        if(selected)row.addView(ImageView(context).apply {setImageDrawable(AppIconDrawable(AppIcon.CHECK,colors.accent));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(20),dp(20)).apply{marginStart=dp(8)})
        if(trailing!=null) {
            val figure=TextView(context).apply {
                textSize=13f;textWeight(600);setTextColor(colors.mutedText);maxLines=1;gravity=Gravity.END
                fontFeatureSettings="tnum";textDirection=TEXT_DIRECTION_LTR;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(figure,LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(12)})
            trailers[row]=Trailing(figure,row.contentDescription?.toString().orEmpty())
            setTrailing(row,trailing,trailingSpoken)
        }
        return row
    }
    /** A row's trailing figure: where it is on screen, and how the row reads without it. */
    private class Trailing(val view:TextView,val described:String)
    private val trailers=mutableMapOf<View,Trailing>()
    /** Sets or clears the figure at the right edge of a [choice] row made with a [trailing]; [spoken] is how a screen reader says it. */
    fun setTrailing(row:View,text:String,spoken:String=text) {
        val slot=trailers[row] ?: return
        slot.view.text=text;slot.view.visibility=if(text.isBlank()) GONE else VISIBLE
        row.contentDescription=listOf(slot.described,spoken).filter(String::isNotBlank).joinToString(", ")
    }
    /**
     * A key and what it does (a reader's Controls sheet, #16): the caps as
     * the hint bar draws them in a column of their own, then the words. The
     * row takes focus so the pad can scroll a long list; pressing it does
     * nothing.
     */
    fun keys(caps:List<String>,label:String):View {
        val row=row(label,"",false,false) {}
        row.contentDescription=caps.joinToString(" ")+": "+label
        val column=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        caps.forEach { cap ->
            val glyph=KeyGlyphDrawable(cap,dp(20),Type.text(context,800))
            column.addView(ImageView(context).apply {setImageDrawable(glyph);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},
                LinearLayout.LayoutParams(glyph.intrinsicWidth,glyph.intrinsicHeight).apply {marginEnd=dp(5)})
        }
        row.addView(column,0,LinearLayout.LayoutParams(dp(KEY_COLUMN_DP),-2).apply {marginEnd=dp(10)})
        return row
    }
    /** A row that opens its own menu: the setting, its current value, and a chevron. */
    fun setting(label:String,value:String,onPick:()->Unit):View {
        val row=row(label,"",false,false,onPick)
        row.contentDescription=listOf(label,value).filter(String::isNotBlank).joinToString(", ")
        row.addView(TextView(context).apply {
            text=value;textSize=12f;setTextColor(colors.mutedText);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
            textDirection=TEXT_DIRECTION_LTR;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(-2,-2).apply {marginStart=dp(8)})
        row.addView(ImageView(context).apply {setImageDrawable(AppIconDrawable(AppIcon.NEXT,colors.mutedText));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},
            LinearLayout.LayoutParams(dp(14),dp(14)).apply{marginStart=dp(6)})
        return row
    }
    private fun row(label:String,detail:String,selected:Boolean,danger:Boolean,onPick:()->Unit):LinearLayout {
        val row=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(48);setPadding(dp(12),dp(8),dp(12),dp(8))
            val key=menuKey;val index=choiceRows.size
            background=Styler.selectionBackground(context,colors,selected,cornerDp=12f);Styler.makeFocusable(this)
            activateOnTap {
                if(danger){lastChosen.remove(key);returning=null} else {lastChosen[key]=index;returning=key to index}
                onPick()
            }
            contentDescription=listOf(label,detail,if(selected) "Selected" else "").filter(String::isNotBlank).joinToString(", ")
            isSelected=selected;FocusDecorator.attach(this,ringVisible,scale=false)
        }
        val copy=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        copy.addView(TextView(context).apply {text=label;textSize=14f;textWeight(600);setTextColor(if(danger) colors.dangerText else colors.primaryText)})
        if(detail.isNotBlank())copy.addView(TextView(context).apply {text=detail;textSize=12f;setTextColor(colors.mutedText);setPadding(0,dp(2),0,0)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        currentGroup().addView(row,LinearLayout.LayoutParams(-1,-2))
        choiceRows+=row
        return row
    }
    /** The card the next row joins: the last thing in [body] if it is one, else a new one. */
    private fun currentGroup():RowGroup =
        (body.getChildAt(body.childCount-1) as? RowGroup) ?: RowGroup(context).also {
            it.background=GlassPanelDrawable(glassFill,dp(14).toFloat())
            body.addView(it,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        }
    private class RowGroup(context:Context) : LinearLayout(context) {
        init {orientation=VERTICAL;val pad=Styler.dpInt(context,3f);setPadding(pad,pad,pad,pad)}
    }
    open fun dismiss() {
        if(!isOpen)return
        card.animate().cancel();card.alpha=1f;visibility=GONE
        onPanelGeometryChanged?.invoke()
        hiddenAccessibility.forEach {(view,mode)->view.importantForAccessibility=mode};hiddenAccessibility.clear()
        opener?.takeIf {it.isShown && it.isFocusable}?.requestFocus();opener=null;dismissed=null
    }
    // A Back that closes the whole panel (no parent reopened) drops the return marker.
    fun cancel(){val callback=dismissed;dismiss();callback?.invoke();if(!isOpen)returning=null}
    open fun onPad(action:PadAction):Boolean {
        if(!isOpen)return false
        val focused=findFocus()
        if(action is PadAction.Step) {
            var ancestor:View?=focused
            while(ancestor!=null && ancestor!=this) {
                if(ancestor is ValueAdjusterView && ancestor.onDirection(action.direction))return true
                ancestor=ancestor.parent as? View
            }
        }
        when(action) {
            PadAction.Back->cancel()
            PadAction.Activate->focused?.performClick()
            is PadAction.Step->{val direction=when(action.direction){Direction.LEFT->FOCUS_LEFT;Direction.RIGHT->FOCUS_RIGHT;Direction.UP->FOCUS_UP;Direction.DOWN->FOCUS_DOWN}
                FocusFinder.getInstance().findNextFocus(card,focused,direction)?.requestFocus()}
            else->Unit
        }
        return true
    }
    protected fun dp(n:Int)=Styler.dpInt(context,n.toFloat())

    companion object {
        /** The caps' column in a [keys] row, wide enough for "D-pad ← →". */
        const val KEY_COLUMN_DP=118
        /** Android edge-back follows the same modal-first ordering as the controller's B. */
        fun dismissTopIn(root: View): Boolean {
            if(root.visibility!=VISIBLE)return false
            if(root is SidePanelView && root.isOpen){root.cancel();return true}
            if(root is ViewGroup)for(i in root.childCount-1 downTo 0)if(dismissTopIn(root.getChildAt(i)))return true
            return false
        }
    }
}

/**
 * How a [ValueAdjusterView] frames its slider: the glyphs of its two step buttons, how large each is, and whether the
 * slider's steps are marked. [SIZE] is Kindle's text size: a small A, a large A, a mark at every step (#47).
 */
data class AdjusterStyle(val less:String="−",val more:String="+",val lessSp:Float=24f,val moreSp:Float=24f,val ticks:Boolean=false,val compact:Boolean=false) {
    companion object {
        val STEPS=AdjusterStyle()
        val SIZE=AdjusterStyle(less="A",more="A",lessSp=14f,moreSp=26f,ticks=true)
        /** One short line, the label beside a slider and no step buttons: what a sheet's fixed foot has room for (the appearance sheet's brightness, #47). */
        val FOOT=AdjusterStyle(compact=true)
    }
}

class ValueAdjusterView(context:Context,colors:PocketColors,label:String,private val range:ValueRange,initial:Float,
    private val format:(Float)->String,private val style:AdjusterStyle=AdjusterStyle.STEPS,private val changed:(Float)->Unit) : LinearLayout(context) {
    private val value=TextView(context).apply{textSize=13f;setTextColor(colors.primaryText)}
    private val seek=SeekBar(context).apply{
        max=range.steps;progress=range.index(initial);contentDescription=label;minimumHeight=dp(48);Styler.makeFocusable(this)
        progressTintList=android.content.res.ColorStateList.valueOf(colors.accent)
        thumbTintList=android.content.res.ColorStateList.valueOf(colors.accent)
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.cardSurfacePressed)
        ViewCompat.setStateDescription(this,format(range.at(progress)))
        if(style.ticks)tickMark=android.graphics.drawable.GradientDrawable().apply {setSize(dp(2),dp(10));setColor(colors.mutedText)}
    }
    init {
        // The slider reports each step the same way in both layouts.
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar)=Unit
            override fun onStopTrackingTouch(bar:SeekBar)=Unit
            override fun onProgressChanged(bar:SeekBar,index:Int,fromUser:Boolean) {
                val v=range.at(index);value.text="$label · ${format(v)}";ViewCompat.setStateDescription(seek,format(v));changed(v)
            }
        })
        value.text="$label · ${format(range.at(seek.progress))}"
        if(style.compact) {
            orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(2),dp(4),dp(2))
            addView(value,LayoutParams(dp(132),-2));addView(seek,LayoutParams(0,dp(44),1f))
        } else {
            orientation=VERTICAL;setPadding(dp(4),dp(10),dp(4),dp(6));addView(value)
            val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
            fun stepButton(text:String,delta:Int,sp:Float)=TextView(context).apply {
                this.text=text;textSize=sp;gravity=Gravity.CENTER;contentDescription="$label ${if(delta<0) "decrease" else "increase"}"
                DetailStyler.action(this,colors);activateOnTap{seek.progress=(seek.progress+delta).coerceIn(0,seek.max)}
            }
            row.addView(stepButton(style.less,-1,style.lessSp),LayoutParams(dp(48),dp(48)))
            row.addView(seek,LayoutParams(0,dp(48),1f));row.addView(stepButton(style.more,1,style.moreSp),LayoutParams(dp(48),dp(48)))
            addView(row)
        }
    }
    fun onDirection(direction:Direction):Boolean {
        if(!seek.hasFocus() || direction !in listOf(Direction.LEFT,Direction.RIGHT))return false
        seek.progress=(seek.progress+if(direction==Direction.LEFT)-1 else 1).coerceIn(0,seek.max);return true
    }
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())
}

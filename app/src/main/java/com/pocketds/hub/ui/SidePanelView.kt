package com.pocketds.hub.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.FocusFinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction

/** Modal in the existing window: stable content underneath, bounded body and real accessible focus. */
open class SidePanelView(context:Context, protected val colors:PocketColors, private val ringVisible:()->Boolean,
    private val side:Boolean=true) : FrameLayout(context) {
    val body=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(4),0,dp(4),dp(12))}
    val footer=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
    private val card=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(8),dp(12),dp(4));isClickable=true
        background=ThemeGradientDrawable().apply {cornerRadius=dp(16).toFloat();setColor(this@SidePanelView.colors.cardSurface)}}
    private val titleView=TextView(context).apply {textSize=20f;setTextColor(colors.primaryText);maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val close=AppIcons.button(context,colors,AppIcon.CLOSE,"Close panel")
    private val subtitle=TextView(context).apply {textSize=12f;setTextColor(colors.mutedText);setPadding(dp(4),0,dp(4),dp(8));maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val tabRow=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL}
    private val scroll=ScrollView(context).apply {isFocusable=false;isFocusableInTouchMode=false;clipToPadding=false;addView(body)}
    private var opener:View?=null
    private var dismissed:(()->Unit)?=null
    private val hiddenAccessibility=mutableMapOf<View,Int>()
    val isOpen get()=visibility==VISIBLE
    /** Optional reader preview hook; called for both cancel and successful selection. */
    var onPanelGeometryChanged: (() -> Unit)? = null
    val panelStartX: Int get() = if (card.isLaidOut) card.left else
        (width - (card.layoutParams as LayoutParams).rightMargin - card.layoutParams.width).coerceAtLeast(0)
    init {
        visibility=GONE;isClickable=true;setBackgroundColor(Color.argb(if(side) 75 else 145,0,0,0))
        setOnClickListener {cancel()}
        val header=LinearLayout(context).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        header.addView(titleView,LinearLayout.LayoutParams(0,-2,1f));header.addView(close,LinearLayout.LayoutParams(dp(48),dp(48)))
        close.activateOnTap(::cancel);FocusDecorator.attach(close,ringVisible,scale=false)
        card.addView(header);card.addView(subtitle);card.addView(tabRow)
        card.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        card.addView(footer,LinearLayout.LayoutParams(-1,-2))
        addView(card,LayoutParams(dp(if(side)320 else 420),-1,if(side) Gravity.END else Gravity.CENTER).apply {setMargins(dp(12),dp(12),dp(12),dp(12))})
        card.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if(isOpen) onPanelGeometryChanged?.invoke() }
    }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        val available=MeasureSpec.getSize(widthMeasureSpec)
        card.layoutParams.width=if(side) dp(PanelGeometry.width((available/resources.displayMetrics.density).toInt())) else minOf(dp(420),(available-dp(24)).coerceAtLeast(1))
        card.layoutParams.height=if(side) -1 else minOf(dp(minOf(340,108+body.childCount*72)),(MeasureSpec.getSize(heightMeasureSpec)-dp(24)).coerceAtLeast(1))
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    fun open(title:String,detail:String="",onDismiss:()->Unit={}) {
        if(!isOpen) {
            opener=rootView.findFocus()
            (parent as? ViewGroup)?.let {parent-> for(i in 0 until parent.childCount) {
                val sibling=parent.getChildAt(i)
                if(sibling!=this){hiddenAccessibility[sibling]=sibling.importantForAccessibility;sibling.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            }}
        }
        dismissed=onDismiss;titleView.text=title;subtitle.text=detail;subtitle.visibility=if(detail.isBlank()) GONE else VISIBLE
        visibility=VISIBLE;bringToFront();ViewCompat.setAccessibilityPaneTitle(this,title)
        onPanelGeometryChanged?.invoke()
        if(ValueAnimator.areAnimatorsEnabled()){card.alpha=0f;card.animate().alpha(1f).setDuration(180).start()}
    }
    fun resetBody() {body.removeAllViews();tabRow.removeAllViews();footer.removeAllViews();scroll.scrollTo(0,0)}
    fun focusBody(preferred:View?=null) {post {if(isOpen)(preferred ?: body.getFocusables(FOCUS_FORWARD).firstOrNull() ?: close).requestFocus()}}
    fun tabs(values:List<Pair<String,String>>,selected:String,onPick:(String)->Unit) = tabs(values, selected, false, onPick)

    fun tabs(values:List<Pair<String,String>>,selected:String,dividers:Boolean,onPick:(String)->Unit) {
        tabRow.removeAllViews()
        values.forEachIndexed { index,(id,label)->
            if(dividers && index>0) tabRow.addView(View(context).apply {setBackgroundColor(colors.mutedText)},LinearLayout.LayoutParams(dp(1),dp(20)).apply {gravity=Gravity.CENTER_VERTICAL})
            tabRow.addView(TextView(context).apply {
            tag="tab:$id"
            text=label;textSize=14f;gravity=Gravity.CENTER;minimumHeight=dp(48);isSelected=id==selected
            setTextColor(if(isSelected) colors.accent else colors.mutedText)
            background=Styler.selectionBackground(context,colors,isSelected,cornerDp=8f)
            contentDescription=if(isSelected) "$label, selected" else label
            if(isSelected)foreground=android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(colors.accent))).apply {
                setLayerHeight(0,dp(2));setLayerGravity(0,Gravity.BOTTOM);setLayerInsetLeft(0,dp(10));setLayerInsetRight(0,dp(10))
            }
            Styler.makeFocusable(this)
            activateOnTap {onPick(id)}
        },LinearLayout.LayoutParams(0,dp(48),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(10))})}
    }
    fun choice(label:String,detail:String="",selected:Boolean=false,danger:Boolean=false,onPick:()->Unit):View {
        val row=LinearLayout(context).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(52);setPadding(dp(10),dp(8),dp(10),dp(8))
            background=Styler.selectionBackground(context,colors,selected);Styler.makeFocusable(this);activateOnTap(onPick)
            contentDescription=listOf(label,detail,if(selected) "Selected" else "").filter(String::isNotBlank).joinToString(", ")
            isSelected=selected;FocusDecorator.attach(this,ringVisible,scale=false)
        }
        val copy=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
        copy.addView(TextView(context).apply {text=label;textSize=14f;setTextColor(if(danger) colors.dangerText else colors.primaryText)})
        if(detail.isNotBlank())copy.addView(TextView(context).apply {text=detail;textSize=12f;setTextColor(colors.mutedText);setPadding(0,dp(3),0,0)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        if(selected)row.addView(ImageView(context).apply {setImageDrawable(AppIconDrawable(AppIcon.CHECK,colors.accent));importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(dp(22),dp(22)).apply{marginStart=dp(8)})
        body.addView(row,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(3)})
        return row
    }
    open fun dismiss() {
        if(!isOpen)return
        card.animate().cancel();card.alpha=1f;visibility=GONE
        onPanelGeometryChanged?.invoke()
        hiddenAccessibility.forEach {(view,mode)->view.importantForAccessibility=mode};hiddenAccessibility.clear()
        opener?.takeIf {it.isShown && it.isFocusable}?.requestFocus();opener=null;dismissed=null
    }
    fun cancel(){val callback=dismissed;dismiss();callback?.invoke()}
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
        /** Android edge-back follows the same modal-first ordering as the controller's B. */
        fun dismissTopIn(root: View): Boolean {
            if(root.visibility!=VISIBLE)return false
            if(root is SidePanelView && root.isOpen){root.cancel();return true}
            if(root is ViewGroup)for(i in root.childCount-1 downTo 0)if(dismissTopIn(root.getChildAt(i)))return true
            return false
        }
    }
}

class ValueAdjusterView(context:Context,colors:PocketColors,label:String,private val range:ValueRange,initial:Float,
    private val format:(Float)->String,private val changed:(Float)->Unit) : LinearLayout(context) {
    private val value=TextView(context).apply{textSize=13f;setTextColor(colors.primaryText)}
    private val seek=SeekBar(context).apply{
        max=range.steps;progress=range.index(initial);contentDescription=label;minimumHeight=dp(48);Styler.makeFocusable(this)
        progressTintList=android.content.res.ColorStateList.valueOf(colors.accent)
        thumbTintList=android.content.res.ColorStateList.valueOf(colors.accent)
        progressBackgroundTintList=android.content.res.ColorStateList.valueOf(colors.cardSurfacePressed)
        ViewCompat.setStateDescription(this,format(range.at(progress)))
    }
    init {
        orientation=VERTICAL;setPadding(dp(4),dp(10),dp(4),dp(6));addView(value)
        val row=LinearLayout(context).apply{gravity=Gravity.CENTER_VERTICAL}
        fun stepButton(text:String,delta:Int)=TextView(context).apply {
            this.text=text;textSize=24f;gravity=Gravity.CENTER;contentDescription="$label ${if(delta<0) "decrease" else "increase"}"
            DetailStyler.action(this,colors);activateOnTap{seek.progress=(seek.progress+delta).coerceIn(0,seek.max)}
        }
        row.addView(stepButton("−",-1),LayoutParams(dp(48),dp(48)))
        row.addView(seek,LayoutParams(0,dp(48),1f));row.addView(stepButton("+",1),LayoutParams(dp(48),dp(48)))
        addView(row);value.text="$label · ${format(range.at(seek.progress))}"
        seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar:SeekBar)=Unit
            override fun onStopTrackingTouch(bar:SeekBar)=Unit
            override fun onProgressChanged(bar:SeekBar,index:Int,fromUser:Boolean) {
                val v=range.at(index);value.text="$label · ${format(v)}";ViewCompat.setStateDescription(seek,format(v));changed(v)
            }
        })
    }
    fun onDirection(direction:Direction):Boolean {
        if(!seek.hasFocus() || direction !in listOf(Direction.LEFT,Direction.RIGHT))return false
        seek.progress=(seek.progress+if(direction==Direction.LEFT)-1 else 1).coerceIn(0,seek.max);return true
    }
    private fun dp(n:Int)=Styler.dpInt(context,n.toFloat())
}

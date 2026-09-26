package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PosterBrowsingLayoutTest {
    @Test fun nestedShelvesRevealTheWholeFocusedCardWhenMovingDownAndBackUp() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun dp(value:Int)=Styler.dpInt(activity,value.toFloat())
        lateinit var shelves: androidx.recyclerview.widget.RecyclerView
        class Holder(view:View):androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
        try {
            ins.runOnMainSync {
                shelves=androidx.recyclerview.widget.RecyclerView(activity).apply {
                    layoutManager=androidx.recyclerview.widget.LinearLayoutManager(activity)
                    setItemViewCacheSize(4); setPadding(0,dp(8),0,dp(12))
                    adapter=object:androidx.recyclerview.widget.RecyclerView.Adapter<Holder>() {
                        override fun getItemCount()=4
                        override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int):Holder {
                            val strip=androidx.recyclerview.widget.RecyclerView(activity).apply {
                                layoutManager=androidx.recyclerview.widget.LinearLayoutManager(activity,androidx.recyclerview.widget.RecyclerView.HORIZONTAL,false)
                                isFocusable=false
                                setPadding(dp(12),dp(12),dp(12),dp(12))
                                adapter=object:androidx.recyclerview.widget.RecyclerView.Adapter<Holder>() {
                                    override fun getItemCount()=5
                                    override fun onCreateViewHolder(parent:android.view.ViewGroup,type:Int)=Holder(PosterCardView(activity,Theme.colors(activity),150f).apply {
                                        layoutParams=androidx.recyclerview.widget.RecyclerView.LayoutParams(dp(104),-2).apply {setMargins(dp(8),dp(8),dp(8),dp(8))}
                                        (getChildAt(1) as TextView).text="A title with two lines"
                                        FocusDecorator.attach(this,{true})
                                    })
                                    override fun onBindViewHolder(holder:Holder,position:Int)=Unit
                                }
                            }
                            return Holder(strip.apply {layoutParams=androidx.recyclerview.widget.RecyclerView.LayoutParams(-1,-2)})
                        }
                        override fun onBindViewHolder(holder:Holder,position:Int)=Unit
                    }
                }
                activity.setContentView(FrameLayout(activity).apply {addView(shelves,FrameLayout.LayoutParams(dp(720),dp(280)))})
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val strip=shelves.findViewHolderForAdapterPosition(0)!!.itemView as androidx.recyclerview.widget.RecyclerView
                strip.findViewHolderForAdapterPosition(0)!!.itemView.requestFocus()
            }
            ins.waitForIdleSync()
            for(key in listOf(20,20,19,19,20,19)) {
                ins.sendKeyDownUpSync(key); ins.waitForIdleSync(); android.os.SystemClock.sleep(250)
                ins.runOnMainSync {
                    val card=activity.currentFocus as? PosterCardView ?: error("Focus left shelves")
                    val bounds=Rect(0,0,card.width,card.height)
                    shelves.offsetDescendantRectToMyCoords(card,bounds)
                    assertTrue("Focused poster clipped above viewport: $bounds",bounds.top>=shelves.paddingTop)
                    assertTrue("Focused caption clipped below viewport: $bounds",bounds.bottom<=shelves.height-shelves.paddingBottom)
                }
            }
        } finally {ins.runOnMainSync {activity.finish()}}
    }

    @Test fun sparseRowsStayCompactAndDirectionalFocusKeepsCaptionsVisible() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun dp(value:Int)=Styler.dpInt(activity,value.toFloat())
        val cards=mutableListOf<PosterCardView>()
        lateinit var scroll:ScrollView
        lateinit var grid:PosterGridLayout
        try {
            var fullWidth=0
            for(count in listOf(14,3,1,14)) {
                ins.runOnMainSync {
                    cards.clear()
                    grid=PosterGridLayout(activity).apply { setPadding(dp(8),dp(12),dp(8),dp(16)) }
                    repeat(count) { index ->
                        val card=PosterCardView(activity,Theme.colors(activity),150f)
                        (card.getChildAt(1) as TextView).text="A long movie title number ${index+1}"
                        FocusDecorator.attach(card,{true})
                        grid.addView(card,GridLayout.LayoutParams().apply {
                            width=0;height=-2;setMargins(dp(8),dp(8),dp(8),dp(8))
                        })
                        cards+=card
                    }
                    scroll=ScrollView(activity).apply {isFocusable=false;addView(grid);isSmoothScrollingEnabled=false}
                    activity.setContentView(FrameLayout(activity).apply {
                        addView(scroll,FrameLayout.LayoutParams(dp(720),dp(270)))
                    })
                }
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    if(fullWidth==0) fullWidth=cards.first().width
                    assertEquals("Sparse rows must not stretch posters",fullWidth,cards.first().width)
                    assertTrue("The whole card must fit the content viewport",cards.first().height<scroll.height)
                    cards.first().requestFocus()
                }
                ins.waitForIdleSync()
            }
            repeat(3) {
                for(key in listOf(KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_UP)) {
                    ins.sendKeyDownUpSync(key)
                    ins.waitForIdleSync()
                    android.os.SystemClock.sleep(150)
                    ins.runOnMainSync {
                        val focused=activity.currentFocus as? PosterCardView ?: error("Focus left the poster grid")
                        val caption=focused.getChildAt(1) as TextView
                        val visible=Rect()
                        assertTrue(caption.getGlobalVisibleRect(visible))
                        assertTrue("Caption was clipped after vertical navigation: $visible / ${caption.height}",visible.height()>=caption.height)
                        val art=focused.getChildAt(0)
                        assertTrue("Artwork must inherit the card focus ring",art.drawableState.contains(android.R.attr.state_focused))
                        assertNotNull(art.foreground)
                    }
                }
            }
        } finally { ins.runOnMainSync {activity.finish()} }
    }
}

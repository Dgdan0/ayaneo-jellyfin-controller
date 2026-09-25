package com.pocketds.hub.ui

import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.R
import com.pocketds.hub.nav.SectionRailItem
import com.pocketds.hub.nav.SectionRailView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SectionRailTest {
    @Test fun eightDestinationsRetainTouchTargetsAndLastItemCanBeSelected() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val ctx=i.targetContext
            val rail=SectionRailView(ctx,Theme.colors(ctx))
            rail.setSections((1..8).map{SectionRailItem("Section $it",R.drawable.ic_launcher_foreground)})
            rail.setBadge(6,120)
            rail.measure(View.MeasureSpec.makeMeasureSpec(Styler.dpInt(ctx,68f),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(Styler.dpInt(ctx,360f),View.MeasureSpec.EXACTLY))
            rail.layout(0,0,rail.measuredWidth,rail.measuredHeight)
            fun all(v:View):List<View> = listOf(v)+(v as? ViewGroup)?.let {g->(0 until g.childCount).flatMap{all(g.getChildAt(it))}}.orEmpty()
            val rows=all(rail).filter{it.isClickable && it.contentDescription?.startsWith("Section ")==true}
            assertEquals(8,rows.size);assertTrue(rows.all{it.height>=Styler.dpInt(ctx,48f)})
            var selected=-1;rail.onSelect={selected=it};rows.last().performClick();assertEquals(7,selected)
        }
    }
}

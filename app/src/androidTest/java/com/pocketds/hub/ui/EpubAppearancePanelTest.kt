package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubAppearancePanelTest {
    @Test fun appearanceSavesOnChangeAndClosingNeedsNoDone() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            val panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
            activity.setContentView(panel)
            var saved=EpubReaderPreferences();var closed=false
            panel.show(saved,{saved=it},{closed=true})
            fun find(v:View,label:String):View? = if(v.contentDescription?.toString()?.startsWith(label)==true || (v is android.widget.TextView && v.text==label)) v else (v as? ViewGroup)?.let { g->(0 until g.childCount).firstNotNullOfOrNull {find(g.getChildAt(it),label)} }
            find(panel,"Theme")!!.performClick()
            find(panel,"Dark page colour")!!.performClick()
            assertTrue(panel.isOpen);assertEquals(EpubTheme.DARK,saved.theme)
            assertNull(find(panel,"Save reading appearance"))
            find(panel,"Close panel")!!.performClick()
            assertFalse(panel.isOpen);assertTrue(closed)
            panel.show(saved,{saved=it},{})
            find(panel,"Page")!!.performClick()
            find(panel,"Continuous scrolling")!!.performClick()
            find(panel,"Two columns")!!.performClick()
            assertFalse(saved.scroll);assertEquals(EpubColumns.TWO,saved.columns)
        }} finally {i.runOnMainSync {activity.finish()}}
    }
}

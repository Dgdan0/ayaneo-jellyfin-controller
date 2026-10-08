package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
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
            find(panel,"Themes")!!.performClick()
            find(panel,"Night")!!.performClick()
            assertTrue(panel.isOpen);assertEquals(EpubTheme.DARK,saved.theme)
            assertNull(find(panel,"Save reading appearance"))
            find(panel,"Close panel")!!.performClick()
            assertFalse(panel.isOpen);assertTrue(closed)
            panel.show(saved,{saved=it},{})
            find(panel,"Layout")!!.performClick()
            find(panel,"Continuous scrolling")!!.performClick()
            find(panel,"Two pages")!!.performClick()
            assertFalse(saved.scroll);assertEquals(EpubColumns.TWO,saved.columns)
        }} finally {i.runOnMainSync {activity.finish()}}
    }

    /** Kindle's typography is the default (#42, Part 3), and the sheet can turn each part off and the book's own look back on. */
    @Test fun typographyDefaultsToTheReadersOwnAndPublisherStylingIsTheWayBack() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            val panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
            activity.setContentView(panel)
            var saved=EpubReaderPreferences()
            assertFalse(saved.publisherStyles);assertEquals("justify",saved.textAlignment);assertEquals(1.5f,saved.lineHeight,0f);assertTrue(saved.hyphenation)
            panel.show(saved,{saved=it},{})
            fun find(v:View,label:String):View? = if(v.contentDescription?.toString()?.startsWith(label)==true || (v is android.widget.TextView && v.text==label)) v else (v as? ViewGroup)?.let { g->(0 until g.childCount).firstNotNullOfOrNull {find(g.getChildAt(it),label)} }
            find(panel,"Layout")!!.performClick()
            find(panel,"Hyphenation")!!.performClick()
            assertFalse(saved.hyphenation)
            find(panel,"Hyphenation")!!.performClick()
            assertTrue("turning hyphenation on needs the reader's typography",saved.hyphenation && !saved.publisherStyles)
            find(panel,"Publisher styling")!!.performClick()
            assertTrue(saved.publisherStyles)
            // The book's own face does not take the book's whole look with it, nor undo it.
            find(panel,"Font")!!.performClick()
            find(panel,"Publisher")!!.performClick()
            assertTrue(saved.publisherStyles)
            find(panel,"Serif")!!.performClick()
            assertFalse("a face of the reader's own needs its typography",saved.publisherStyles)
            find(panel,"Publisher")!!.performClick()
            assertFalse("the book's face leaves the reader's typography as it is",saved.publisherStyles)
        }} finally {i.runOnMainSync {activity.finish()}}
    }

    @Test fun pageInfoPartChangesTheCornersAndHandsThemOn() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { i.runOnMainSync {
            val panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
            activity.setContentView(panel)
            var info=PageInfoChoice()
            panel.show(EpubReaderPreferences(),{},{},info,{info=it})
            fun find(v:View,label:String):View? = if(v.contentDescription?.toString()?.startsWith(label)==true || (v is android.widget.TextView && v.text==label)) v else (v as? ViewGroup)?.let { g->(0 until g.childCount).firstNotNullOfOrNull {find(g.getChildAt(it),label)} }
            find(panel,"Page info")!!.performClick()
            find(panel,"Clock")!!.performClick()
            assertFalse(info.clock)
            find(panel,"Time left in chapter")!!.performClick()
            assertEquals(PageInfoCorner.TIME_IN_CHAPTER,info.corner)
            find(panel,"None")!!.performClick()
            assertEquals(PageInfoCorner.NONE,info.corner)
            find(panel,"Percentage")!!.performClick()
            assertFalse(info.percentage)
            assertFalse("with every corner off the page needs no strips",info.topStrip||info.bottomStrip)
        }} finally {i.runOnMainSync {activity.finish()}}
    }

    /** A device that once changed its look never saw the new default: one row brings the text's style back, and the pad reaches it (#42, Part 3). */
    @Test fun resetTextStyleIsOnePressFromThePadAndMovesOnlyTheTextStyle() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val old=EpubReaderPreferences(theme=EpubTheme.DARK,fontFamily="serif",fontScale=1.4f,lineHeight=1.1f,pageMargins=1.7f,
            columns=EpubColumns.TWO,publisherStyles=true,textAlignment="start",hyphenation=false)
        var saved=old
        lateinit var panel:EpubAppearancePanel
        fun find(v:View,label:String):View? = if(v.contentDescription?.toString()?.startsWith(label)==true || (v is android.widget.TextView && v.text==label)) v else (v as? ViewGroup)?.let { g->(0 until g.childCount).firstNotNullOfOrNull {find(g.getChildAt(it),label)} }
        try {
            i.runOnMainSync {
                panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
                activity.setContentView(panel)
                panel.show(old,{saved=it},{})
                find(panel,"Layout")!!.performClick()
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                val row=find(panel,"Reset text style")!!
                assertTrue("says what it does: ${row.contentDescription}",row.contentDescription.toString().contains("Justified, hyphenated, 1.5 spacing"))
                // Down the pad from the first row until the focus is on it: it is a row like any other, reached by D-pad.
                var presses=0
                while(panel.findFocus()?.tag!="reset-text-style" && presses<40) {panel.onPad(PadAction.Step(Direction.DOWN));presses++}
                assertEquals("reset-text-style",panel.findFocus()?.tag)
                panel.onPad(PadAction.Activate)
                val defaults=EpubReaderPreferences()
                assertEquals(defaults.publisherStyles,saved.publisherStyles);assertEquals(defaults.textAlignment,saved.textAlignment)
                assertEquals(defaults.hyphenation,saved.hyphenation);assertEquals(defaults.lineHeight,saved.lineHeight,0f)
                assertEquals(old.copy(publisherStyles=defaults.publisherStyles,textAlignment=defaults.textAlignment,
                    hyphenation=defaults.hyphenation,lineHeight=defaults.lineHeight),saved)
                // The rows above show the change at once.
                assertTrue(find(panel,"Justified text")!!.contentDescription.toString().contains("On"))
                assertTrue(find(panel,"Publisher styling")!!.contentDescription.toString().contains("Off"))
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }
}

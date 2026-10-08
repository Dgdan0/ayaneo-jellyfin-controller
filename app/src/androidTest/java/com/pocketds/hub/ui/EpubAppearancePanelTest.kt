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
            find(panel,"Dim")!!.performClick()
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
            find(panel,"Original")!!.performClick()
            assertTrue(saved.publisherStyles)
            find(panel,"Charis")!!.performClick()
            assertFalse("a face of the reader's own needs its typography",saved.publisherStyles)
            assertEquals("charis",saved.fontFamily)
            find(panel,"Original")!!.performClick()
            assertFalse("the book's face leaves the reader's typography as it is",saved.publisherStyles)
            assertEquals("publisher",saved.fontFamily)
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
            assertTrue("the title keeps the top clear",info.topStrip)
            find(panel,"Book title")!!.performClick()
            assertFalse(info.title)
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
                assertTrue("says what it does: ${row.contentDescription}",row.contentDescription.toString().contains("Literata, justified, hyphenated, 1.5 spacing"))
                // Down the pad from the first row until the focus is on it: it is a row like any other, reached by D-pad.
                var presses=0
                while(panel.findFocus()?.tag!="reset-text-style" && presses<40) {panel.onPad(PadAction.Step(Direction.DOWN));presses++}
                assertEquals("reset-text-style",panel.findFocus()?.tag)
                panel.onPad(PadAction.Activate)
                val defaults=EpubReaderPreferences()
                assertEquals(defaults.publisherStyles,saved.publisherStyles);assertEquals(defaults.textAlignment,saved.textAlignment)
                assertEquals(defaults.hyphenation,saved.hyphenation);assertEquals(defaults.lineHeight,saved.lineHeight,0f)
                assertEquals(defaults.fontFamily,saved.fontFamily)
                assertEquals(old.copy(publisherStyles=defaults.publisherStyles,textAlignment=defaults.textAlignment,
                    hyphenation=defaults.hyphenation,lineHeight=defaults.lineHeight,fontFamily=defaults.fontFamily),saved)
                // The rows above show the change at once.
                assertTrue(find(panel,"Justified text")!!.contentDescription.toString().contains("On"))
                assertTrue(find(panel,"Publisher styling")!!.contentDescription.toString().contains("Off"))
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }

    /** The slider of the adjuster called [label]: not its "decrease" and "increase" buttons, whose names start the same. */
    private fun seekOf(v:View,label:String):android.widget.SeekBar? = (v as? android.widget.SeekBar)?.takeIf {it.contentDescription?.toString()==label}
        ?: (v as? ViewGroup)?.let {g->(0 until g.childCount).firstNotNullOfOrNull {seekOf(g.getChildAt(it),label)}}

    private fun finds(v:View,label:String):View? = if(v.contentDescription?.toString()?.startsWith(label)==true || (v is android.widget.TextView && v.text==label)) v else (v as? ViewGroup)?.let { g->(0 until g.childCount).firstNotNullOfOrNull {finds(g.getChildAt(it),label)} }

    /** Kindle's size slider (#47): 70 to 200% in steps of 10, a step for left and right on the pad. */
    @Test fun sizeSliderStepsWithTheD_padFromSeventyToTwoHundredPercent() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var saved=EpubReaderPreferences()
        lateinit var panel:EpubAppearancePanel
        try {
            i.runOnMainSync {
                panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
                activity.setContentView(panel)
                panel.show(saved,{saved=it},{})
                assertEquals(1.3f,saved.fontScale,0f)
                val seek=seekOf(panel,"Size")!!
                assertEquals("14 marks, 70 to 200 in tens",13,seek.max)
                assertNotNull(seek.tickMark)
                assertTrue(finds(panel,"Size · 130%")!=null)
                seek.requestFocus()
                panel.onPad(PadAction.Step(Direction.RIGHT))
                assertEquals(1.4f,saved.fontScale,0.001f)
                repeat(3){panel.onPad(PadAction.Step(Direction.LEFT))}
                assertEquals(1.1f,saved.fontScale,0.001f)
                repeat(20){panel.onPad(PadAction.Step(Direction.LEFT))}
                assertEquals("the smallest is 70%",0.7f,saved.fontScale,0.001f)
                repeat(30){panel.onPad(PadAction.Step(Direction.RIGHT))}
                assertEquals("the largest is 200%",2f,saved.fontScale,0.001f)
                assertTrue(finds(panel,"Size · 200%")!=null)
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }

    /** Line spacing and the margins are a page of their own, opened from the Font tab and left with B (#47). */
    @Test fun spacingIsItsOwnPageAndBGoesBackToFont() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var saved=EpubReaderPreferences()
        lateinit var panel:EpubAppearancePanel
        try {
            i.runOnMainSync {
                panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
                activity.setContentView(panel)
                panel.show(saved,{saved=it},{})
                val row=finds(panel,"Spacing")!!
                assertTrue("says where it stands: ${row.contentDescription}",row.contentDescription.toString().contains("1.5 · Balanced margins"))
                assertNull("not on the Font tab",finds(panel,"Wide"))
                row.performClick()
                assertNotNull(finds(panel,"Wide"));assertNotNull(finds(panel,"Narrow"))
                assertEquals(listOf("Tight","Relaxed","Open"),listOf("Tight","Relaxed","Open").filter {finds(panel,it)!=null})
                finds(panel,"Tight")!!.performClick()
                assertEquals(1.3f,saved.lineHeight,0f)
                finds(panel,"Narrow")!!.performClick()
                assertEquals(PageGeometry.Margin.NARROW.stored,saved.pageMargins,0f)
                // B goes back to the Font tab, the sheet still open.
                assertTrue(panel.onPad(PadAction.Back))
                assertTrue(panel.isOpen)
                assertNotNull(finds(panel,"Literata"))
                assertTrue(finds(panel,"Spacing")!!.contentDescription.toString().contains("1.3 · Narrow margins"))
                // The Layout tab keeps the columns, and no longer the margins.
                finds(panel,"Layout")!!.performClick()
                assertNotNull(finds(panel,"Two pages"));assertNull(finds(panel,"Wide"));assertNotNull(finds(panel,"Reset text style"))
                // B on a tab closes the sheet, as it always did.
                assertTrue(panel.onPad(PadAction.Back))
                assertFalse(panel.isOpen)
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }

    /** Brightness at the foot of every tab, moved from Comfort (#47). */
    @Test fun brightnessIsFixedAtTheFootOfEveryTab() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var brightness=0.8f
        lateinit var panel:EpubAppearancePanel
        try {
            i.runOnMainSync {
                panel=EpubAppearancePanel(activity,Theme.colors(activity)){true}
                activity.setContentView(panel)
                panel.show(EpubReaderPreferences(),{},{},brightness=brightness,onBrightness={brightness=it})
                for(tab in listOf("Font","Layout","Themes","Page info")) {
                    finds(panel,tab)!!.performClick()
                    val seek=seekOf(panel,"Brightness")
                    assertNotNull("brightness on $tab",seek)
                    assertTrue("$tab: it is at the foot, outside what scrolls",generateSequence(seek as View){it.parent as? View}.any {it===panel.footer})
                }
                val seek=seekOf(panel,"Brightness")!!
                assertTrue(finds(panel,"Brightness · 80%")!=null)
                seek.requestFocus()
                panel.onPad(PadAction.Step(Direction.LEFT))
                assertEquals(0.75f,brightness,0.001f)
                panel.onPad(PadAction.Step(Direction.RIGHT));panel.onPad(PadAction.Step(Direction.RIGHT))
                assertEquals(0.85f,brightness,0.001f)
            }
            // Without a brightness to offer (a caller that has none) there is no slider.
            i.runOnMainSync {
                panel.show(EpubReaderPreferences(),{},{})
                assertNull(seekOf(panel,"Brightness"))
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }
}

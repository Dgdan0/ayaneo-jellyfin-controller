package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SidePanelTest {
    @Test fun panelTrapsFocusClosesWithBackAndRestoresOpener() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var panel: ChoiceOverlay
        lateinit var opener: TextView
        var previousAccessibility=0
        try {
            i.runOnMainSync {
                val root=FrameLayout(activity)
                opener=TextView(activity).apply {text="Open";Styler.makeFocusable(this)}
                previousAccessibility=opener.importantForAccessibility
                root.addView(opener,FrameLayout.LayoutParams(200,120))
                panel=ChoiceOverlay(activity,Theme.colors(activity),{true},sidePanel=true)
                root.addView(panel,FrameLayout.LayoutParams(-1,-1));activity.setContentView(root)
                opener.requestFocus()
                panel.show("Audio","",listOf(ChoiceOverlay.Choice("en","English",selected=true),ChoiceOverlay.Choice("he","Hebrew"))) {}
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertTrue(panel.hasFocus())
                assertTrue(panel.rows[0].isSelected)
                repeat(10) {panel.onPad(PadAction.Step(Direction.LEFT))}
                assertTrue(panel.hasFocus());assertFalse(opener.hasFocus())
                panel.onPad(PadAction.Back)
                assertFalse(panel.isOpen);assertTrue(opener.hasFocus())
                assertEquals(previousAccessibility,opener.importantForAccessibility)
                var cancelled=false
                panel.show("Quality","",listOf(ChoiceOverlay.Choice("original","Original")),onCancel={cancelled=true}) {}
                assertTrue(SidePanelView.dismissTopIn(panel.parent as View))
                assertTrue(cancelled);assertFalse(panel.isOpen)
                assertFalse(SidePanelView.dismissTopIn(panel.parent as View))
            }
        } finally {i.runOnMainSync {activity.finish()}}
    }
}

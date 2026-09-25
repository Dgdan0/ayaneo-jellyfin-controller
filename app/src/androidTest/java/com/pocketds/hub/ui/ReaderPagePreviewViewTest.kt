package com.pocketds.hub.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.reader.ReaderPagePreviewController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPagePreviewViewTest {
    @Test fun menuFitsPageAndClosingRestoresWithoutResizingTheReaderViewport() {
        val i = InstrumentationRegistry.getInstrumentation()
        val activity = i.startActivitySync(Intent(i.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var root: FrameLayout
        lateinit var page: View
        lateinit var top: View
        lateinit var bottom: View
        lateinit var panel: ChoiceOverlay
        lateinit var preview: ReaderPagePreviewController
        try {
            i.runOnMainSync {
                root = FrameLayout(activity)
                page = View(activity)
                root.addView(page, FrameLayout.LayoutParams(-1,-1))
                top = View(activity); bottom = View(activity)
                root.addView(top, FrameLayout.LayoutParams(-1,80,Gravity.TOP))
                root.addView(bottom, FrameLayout.LayoutParams(-1,80,Gravity.BOTTOM))
                panel = ChoiceOverlay(activity, Theme.colors(activity), { true }, sidePanel=true)
                root.addView(panel,FrameLayout.LayoutParams(-1,-1))
                preview = ReaderPagePreviewController(root,page,top,bottom,listOf(panel),animate=false)
                activity.setContentView(root)
                preview.setControlsVisible(false)
            }
            i.waitForIdleSync()
            var width=0; var height=0
            i.runOnMainSync {
                width=page.width; height=page.height
                assertTrue(width>0 && height>0)
                page.scrollTo(0,37)
                preview.setControlsVisible(true)
                assertTrue(page.scaleX<1f)
                assertTrue(page.translationY>=80)
                assertTrue(page.translationY+height*page.scaleY<=root.height-80+.01f)
                panel.open("Reading appearance")
            }
            i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals(width,page.width); assertEquals(height,page.height)
                assertEquals(37,page.scrollY)
                assertEquals(View.GONE,top.visibility)
                assertEquals(View.GONE,bottom.visibility)
                assertTrue(page.translationX+width*page.scaleX<=panel.panelStartX+.01f)
                // Successful picks use dismiss(), not the Back/cancel callback.
                panel.dismiss()
                assertEquals(View.VISIBLE,top.visibility)
                preview.setControlsVisible(false)
                assertEquals(1f,page.scaleX,0f);assertEquals(1f,page.scaleY,0f)
                assertEquals(0f,page.translationX,0f);assertEquals(0f,page.translationY,0f)
                assertEquals(width,page.width);assertEquals(height,page.height)
                assertEquals(37,page.scrollY)
                panel.open("Contents");panel.cancel()
                assertEquals(1f,page.scaleX,0f)
            }
        } finally {
            i.runOnMainSync { runCatching { preview.dispose() }; activity.finish() }
        }
    }
}

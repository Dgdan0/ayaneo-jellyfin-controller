package com.pocketds.hub.ui

import android.view.View
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowseHeaderControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun selected_pill_gains_a_visible_second_focus_cue() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val toggle = ContentModeToggleView(activity, Theme.colors(activity))
            activity.setContentView(toggle)
            toggle.select(ContentMode.BOOKS)
            toggle.measure(View.MeasureSpec.makeMeasureSpec(220, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(60, View.MeasureSpec.EXACTLY))
            toggle.layout(0, 0, toggle.measuredWidth, toggle.measuredHeight)
            toggle.button(ContentMode.MEDIA).clearFocus()
            toggle.button(ContentMode.BOOKS).clearFocus()
            fun render(): Bitmap = Bitmap.createBitmap(toggle.width, toggle.height, Bitmap.Config.ARGB_8888).also {
                toggle.draw(Canvas(it))
            }
            val selected = render()
            assertTrue(toggle.focus(ContentMode.BOOKS))
            val focused = render()
            assertTrue(toggle.button(ContentMode.BOOKS).isSelected)
            assertFalse("Focused and selected must look different from selected alone", selected.sameAs(focused))
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun compactDomainSwitchKeepsAccessibleTargets() = instrumentation.runOnMainSync {
        val context = instrumentation.targetContext
        val toggle = ContentModeToggleView(context, Theme.colors(context))
        toggle.select(ContentMode.BOOKS)
        val width = Styler.dpInt(context, 400f)
        toggle.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertTrue(toggle.measuredWidth <= Styler.dpInt(context, 210f))
        assertTrue(toggle.getChildAt(0).minimumHeight >= Styler.dpInt(context, 48f))
        assertTrue(toggle.getChildAt(1).minimumHeight >= Styler.dpInt(context, 48f))
        assertTrue(toggle.getChildAt(1).isSelected)
    }
}

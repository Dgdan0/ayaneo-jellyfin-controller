package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectedFocusStyleTest {
    @Test fun selected_and_focused_are_distinct_states_in_both_themes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val base = Theme.colors(activity)
            for (colors in listOf(base.copy(
                background = 0xff15151a.toInt(), cardSurface = 0xff25252b.toInt(),
                primaryText = 0xfff1f1f3.toInt(), focusFill = 0xff15302e.toInt(),
                focusRing = 0xff2be0ce.toInt(), accent = 0xff2be0ce.toInt()
            ), base.copy(
                background = 0xfff1f1f4.toInt(), primaryText = 0xff1b1b1f.toInt(),
                cardSurface = 0xffffffff.toInt(),
                focusFill = 0xffe2f6f4.toInt(), focusRing = 0xff087d73.toInt(),
                accent = 0xff087d73.toInt()
            ))) {
                val tab = TextView(activity).apply {
                    text = "Downloaded"
                    Styler.makeFocusable(this)
                    background = Styler.selectionBackground(activity, colors, selected = true,
                        baseFill = colors.cardSurface, selectedFill = colors.focusFill,
                        selectedStrokeDp = 1f)
                }
                activity.setContentView(tab)
                tab.measure(View.MeasureSpec.makeMeasureSpec(160, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
                tab.layout(0, 0, 160, 48)
                fun image(): Bitmap = Bitmap.createBitmap(160, 48, Bitmap.Config.ARGB_8888).also {
                    tab.draw(Canvas(it))
                }
                tab.clearFocus()
                val selectedOnly = image()
                assertTrue(tab.requestFocus())
                val selectedAndFocused = image()
                assertFalse(selectedOnly.sameAs(selectedAndFocused))
                tab.background = Styler.selectionBackground(activity, colors, selected = false,
                    baseFill = colors.cardSurface, selectedFill = colors.focusFill,
                    selectedStrokeDp = 1f)
                val focusedButNotSelected = image()
                assertFalse(selectedAndFocused.sameAs(focusedButNotSelected))
            }
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}

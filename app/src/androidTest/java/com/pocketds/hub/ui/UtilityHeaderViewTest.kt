package com.pocketds.hub.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.pocketds.hub.state.ContentMode
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UtilityHeaderViewTest {
    @Test fun selected_mode_remains_distinct_when_focus_moves_across_the_header() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val context = activity
            val bar = UtilityHeaderView(context, Theme.colors(context))
            activity.setContentView(bar)
            val chosen = mutableListOf<ContentMode>()
            bar.onModeSelected = { chosen.add(it) }
            bar.setMode(ContentMode.BOOKS)
            assertTrue(bar.modeButton(ContentMode.BOOKS).isSelected)
            assertFalse(bar.modeButton(ContentMode.MEDIA).isSelected)
            assertTrue(bar.focusFirst())
            assertTrue(bar.modeButton(ContentMode.BOOKS).isFocused)
            assertTrue(bar.moveHorizontal(-1))
            assertTrue(bar.modeButton(ContentMode.MEDIA).isFocused)
            assertTrue(bar.modeButton(ContentMode.BOOKS).isSelected)
            bar.modeButton(ContentMode.MEDIA).performClick()
            assertEquals(listOf(ContentMode.MEDIA), chosen)
            bar.setMode(null)
            assertEquals(View.GONE, bar.getChildAt(1).visibility)
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun notifications_services_and_settings_keep_their_section_destinations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bar = UtilityHeaderView(context, Theme.colors(context))
        val selected = mutableListOf<Int>()
        bar.onSelect = { selected.add(it) }
        bar.buttonForSection(5).performClick()
        bar.buttonForSection(6).performClick()
        bar.buttonForSection(7).performClick()
        assertEquals(listOf(5, 6, 7), selected)
        bar.setBadge(3)
        assertTrue(bar.buttonForSection(5).contentDescription.toString().contains("3 unread"))
    }
}

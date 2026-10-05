package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.TopBarView
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopBarViewTest {
    private val tabs = listOf("Home", "Discover", "Library", "Downloads", "Activity")

    @Test fun tabs_mode_and_utility_icons_are_walked_in_order_and_keep_their_selection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val bar = TopBarView(activity, Theme.colors(activity), { true }, tabs)
            activity.setContentView(bar)
            val chosen = mutableListOf<ContentMode>()
            val sections = mutableListOf<Int>()
            bar.onModeSelected = { chosen.add(it) }
            bar.onSelect = { sections.add(it) }
            bar.setCurrent(2)
            bar.setMode(ContentMode.BOOKS)
            assertTrue(bar.buttonForSection(2).isSelected)
            assertTrue(bar.modeButton(ContentMode.BOOKS).isSelected)
            // Up from the content lands on the tab you are on.
            assertTrue(bar.focusFirst())
            assertTrue(bar.buttonForSection(2).isFocused)
            assertTrue(bar.moveHorizontal(1))
            assertTrue(bar.buttonForSection(3).isFocused)
            assertTrue(bar.buttonForSection(2).isSelected)
            repeat(2) { bar.moveHorizontal(1) }
            assertTrue(bar.modeButton(ContentMode.MEDIA).isFocused)
            bar.modeButton(ContentMode.MEDIA).performClick()
            assertEquals(listOf(ContentMode.MEDIA), chosen)
            bar.buttonForSection(4).performClick()
            assertEquals(listOf(4), sections)
            bar.setMode(null)
            assertEquals(View.GONE, (bar.modeButton(ContentMode.MEDIA).parent as View).visibility)
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    /** #16, A1: the audiobook playing sits after the tabs; A opens it, its symbol plays and pauses. */
    @Test fun the_mini_player_names_the_book_playing_and_is_walked_after_the_tabs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try { instrumentation.runOnMainSync {
            val bar = TopBarView(activity, Theme.colors(activity), { true }, tabs)
            activity.setContentView(bar)
            bar.setMode(ContentMode.BOOKS)
            bar.setCurrent(4)
            val mini = bar.miniPlayer
            assertEquals("Nothing on the player: no mini player", View.GONE, mini.visibility)
            var opened = 0
            var toggled = 0
            mini.onOpen = { opened++ }
            mini.onToggle = { toggled++ }
            mini.show("The Last Observatory", "4h 10m left", isPlaying = true)
            assertEquals(View.VISIBLE, mini.visibility)
            assertEquals("Pause", mini.toggleLabel)
            assertEquals("Open The Last Observatory, playing", mini.contentDescription)
            // After the last tab, before Media and Books.
            assertTrue(bar.focusFirst())
            assertTrue(bar.moveHorizontal(1))
            assertTrue(mini.isFocused)
            assertTrue(bar.moveHorizontal(1))
            assertTrue(bar.modeButton(ContentMode.MEDIA).isFocused)
            mini.performClick()
            assertEquals(1, opened)
            mini.getChildAt(0).performClick()
            assertEquals(1, toggled)
            mini.show("The Last Observatory", "4h 10m left", isPlaying = false)
            assertEquals("Play", mini.toggleLabel)
            // Stopped: gone, and out of the walk.
            mini.show(null, "", isPlaying = false)
            assertEquals(View.GONE, mini.visibility)
            assertTrue(bar.focusFirst())
            assertTrue(bar.moveHorizontal(1))
            assertTrue(bar.modeButton(ContentMode.MEDIA).isFocused)
        }} finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun utility_icons_open_their_sections_and_a_utility_page_leaves_no_tab_selected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = TopBarView(context, Theme.colors(context), { true }, tabs)
            val selected = mutableListOf<Int>()
            bar.onSelect = { selected.add(it) }
            bar.buttonForSection(TopBarView.NOTIFICATIONS).performClick()
            bar.buttonForSection(TopBarView.SERVICES).performClick()
            bar.buttonForSection(TopBarView.SETTINGS).performClick()
            assertEquals(listOf(5, 6, 7), selected)
            bar.setBadge(3)
            assertTrue(bar.buttonForSection(5).contentDescription.toString().contains("3 unread"))
            bar.setCurrent(TopBarView.SETTINGS)
            assertTrue(bar.buttonForSection(TopBarView.SETTINGS).isSelected)
            assertFalse((0 until 5).any { bar.buttonForSection(it).isSelected })
        }
    }
}

package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.nav.ScreenHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The place Back returns to (#23), on a page of nothing but rows: the owner on its own. */
@RunWith(AndroidJUnit4::class)
class FocusPlaceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    /** Twelve rows in a scrolling page, each saying when it takes focus. */
    private class Rows : Screen {
        override val title = "Rows"
        val heard = mutableListOf<Int>()
        val rows = mutableListOf<View>()
        override fun onCreateView(host: ScreenHost, container: ViewGroup): View {
            val context = host.viewContext
            return FocusScrollView(context).apply {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    for (n in 1..12) addView(TextView(context).apply {
                        text = "Row $n"
                        Styler.makeFocusable(this)
                        setOnFocusChangeListener { _, focused -> if (focused) heard += n }
                        rows += this
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Styler.dpInt(context, 90f)))
                })
            }
        }
        override fun requestInitialFocus() = rows.first().requestFocus()
        override fun onShow() = Unit
        override fun onHide() = Unit
        override fun onDestroyView() = Unit
    }

    @Test fun backReturnsToTheRowLeftAndThePageHearsNothingWhileAway() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        val page = Rows()
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(page) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                // Scrolled well down the page: the clear would hand focus to a row near the top.
                assertTrue(page.rows[8].requestFocus())
                page.heard.clear()
                harness.push(PageHarness.Elsewhere())
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals("a page that is not in front takes no focus", emptyList<Int>(), page.heard)
                assertTrue(harness.back())
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue("Back lands on the row that was left", page.rows[8].isFocused)
                assertEquals("and on nothing first", listOf(9), page.heard)
            }
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
        }
    }

    @Test fun theTabsReturnToThePlaceAndAMoveSpendsIt() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        val page = Rows()
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(page) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val root = harness.viewOf(page)
                assertTrue(page.rows[4].requestFocus())
                // Up into the tabs: the host marks the place and focus leaves the page.
                FocusPlace.mark(root, page.rows[4])
                page.rows[4].clearFocus()
                assertTrue(FocusPlace.restore(root))
                assertTrue(page.rows[4].isFocused)
                assertEquals("restored once, the place is spent", null, FocusPlace.placeIn(root))
                assertTrue(!FocusPlace.restore(root))
            }
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
        }
    }
}

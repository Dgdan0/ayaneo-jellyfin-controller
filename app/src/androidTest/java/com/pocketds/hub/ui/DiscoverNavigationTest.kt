package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.*
import com.pocketds.hub.screens.discover.DiscoverScreen
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.settings.Look
import com.pocketds.hub.settings.LookSettings
import com.pocketds.hub.state.ContentMode
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiscoverNavigationTest {
    /** In each look (#11): Glass's rows are posters without captions, Classic's carry their titles. */
    @Test fun featureAndShelvesAreSeparateReversibleStopsIncludingRecycledRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val oldLook = LookSettings.get(context)
        try {
            for (look in listOf(Look.GLASS, Look.CLASSIC)) walk(look)
        } finally { LookSettings.set(context, oldLook) }
    }

    private fun walk(look: Look) {
        val ins = InstrumentationRegistry.getInstrumentation()
        LookSettings.set(ins.targetContext, look)
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ContentModeSettings.set(activity, ContentMode.MEDIA)
        val response = DiscoverResponse(rows = (0..5).map { row -> DiscoverRow(id="row$row", title="Shelf $row", items=(0..8).map { index ->
            SearchHit(media=MediaRef(key="item-$row-$index", title="Title $row $index", year=2024,
                poster=if(row==0 && index==0) "file:///missing-fixture.jpg" else ""))
        }) })
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, args ->
            when(method.name) { "discover" -> HubResult.Ok(response); "imageUrl" -> args!![0]; else -> error("Unexpected ${method.name}") }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            if(method.name=="getViewContext") activity else null
        } as ScreenHost
        val screen = DiscoverScreen(api) { true }
        lateinit var root: View
        fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun settle() { ins.waitForIdleSync(); android.os.SystemClock.sleep(180); ins.waitForIdleSync() }
        fun step(direction: Direction, title: String) {
            ins.runOnMainSync { assertTrue(screen.onPad(PadAction.Step(direction))) }
            settle()
            ins.runOnMainSync {
                val focused=activity.currentFocus
                assertTrue("Expected $title, got ${focused?.contentDescription}", focused?.contentDescription?.contains(title)==true)
                if(focused is PosterCardView) {
                    val row=generateSequence(focused.parent as? View) {it.parent as? View}.filterIsInstance<ShelfFocusRow>().first()
                    val heading=row.shelfHeadingView
                    val visible=android.graphics.Rect()
                    assertTrue("Shelf heading must stay visible",heading.getGlobalVisibleRect(visible))
                    assertEquals("Shelf heading must not be clipped",heading.height,visible.height())
                    // Classic shows the title under the poster; Glass's rows are the posters alone.
                    val shown=if(look==Look.GLASS) focused.getChildAt(0) else focused.getChildAt(1)
                    val part=if(look==Look.GLASS) "Poster" else "Title"
                    assertTrue("$part must be visible in $look",shown.getGlobalVisibleRect(visible))
                    assertTrue("$part must not be clipped in $look",visible.height() >= (shown.height * focused.scaleY).toInt())
                }
            }
        }
        try {
            ins.runOnMainSync {
                root=screen.onCreateView(host, FrameLayout(activity))
                activity.setContentView(FrameLayout(activity).apply {
                    addView(root,FrameLayout.LayoutParams(-1,Styler.dpInt(activity,360f)))
                })
                screen.onShow()
            }
            settle()
            ins.runOnMainSync {
                val feature=all(root).filterIsInstance<DiscoverFeatureCardView>().first { it.visibility==View.VISIBLE }
                assertNotNull("Feature ring must be drawn above artwork", feature.foreground)
                feature.requestFocus()
            }
            step(Direction.DOWN,"Title 0 1")
            ins.runOnMainSync { assertTrue(all(root).first { it.contentDescription?.toString()=="Title 0 3" }.requestFocus()) }
            step(Direction.DOWN,"Title 1 0")
            step(Direction.UP,"Title 0 3")
            ins.runOnMainSync { screen.onHide();root.clearFocus();screen.onShow();screen.requestInitialFocus() }
            settle()
            ins.runOnMainSync { assertEquals("Return must remember the title", "Title 0 3",activity.currentFocus?.contentDescription?.toString()) }
            ins.runOnMainSync { assertTrue(all(root).first { it.contentDescription?.toString()=="Title 0 1" }.requestFocus()) }
            step(Direction.DOWN,"Title 1 0")
            step(Direction.UP,"Title 0 1")
            step(Direction.UP,"Title 0 0")
            step(Direction.DOWN,"Title 0 1")
            step(Direction.DOWN,"Title 1 0")
            step(Direction.DOWN,"Title 2 0")
            step(Direction.DOWN,"Title 3 0")
            step(Direction.DOWN,"Title 4 0")
            step(Direction.DOWN,"Title 5 0")
            for(row in 4 downTo 1) step(Direction.UP,"Title $row 0")
            step(Direction.UP,"Title 0 1")
            step(Direction.UP,"Title 0 0")
        } finally { ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() } }
    }
}

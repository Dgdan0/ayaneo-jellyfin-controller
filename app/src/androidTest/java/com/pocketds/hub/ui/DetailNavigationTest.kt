package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.LibraryDetailScreen
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DetailNavigationTest {
    @Test fun selectedSeasonSurvivesReturnAndAReorderedRefresh() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var seasons = (1..4).map { LibraryItem(id = "season-$it", type = "season", title = "Season $it", seasonNumber = it) }
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, args ->
            when (method.name) {
                "libraryItem" -> HubResult.Ok(LibraryItemResponse(LibraryItem(id = "series", type = "series", title = "Fixture series", overview = "A synopsis.")))
                "librarySeasons" -> HubResult.Ok(LibrarySeasonsResponse("series", seasons))
                "seriesPlayTarget" -> HubResult.Ok(SeriesPlayTargetResponse(item = LibraryItem(id = "episode", type = "episode", title = "Next episode")))
                "librarySimilar" -> HubResult.Ok(com.pocketds.hub.model.LibraryItemsResponse())
                // The selected season's episodes load inline under the season blob.
                "libraryEpisodes" -> HubResult.Ok(com.pocketds.hub.model.LibraryEpisodesResponse())
                "imageUrl" -> args?.firstOrNull() as? String ?: ""
                else -> error("Unexpected fixture request: ${method.name}")
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            if (method.name == "getViewContext") activity else null
        } as ScreenHost
        val screen = LibraryDetailScreen(api, "series", "Fixture series", "series") { true }
        lateinit var root: View
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
        // A season is an option of the season blob: "Season 3", or "Season 3, selected".
        fun seasonThree() = descendants(root).first { view ->
            view.contentDescription?.toString()?.let { it == "Season 3" || it.startsWith("Season 3,") } == true
        }
        try {
            instrumentation.runOnMainSync {
                root = screen.onCreateView(host, FrameLayout(activity))
                activity.setContentView(root)
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(seasonThree().requestFocus())
                screen.onHide()
                root.clearFocus()
                root.visibility = View.GONE
                root.visibility = View.VISIBLE
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue("Return lost the selected season", seasonThree().hasFocus()) }
            instrumentation.runOnMainSync { seasons = seasons.reversed(); screen.onPad(PadAction.Refresh) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue("Refresh followed a position instead of an item ID", seasonThree().hasFocus()) }
        } finally {
            instrumentation.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
        }
    }
}

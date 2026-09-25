package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.*
import com.pocketds.hub.screens.discover.UpcomingScreen
import com.pocketds.hub.screens.downloads.DownloadsScreen
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import java.io.File
import java.lang.reflect.Proxy
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only in .uitest: synthetic failures never touch the user's real queue. */
@RunWith(AndroidJUnit4::class)
class QuartermasterFeaturesTest {
    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    @Test fun cachedDiscoverPageCanArriveDuringInitialLayout() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var pages=0
        fun row(page:Int)=DiscoverRow(id="fixture",title="Fixture row",page=page,totalPages=2,
            items=listOf(SearchHit(media=MediaRef(key="tmdb:movie:$page",title="Example $page"))))
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)) { _,method,_ ->
            when(method.name) {
                "discover" -> HubResult.Ok(DiscoverResponse(rows=listOf(row(1))))
                "discoverRow" -> {pages++;HubResult.Ok(DiscoverResponse(rows=listOf(row(2))))}
                "imageUrl" -> ""
                else -> error("Unexpected operation ${method.name}")
            }
        } as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)) { _,method,_ -> if(method.name=="getViewContext") activity else null } as ScreenHost
        val screen=com.pocketds.hub.screens.discover.DiscoverScreen(api){true}
        try {
            ins.runOnMainSync {
                ContentModeSettings.set(activity,ContentMode.MEDIA)
                val root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertEquals("Immediate page two must be safely appended",1,pages) }
        } finally { ins.runOnMainSync { screen.onHide();screen.onDestroyView();activity.finish() } }
    }

    @Test fun attentionFilterAndDiagnosisAreReadableWithoutPerformingRepairs() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val stuck = ActivityItem(id="test:stuck", title="Example series — fixture", stage="stuck", diagnosis=ActivityDiagnosis(
            code="import_blocked", title="Import needs attention", needsAttention=true,
            explanation="Sonarr reported a problem importing this release.",
            evidence=listOf("Sonarr: importBlocked", "No files eligible for import"),
            nextStep="Check the reported import issue in Sonarr before searching for another release."
        ))
        val response = ActivityResponse(items=listOf(stuck, ActivityItem(id="test:healthy", title="Healthy download — fixture", stage="downloading", diagnosis=ActivityDiagnosis(title="Downloading"))))
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when(method.name) { "activity" -> HubResult.Ok(response); "imageUrl" -> ""; else -> error("Unexpected operation: ${method.name}") }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ -> if(method.name=="getViewContext") activity else null } as ScreenHost
        val screen = DownloadsScreen(api, {true})
        lateinit var root: View
        try {
            ins.runOnMainSync {
                ContentModeSettings.set(activity, ContentMode.MEDIA)
                root=screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen.onShow()
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val filter=all(root).filterIsInstance<TextView>().first { it.text.toString().startsWith("Needs attention") }
                filter.requestFocus(); assertTrue(screen.onPad(PadAction.Activate))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val rows=all(root).filterIsInstance<com.pocketds.hub.screens.downloads.DownloadRowView>()
                assertEquals(1, rows.size)
                rows.single().requestFocus(); assertTrue(screen.onPad(PadAction.Activate))
                var target: View=all(root).filterIsInstance<TextView>().first { it.text=="Why is this stuck?" }
                while(!target.isClickable) target=target.parent as View
                target.performClick()
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(all(root).filterIsInstance<TextView>().any { it.isShown && it.text=="No files eligible for import" })
                assertTrue(all(root).filterIsInstance<TextView>().any { it.isShown && it.text=="Suggested next step" })
                val panel=all(root).filterIsInstance<ChoiceOverlay>().single()
                assertTrue(panel.isOpen)
                repeat(5) { assertTrue(screen.onPad(PadAction.Step(Direction.DOWN))) }
                assertTrue(all(panel).contains(root.findFocus()))
            }
            ins.waitForIdleSync()
            val shot=ins.uiAutomation.takeScreenshot()
            File(activity.externalCacheDir, "diagnostics-fixture.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }
            shot.recycle()
            ins.runOnMainSync {
                assertTrue(screen.onPad(PadAction.Back))
                assertFalse(all(root).filterIsInstance<ChoiceOverlay>().single().isOpen)
            }
        } finally { ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() } }
    }

    @Test fun calendarWeeksRemainContiguousAndEpisodesGroupOnDevice() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val requests=mutableListOf<Pair<String,String>>()
        val today=LocalDate.now().toString()
        val episodes=(1..2).map { CalendarItem(id="episode:$it",date=today,season=1,episode=it,media=MediaRef(key="tmdb:series:1",type="series",title="Example series")) }
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)) { _,method,args ->
            when(method.name) {
                "calendar" -> {
                    val start=args!![0] as String; val end=args[1] as String; requests.add(start to end)
                    HubResult.Ok(CalendarResponse(items=episodes.filter { it.date>=start && it.date<end }))
                }
                "imageUrl" -> ""
                else -> error("Unexpected operation ${method.name}")
            }
        } as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)) { _,method,_ -> if(method.name=="getViewContext") activity else null } as ScreenHost
        val screen=UpcomingScreen(api){true}
        lateinit var root:View
        try {
            ins.runOnMainSync { root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Season 1 · 2 episodes" })
                assertEquals(6,all(root).filterIsInstance<TextView>().count { it.text=="No scheduled releases" })
                assertTrue(screen.onPad(PadAction.Page(Direction.RIGHT)))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(requests[0].second,requests[1].first)
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="No scheduled releases this week" })
                screen.onPad(PadAction.Page(Direction.LEFT))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertEquals(requests[0],requests[2]);assertTrue(screen.requestInitialFocus()) }
        } finally { ins.runOnMainSync { screen.onHide();screen.onDestroyView();activity.finish() } }
    }
}

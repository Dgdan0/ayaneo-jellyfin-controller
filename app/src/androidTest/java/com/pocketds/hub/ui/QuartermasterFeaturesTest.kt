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
import com.pocketds.hub.offline.OfflineRepository
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
    @Test fun offlineSeriesOverflowOpensSubtitleFetchingAndRestoresFocus() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val key = "subtitle-action-${System.nanoTime()}"
        val repository = OfflineRepository.get(activity)
        repository.enqueue("Example series", "", listOf(OfflineManifest(batchKey=key, clientItemKey=key,
            item=LibraryItem(id=key, type="episode", title="Example episode", seriesId=key, seasonId="$key-season", seasonNumber=1, indexNumber=1),
            source=OfflineSource(id="source", container="mp4", sizeBytes=4))))
        val row = checkNotNull(repository.forItem(key))
        repository.mediaFile(row).writeText("film")
        repository.finish(row.id)
        var played = false
        var pushed: Any? = null
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "playItem" -> { played=true; null }
                "push" -> { pushed=args!![0]; null }
                else -> null
            }
        } as ScreenHost
        val screen = com.pocketds.hub.screens.offline.OfflineSeriesScreen(api, key, "Example series") { true }
        lateinit var button: View
        try {
            ins.runOnMainSync {
                val root = screen.onCreateView(host, FrameLayout(activity))
                activity.setContentView(root); screen.onShow()
                button = all(root).filterIsInstance<TextView>().first { it.contentDescription?.startsWith("More actions for") == true }
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(button.requestFocus())
                assertTrue(screen.onPad(PadAction.Activate))
                assertNull("Opening More must not fetch or play", pushed)
                var fetch: View = all(activity.window.decorView).filterIsInstance<TextView>().first { it.text == "Find / update subtitles" }
                while (!fetch.isClickable) fetch = fetch.parent as View
                fetch.performClick()
                assertTrue(pushed is com.pocketds.hub.screens.library.SubtitleScreen)
                assertFalse("Subtitle action must never launch playback", played)
                screen.onHide(); button.clearFocus(); screen.onShow()
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertTrue("Returning should restore the More button", button.hasFocus()) }
        } finally {
            ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
            repository.remove(row.id)
        }
    }

    @Test fun downloadedTitleKeepsItsLocalSubtitleVisibleWithoutTheHub() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val item="offline-subtitle-${System.nanoTime()}"
        val rowKey="fixture-${System.nanoTime()}"
        val track=PlaybackTrack(index=4,type="Subtitle",language="he",codec="srt",external=true)
        val manifest=OfflineManifest(batchKey=rowKey,clientItemKey=rowKey,
            item=LibraryItem(id=item,type="movie",title="Offline example"),
            source=OfflineSource(id="source-1",container="mp4",sizeBytes=4),
            subtitles=listOf(OfflineSubtitle(track,"/fixture/subtitle")))
        val repository=OfflineRepository.get(activity)
        assertEquals(1,repository.enqueue("Offline example","",listOf(manifest)))
        val row=checkNotNull(repository.forItem(item))
        repository.mediaFile(row).writeText("film")
        repository.finish(row.id)
        repository.subtitleFile(row,4,"srt").writeText("1\n00:00:00,000 --> 00:00:01,000\nHello")
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)) { _,method,_ ->
            when(method.name) {"subtitles"->HubResult.Failed(FailureKind.NO_NETWORK);else->error("Unexpected ${method.name}")}
        } as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)) { _,method,_ ->
            if(method.name=="getViewContext") activity else null
        } as ScreenHost
        val screen=com.pocketds.hub.screens.library.SubtitleScreen(api,item,"Offline example"){true}
        try {
            lateinit var root:View
            ins.runOnMainSync {root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()}
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val text=all(root).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(text.any { it.contains("On this AYANEO · 1 subtitle track") })
                assertTrue(text.any { it.contains("Hebrew") && it.contains("Downloaded") })
                assertTrue(text.any { it.contains("Can't reach the Hub") })
                assertFalse(text.any { it.contains("Search subtitle providers") })
            }
        } finally {
            ins.runOnMainSync {screen.onHide();screen.onDestroyView();activity.finish()}
            repository.remove(row.id)
        }
    }
    @Test fun serverMonitorShowsUnknownValuesAndPreservesFocusOnRefresh() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val snapshot=ServerMonitor(host=HostSnapshot(os="Windows",cpuPercent=null,memoryTotalBytes=32L*1073741824,memoryAvailableBytes=12L*1073741824,uptimeSeconds=172800,
            disks=listOf(HostDisk("C:\\",100L*1073741824,5L*1073741824))),containers=listOf(HostContainer("jellyseerr","jellyseerr:latest","running","Up 2 days (healthy)")),sessionWarning="Jellyfin sessions unavailable",checkedAt="2026-09-25T18:00:00Z")
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,method,_->when(method.name){"serverMonitor"->HubResult.Ok(snapshot);else->error("Unexpected ${method.name}")}} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,_->if(method.name=="getViewContext")activity else null} as ScreenHost
        val screen=com.pocketds.hub.screens.manage.ServerMonitorScreen(api){true}
        lateinit var root:View
        try {
            ins.runOnMainSync{root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()};ins.waitForIdleSync()
            ins.runOnMainSync{
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text=="Unavailable"})
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text.contains("Low space (<10%)")})
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text=="Jellyfin sessions unavailable"})
                root.findViewWithTag<View>("disk:C:\\").requestFocus();screen.onPad(PadAction.Refresh)
            };ins.waitForIdleSync()
            ins.runOnMainSync{assertEquals("disk:C:\\",root.findFocus()?.tag)}
            android.os.SystemClock.sleep(500)
            val shot=ins.uiAutomation.takeScreenshot();File(activity.externalCacheDir,"monitor-fixture.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
        } finally {ins.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }
    @Test fun subtitlesKeepScoresSeparateFromPersonalRatingsAndRequireSelection() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val item="subtitle-fixture-${System.nanoTime()}"
        val record=SubtitleRecord(id="history-1",language="Hebrew",provider="Example provider",score="91.11%",date="17 Sep 2026",installed=true)
        val candidate=SubtitleCandidate(ticket="opaque-hub-ticket",language="he",provider="Example provider",score=95.0,release="Example Movie 1080p BluRay",matches=listOf("title","year","release group"),mismatches=listOf("hash"))
        var downloaded="";var records=listOf(record)
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)){_,method,args->when(method.name){
            "subtitles"->HubResult.Ok(SubtitleState(records=records,canDownload=true))
            "searchSubtitles"->HubResult.Ok(SubtitleSearch(listOf(candidate)))
            "downloadSubtitle"->{downloaded=args!![1] as String;HubResult.Ok(ActionAck(ok=true))}
            else->error("Unexpected ${method.name}")
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,_->if(method.name=="getViewContext")activity else null} as ScreenHost
        val screen=com.pocketds.hub.screens.library.SubtitleScreen(api,item,"Example movie — subtitle fixture"){true}
        lateinit var root:View
        fun click(text:String) {var v:View=all(root).filterIsInstance<TextView>().first{it.text.toString().startsWith(text)};while(!v.isClickable)v=v.parent as View;v.performClick()}
        try {
            ins.runOnMainSync{root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()};ins.waitForIdleSync()
            ins.runOnMainSync{
                click("Hebrew · Example provider");click("Out of sync")
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text.contains("91.11% match · You: Out of sync")})
                // A server history retention change must preserve the score, without claiming installation.
                records=emptyList();screen.onPad(PadAction.Refresh)
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text.contains("91.11% match · You: Out of sync")})
                click("Search subtitle providers");click("Hebrew · 95% match")
                assertEquals("",downloaded)
                assertTrue(all(root).filterIsInstance<TextView>().any{it.text.contains("Doesn't match: hash")})
            };ins.waitForIdleSync()
            // Wait for a rendered frame as well as an idle UI queue before visual capture.
            android.os.SystemClock.sleep(500)
            val shot=ins.uiAutomation.takeScreenshot();File(activity.externalCacheDir,"subtitles-fixture.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
            ins.runOnMainSync{click("Download this subtitle");assertEquals("opaque-hub-ticket",downloaded)}
        } finally {ins.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }
    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    @Test fun bandwidthEditsUseKiBAndPreserveTheSelectedMode() {
        val ins=InstrumentationRegistry.getInstrumentation()
        val activity=ins.startActivitySync(Intent(ins.targetContext,DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var sent:BandwidthChange?=null
        val state=BandwidthState(mode="normal",alternativeDownloadBps=10240,alternativeUploadBps=10240,canControl=true,modeSwitchSupported=true)
        val api=Proxy.newProxyInstance(HubApi::class.java.classLoader,arrayOf(HubApi::class.java)) { _,method,args -> when(method.name) {
            "bandwidth" -> HubResult.Ok(state)
            "setBandwidth" -> {sent=args!![0] as BandwidthChange;HubResult.Ok(state.copy(alternativeDownloadBps=sent!!.downloadBps!!,alternativeUploadBps=sent!!.uploadBps!!))}
            else -> error("Unexpected operation ${method.name}")
        }} as HubApi
        val host=Proxy.newProxyInstance(ScreenHost::class.java.classLoader,arrayOf(ScreenHost::class.java)){_,method,_->if(method.name=="getViewContext")activity else null} as ScreenHost
        val screen=com.pocketds.hub.screens.downloads.BandwidthScreen(api){true}
        lateinit var root:View
        try {
            ins.runOnMainSync {root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow()}
            ins.waitForIdleSync()
            ins.runOnMainSync {
                all(root).filterIsInstance<TextView>().first{it.text=="Edit alternative limits"}.performClick()
                val fields=all(root).filterIsInstance<android.widget.EditText>()
                assertEquals(2,fields.size);assertEquals("10",fields[0].text.toString())
                fields[0].setText("32");fields[1].setText("16")
                var target:View=all(root).filterIsInstance<TextView>().first{it.text=="Apply limits"}
                while(!target.isClickable)target=target.parent as View
                target.performClick()
                assertEquals("alternative",sent?.limitsFor);assertEquals("",sent?.mode)
                assertEquals(32768L,sent?.downloadBps);assertEquals(16384L,sent?.uploadBps)
            }
            ins.waitForIdleSync()
            val shot=ins.uiAutomation.takeScreenshot()
            File(activity.externalCacheDir,"bandwidth-fixture.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
        } finally {ins.runOnMainSync{screen.onHide();screen.onDestroyView();activity.finish()}}
    }

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
                var target: View=all(root).filterIsInstance<TextView>().first { it.text=="Why isn't it working?" }
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
        val screen=UpcomingScreen(api, ringVisible = { true })
        lateinit var root:View
        try {
            ins.runOnMainSync { root=screen.onCreateView(host,FrameLayout(activity));activity.setContentView(root);screen.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Season 1 · 2 episodes" })
                // Each empty day is one quiet line under its heading.
                assertEquals(6,all(root).filterIsInstance<TextView>().count { it.text=="·  Nothing scheduled" })
                assertTrue(screen.onPad(PadAction.Page(Direction.RIGHT)))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertEquals(requests[0].second,requests[1].first)
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text=="Nothing this week" })
                screen.onPad(PadAction.Page(Direction.LEFT))
            }
            ins.waitForIdleSync()
            ins.runOnMainSync { assertEquals(requests[0],requests[2]);assertTrue(screen.requestInitialFocus()) }
        } finally { ins.runOnMainSync { screen.onHide();screen.onDestroyView();activity.finish() } }
    }
}

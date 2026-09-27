package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.*
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.playback.PlaybackOptionsScreen
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackMenuTest {
    @Test fun offlineTrackSelectionNeedsNoNetworkAndDoesNotStartPlayback() {
        val ins = InstrumentationRegistry.getInstrumentation()
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val id = "offline-menu-${System.nanoTime()}"
        var played: PlaybackPrepareResponse? = null
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            error("Offline options must not call ${method.name}")
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) {
                "getViewContext" -> activity
                "playPrepared" -> { played = args!![0] as PlaybackPrepareResponse; null }
                "back" -> true
                else -> null
            }
        } as ScreenHost
        val plan = PlaybackPrepareResponse(item=PlaybackItem(id=id,title="Saved movie"), offline=true,
            audioTracks=listOf(PlaybackTrack(index=1,type="Audio",language="en"),PlaybackTrack(index=2,type="Audio",language="he")),
            subtitleTracks=listOf(PlaybackTrack(index=3,type="Subtitle",language="he")),selectedAudioIndex=1,selectedSubtitleIndex=-1)
        val screen = PlaybackOptionsScreen(api,id,"resume",{true},plan)
        lateinit var root: View
        fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun click(label: String) {
            var view: View = all(root).filterIsInstance<TextView>().first { it.text.toString() == label }
            while(!view.isClickable) view = view.parent as View
            view.performClick()
        }
        try {
            ins.runOnMainSync { root=screen.onCreateView(host,FrameLayout(activity)); activity.setContentView(root);screen.onShow() }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertNull(played)
                assertFalse(all(root).filterIsInstance<TextView>().any { it.text == "Quality" || it.text == "Media version" })
                click("Audio");click("Hebrew")
                click("Subtitles");click("Hebrew")
                assertNull("Choosing tracks must not start playback",played)
                click("Play")
                assertEquals(2,played?.selectedAudioIndex)
                assertEquals(3,played?.selectedSubtitleIndex)
                assertTrue(played?.offline == true)
            }
        } finally { ins.runOnMainSync { screen.onHide();screen.onDestroyView();activity.finish() } }
    }
}

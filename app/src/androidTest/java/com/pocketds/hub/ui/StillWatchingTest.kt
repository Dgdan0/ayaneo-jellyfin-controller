package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.PlaybackItem
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.playback.AutoplayRun
import com.pocketds.hub.playback.PlayerScreen
import com.pocketds.hub.playback.StillWatchingView
import com.pocketds.hub.playback.UpNextCardView
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Still watching?" in the player (#48): three episodes start by themselves, and the fourth waits for a button. A
 * generated plan and a hub that answers nothing, in .uitest only; nothing is played.
 */
@RunWith(AndroidJUnit4::class)
class StillWatchingTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private fun shot(activity: android.app.Activity, name: String) {
        ins.waitForIdleSync(); Thread.sleep(500)
        File(activity.getExternalFilesDir(null), "still-watching-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun theFourthEpisodeAsksAndAnyButtonCarriesOn() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        val plan = PlaybackPrepareResponse(
            item = PlaybackItem(id = "still-1", type = "Episode", title = "Episode One", seriesTitle = "Example series", seasonNumber = 1, episodeNumber = 1),
            nextItem = PlaybackItem(id = "still-2", type = "Episode", title = "Episode Two", seriesTitle = "Example series", seasonNumber = 1, episodeNumber = 2),
            offline = true
        )
        // A hub that answers nothing: the next episode cannot be opened, which is all this test needs of it.
        val screen = PlayerScreen(FixtureHub.of(), "still-1", "resume", plan) { true }
        lateinit var root: View
        try {
            ins.runOnMainSync { root = screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(root) }
            ins.waitForIdleSync()
            val run: AutoplayRun = screen.field("autoplayRun")
            val upNext: UpNextCardView = screen.field("upNext")
            val still: StillWatchingView = screen.field("stillWatching")
            ins.runOnMainSync {
                // Three bars fill with nobody there: each episode starts by itself.
                repeat(3) {
                    upNext.onFilled?.invoke()
                    assertFalse("episode ${it + 1} starts by itself", still.asking)
                }
                assertEquals(3, run.run)
                // The fourth does not: the player asks.
                upNext.onFilled?.invoke()
                assertTrue("the fourth asks", still.asking)
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "Still watching?" && it.isShown })
                assertTrue(all(root).filterIsInstance<TextView>().any { it.text == "Continue watching" && it.isShown })
                assertTrue("the answer takes focus", still.continueButton.isFocused)
            }
            shot(activity, "01-asks")
            ins.runOnMainSync {
                // Any button at all: the pad's right, say. It continues and the count starts again.
                assertTrue(screen.onPad(PadAction.Step(Direction.RIGHT)))
                assertFalse(still.asking)
                assertEquals(0, run.run)
            }
        } finally {
            ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
        }
    }

    @Test fun aTouchOrAButtonMeansSomebodyIsThereAndTheCountStartsAgain() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; else -> null }
        } as ScreenHost
        val plan = PlaybackPrepareResponse(
            item = PlaybackItem(id = "touch-1", type = "Episode", title = "Episode One", seriesTitle = "Example series", seasonNumber = 1, episodeNumber = 1),
            nextItem = PlaybackItem(id = "touch-2", type = "Episode", title = "Episode Two", seriesTitle = "Example series", seasonNumber = 1, episodeNumber = 2),
            offline = true
        )
        val screen = PlayerScreen(FixtureHub.of(), "touch-1", "resume", plan) { true }
        lateinit var root: View
        try {
            ins.runOnMainSync { root = screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(root) }
            ins.waitForIdleSync()
            val run: AutoplayRun = screen.field("autoplayRun")
            val upNext: UpNextCardView = screen.field("upNext")
            val still: StillWatchingView = screen.field("stillWatching")
            ins.runOnMainSync {
                upNext.onFilled?.invoke(); upNext.onFilled?.invoke()
                assertEquals(2, run.run)
                // A finger on the screen.
                val now = SystemClock.uptimeMillis()
                root.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 40f, 40f, 0))
                assertEquals("a touch is somebody there", 0, run.run)
                upNext.onFilled?.invoke(); upNext.onFilled?.invoke()
                assertEquals(2, run.run)
                // A press of any button.
                screen.onPad(PadAction.Page(Direction.UP))
                assertEquals(0, run.run)
                repeat(3) { upNext.onFilled?.invoke() }
                assertFalse("three autoplays since the last touch, and not yet asked", still.asking)
                // Android's Back, or the edge swipe, answers it too.
                upNext.onFilled?.invoke()
                assertTrue(still.asking)
                assertTrue(screen.onSystemBack())
                assertFalse(still.asking)
            }
        } finally {
            ins.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
        }
    }
}

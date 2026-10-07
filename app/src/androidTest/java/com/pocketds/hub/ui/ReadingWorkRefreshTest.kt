package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book's page kept on the stack reads its work again when it is shown (#21):
 * The Final Empire's read-along finished aligning while its page waited, and
 * the page never offered it. Generated work; the hub is a stand-in.
 */
@RunWith(AndroidJUnit4::class)
class ReadingWorkRefreshTest {
    @Test fun aBookPageOffersAReadAlongThatAppearedWhileItWaited() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val oldUser = HubSettings.userId(activity) to HubSettings.userName(activity)
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Refresh test")
        val id = UUID.randomUUID().toString()
        val text = ReadingEdition(source = "storyteller", sourceItemId = "book1", kind = "ebook", format = "epub", availability = "available")
        val audio = ReadingEdition(source = "storyteller", sourceItemId = "book1", kind = "audiobook", narrator = "A generated voice", availability = "available")
        val aligned = ReadingEdition(source = "storyteller", sourceItemId = "book1", kind = "readaloud", availability = "available")
        val before = ReadingWork(id = id, title = "The Last Observatory", editions = listOf(text, audio))
        val after = before.copy(editions = listOf(text, audio, aligned))
        val reads = AtomicInteger()
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) {
                "readingWork" -> HubResult.Ok(if (reads.incrementAndGet() == 1) before else after)
                "imageUrl" -> ""
                else -> error(method.name)
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        val screen = ReadingWorkScreen(api, id, before.title, ringVisible = { true })
        lateinit var root: View
        fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        // The read-along chip of the formats row (#39): a control once there is an edition to open, grey before.
        fun offersReadAlong(): Boolean = all(root).any { view -> view.isShown && view.isFocusable &&
            view.contentDescription?.toString() == "Read along, opens at your place" }
        try {
            instrumentation.runOnMainSync {
                root = screen.onCreateView(host, FrameLayout(activity))
                activity.setContentView(root)
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1, reads.get())
                assertFalse("No read-along yet", offersReadAlong())
                // Away to another page and back: the page reads its work again.
                screen.onHide()
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("Read again when shown", 2, reads.get())
                assertTrue("The read-along that finished aligning is offered", offersReadAlong())
            }
        } finally {
            instrumentation.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
            HubSettings.selectUser(activity, oldUser.first, oldUser.second)
        }
    }
}

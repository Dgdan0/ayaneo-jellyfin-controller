package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYouResponse
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.reader.ReadingCompletionRepository
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingCompletionViewTest {
    @Test fun readActionUndoWindowEndsWhenBookPageIsLeft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        // A profile of its own, put back afterwards: a device's test build keeps the one it browses with.
        val oldUser = HubSettings.userId(activity) to HubSettings.userName(activity)
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Test reader")
        val work = ReadingWork(id = "book", title = "Book", progress = ReadingProgress(.5, false))
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) {
                "readingWork" -> HubResult.Ok(work); "imageUrl" -> ""
                // Finishing says so to the hub as well (#39); this one keeps nothing.
                "updateReadingYou" -> HubResult.Ok(ReadingYouResponse(work.id, null))
                else -> error(method.name)
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        // Read is "Finished" in the ⋯ menu: the card asks when (this month), and Mark finished is its last button.
        // Unread is "Mark unread", offered once the book is finished (#39).
        fun chooseReadAction(screen: ReadingWorkScreen, root: View, label: String) {
            all(root).first { it.contentDescription == "More actions for Book" }.performClick()
            all(root).first { it.contentDescription?.toString()?.startsWith(label) == true }.performClick()
            if (label == "Finished") {
                screen.onPad(PadAction.Step(Direction.DOWN))
                screen.onPad(PadAction.Step(Direction.DOWN))
                screen.onPad(PadAction.Step(Direction.RIGHT))
                screen.onPad(PadAction.Activate)
            }
        }
        var screen = ReadingWorkScreen(api, work.id, work.title, ringVisible = { true })
        lateinit var root: View
        try {
            instrumentation.runOnMainSync {
                root = screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                chooseReadAction(screen, root, "Finished")
                assertTrue(ReadingCompletionRepository.get(activity).project(work).progress!!.completed)
                chooseReadAction(screen, root, "Mark unread")
                assertEquals(.5, ReadingCompletionRepository.get(activity).project(work).progress!!.percentage, 0.0)
                chooseReadAction(screen, root, "Finished")
                screen.onHide(); screen.onDestroyView()
                screen = ReadingWorkScreen(api, work.id, work.title, ringVisible = { true })
                root = screen.onCreateView(host, FrameLayout(activity)); activity.setContentView(root); screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                chooseReadAction(screen, root, "Mark unread")
                val completion = ReadingCompletionRepository.get(activity)
                assertEquals(0.0, completion.project(work).progress!!.percentage, 0.0)
                assertTrue(completion.shouldStartAtBeginning(work.id))
            }
        } finally {
            instrumentation.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
            HubSettings.selectUser(activity, oldUser.first, oldUser.second)
        }
    }
}

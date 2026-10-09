package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.home.ReadingHomeView
import com.pocketds.hub.screens.home.ReadingListEntry
import com.pocketds.hub.screens.home.ReadingListsRepository
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingHomeViewTest {
    @Test fun bookDetailAddsToAListAndListsSurviveTokenRotationButFollowProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val userA = UUID.randomUUID().toString()
        val userB = UUID.randomUUID().toString()
        HubSettings.save(activity, "https://reading-list-test.example", "token-one")
        HubSettings.selectUser(activity, userA, "Reader A")
        ReadingListsRepository.update(activity) { it.create("Comics", "comics") }
        val work = ReadingWork(id = "rw_0123456789abcdef0123456789abcdef", title = "One comic")
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) {
                "readingWork" -> HubResult.Ok(work); "imageUrl" -> ""
                // Choosing a reading status says so to the hub (#63); this one keeps nothing.
                "updateReadingYou" -> HubResult.Ok(com.pocketds.hub.model.ReadingYouResponse(work.id, null))
                else -> error("Unexpected ${method.name}")
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        val screen = ReadingWorkScreen(api, work.id, work.title, ringVisible = { true })
        lateinit var root: View
        fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        try {
            instrumentation.runOnMainSync {
                root = screen.onCreateView(host, android.widget.FrameLayout(activity))
                activity.setContentView(root)
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                all(root).first { it.contentDescription?.toString() == "More actions for ${work.title}" }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Add to a list") == true }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Comics") == true }.performClick()
                assertTrue(ReadingListsRepository.get(activity).lists.single().items.any { it.workId == work.id })
                // Want to read is a choice of the Reading status row of the ⋯ menu (#63): the list this device keeps.
                all(root).first { it.contentDescription?.toString() == "More actions for ${work.title}" }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Reading status") == true }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Want to read,") == true }.performClick()
                assertTrue(ReadingListsRepository.get(activity).wantToRead.any { it.workId == work.id })
                // Any other status takes it off the list again: there is one Want to read, not two.
                all(root).first { it.contentDescription?.toString() == "More actions for ${work.title}" }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Reading status") == true }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Not reading,") == true }.performClick()
                assertFalse(ReadingListsRepository.get(activity).wantToRead.any { it.workId == work.id })
                all(root).first { it.contentDescription?.toString() == "More actions for ${work.title}" }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Reading status") == true }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Want to read,") == true }.performClick()
                assertTrue(ReadingListsRepository.get(activity).wantToRead.any { it.workId == work.id })
                HubSettings.save(activity, "https://reading-list-test.example", "token-two")
                assertTrue(ReadingListsRepository.get(activity).lists.single().items.any { it.workId == work.id })
                HubSettings.selectUser(activity, userB, "Reader B")
                assertFalse(ReadingListsRepository.get(activity).lists.any { it.id == "comics" })
            }
        } finally {
            instrumentation.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
        }
    }

    @Test fun listOpensAtNextUnreadBookAndKeepsCompletedBooksToItsLeft() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Reading test")
        ReadingListsRepository.update(activity) { state ->
            (1..6).fold(state.create("Comics", "comics")) { next, index ->
                next.add("comics", ReadingListEntry("book$index", "Book $index",
                    lastProgress = if (index <= 2) 1.0 else 0.0))
            }
        }
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, args ->
            when (method.name) {
                "readingLibraries" -> HubResult.Ok(ReadingLibrariesResponse())
                "readingWork" -> {
                    val id = args!![0] as String
                    val number = id.removePrefix("book").toInt()
                    HubResult.Ok(ReadingWork(id = id, title = "Book $number",
                        progress = ReadingProgress(if (number <= 2) 1.0 else 0.0, number <= 2)))
                }
                "imageUrl" -> ""
                else -> error("Unexpected ${method.name}")
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        lateinit var view: ReadingHomeView
        fun all(root: View): List<View> = listOf(root) + (root as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        try {
            instrumentation.runOnMainSync {
                view = ReadingHomeView(activity, api, host, Theme.colors(activity)) { true }
                activity.setContentView(view)
                view.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue(view.requestInitialFocus())
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val cards = all(view).filterIsInstance<PosterCardView>()
                assertTrue(cards.size >= 6)
                assertTrue(cards[2].hasFocus())
                assertTrue(cards[0].contentDescription.toString().contains("100 percent read"))
            }
        } finally {
            instrumentation.runOnMainSync { view.destroy(); activity.finish() }
        }
    }

    @Test fun emptyListStillHasFocusableManagementAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DetailFixtureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Empty list test")
        ReadingListsRepository.update(activity) { it.create("New comics", "empty") }
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) { "readingLibraries" -> HubResult.Ok(ReadingLibrariesResponse()); "imageUrl" -> ""; else -> error("Unexpected ${method.name}") }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        lateinit var view: ReadingHomeView
        fun all(root: View): List<View> = listOf(root) + (root as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        try {
            instrumentation.runOnMainSync {
                view = ReadingHomeView(activity, api, host, Theme.colors(activity)) { true }
                activity.setContentView(view)
                view.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val manage = all(view).first { it.contentDescription?.toString() == "Manage New comics" }
                assertTrue(manage.isFocusable)
                assertTrue(manage.requestFocus())
            }
        } finally {
            instrumentation.runOnMainSync { view.destroy(); activity.finish() }
        }
    }
}

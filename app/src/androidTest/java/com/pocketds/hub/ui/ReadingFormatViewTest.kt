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
import com.pocketds.hub.screens.library.ReadingEntryMode
import com.pocketds.hub.screens.library.ReadingEntryPreferences
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReadingFormatViewTest {
    @Test fun choosingNarrationOnlyPreviewsUntilPrimaryActionStartsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Format test")
        val work = ReadingWork(id = UUID.randomUUID().toString(), title = "Test book", editions = listOf(
            ReadingEdition(source = "storyteller", sourceItemId = "text", kind = "ebook", format = "epub", availability = "available"),
            ReadingEdition(source = "storyteller", sourceItemId = "alice", kind = "audiobook", narrator = "Alice", availability = "available"),
            ReadingEdition(source = "storyteller", sourceItemId = "bob", kind = "audiobook", narrator = "Bob", availability = "available")
        ))
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, _ ->
            when (method.name) { "readingWork" -> HubResult.Ok(work); "imageUrl" -> ""; else -> error(method.name) }
        } as HubApi
        var pushes = 0
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; "push" -> { pushes++; null }; else -> null }
        } as ScreenHost
        val screen = ReadingWorkScreen(api, work.id, work.title, ringVisible = { true })
        lateinit var root: View
        fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)
            ?.let { group -> (0 until group.childCount).flatMap { all(group.getChildAt(it)) } }.orEmpty()
        try {
            instrumentation.runOnMainSync {
                root = screen.onCreateView(host, FrameLayout(activity))
                activity.setContentView(root)
                screen.onShow()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                all(root).filterIsInstance<TextView>().first { it.text == "Change format" }.performClick()
                all(root).first { it.contentDescription?.toString()?.startsWith("Listen to audiobook, Bob") == true }.performClick()
                assertEquals(0, pushes)
                assertNull(ReadingEntryPreferences.get(activity, work.id))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val primary = all(root).filterIsInstance<TextView>().first { it.text.toString().startsWith("Listen · Bob") }
                assertTrue(primary.hasFocus())
                primary.performClick()
                assertEquals(1, pushes)
                val saved = ReadingEntryPreferences.get(activity, work.id)!!
                assertEquals(ReadingEntryMode.LISTEN, saved.mode)
                assertEquals("bob", saved.audioSourceItemId)
            }
        } finally {
            instrumentation.runOnMainSync { screen.onHide(); screen.onDestroyView(); activity.finish() }
        }
    }
}

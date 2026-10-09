package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingLibraryItemsResponse
import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.home.ReadingHomeView
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A book's reading status on the covers and on Books home (#63), drawn from generated works that never reach a real
 * server, book or place: the ✓ is for every finished book, however it came to be finished, and a book put down is
 * off Currently reading with its place untouched.
 */
@RunWith(AndroidJUnit4::class)
class ReadingStatusViewTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private fun card(context: android.content.Context, work: ReadingWork) = PosterCardView(context, Theme.colors(context))
        .apply { bindReadingWork(work, Artwork.loader(com.pocketds.hub.net.HubClient(context), context), { it }) }

    @Test fun theTickIsForEveryFinishedBookHoweverItCameToBeFinished() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            ins.runOnMainSync {
                fun tick(work: ReadingWork) = all(card(activity, work)).any { it is TextView && it.text.toString() == "✓" }
                assertTrue("marked Finished, with no place", tick(ReadingWork(id = "a", title = "A", status = "finished")))
                assertTrue("imported as read", tick(ReadingWork(id = "b", title = "B", you = ReadingYou(status = "read", finished = "2024-05"))))
                assertTrue("read to the end, from a hub that sends no status", tick(ReadingWork(id = "c", title = "C", progress = ReadingProgress(1.0, true))))
                assertFalse("being read", tick(ReadingWork(id = "d", title = "D", status = "reading", progress = ReadingProgress(.4))))
                assertFalse("put down", tick(ReadingWork(id = "e", title = "E", status = "not-reading", progress = ReadingProgress(.4))))
                assertFalse("wanted", tick(ReadingWork(id = "f", title = "F", status = "want")))
                assertFalse("read to the end and now being read again", tick(ReadingWork(id = "g", title = "G", status = "reading", progress = ReadingProgress(1.0, true))))
                assertFalse("nothing said", tick(ReadingWork(id = "h", title = "H")))
            }
        } finally {
            ins.runOnMainSync { activity.finish() }
        }
    }

    @Test fun aBookPutDownIsOffCurrentlyReadingAndItsPlaceIsKept() {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val oldUser = HubSettings.userId(activity) to HubSettings.userName(activity)
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Status test")
        val library = ReadingLibrary(id = "storyteller:books", source = "storyteller", kind = "book", title = "Books")
        fun book(id: String, percent: Double, status: String) = ReadingWork(id = id, libraryId = library.id, title = id, artwork = "",
            progress = ReadingProgress(percent, false, updatedAt = "2026-10-0${id.last()}T10:00:00Z"), status = status)
        val works = listOf(book("book1", .3, "reading"), book("book2", .6, "not-reading"), book("book3", .2, "finished"), book("book4", .5, ""))
        val api = Proxy.newProxyInstance(HubApi::class.java.classLoader, arrayOf(HubApi::class.java)) { _, method, args ->
            when (method.name) {
                "readingLibraries" -> HubResult.Ok(ReadingLibrariesResponse(libraries = listOf(library)))
                "readingLibraryItems" -> HubResult.Ok(ReadingLibraryItemsResponse(libraryId = library.id, items = works, total = works.size, totalPages = 1))
                "readingWork" -> HubResult.Ok(works.first { it.id == args!![0] })
                "imageUrl" -> ""
                else -> error("Unexpected ${method.name}")
            }
        } as HubApi
        val host = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, _ ->
            when (method.name) { "getViewContext" -> activity; else -> null }
        } as ScreenHost
        lateinit var view: ReadingHomeView
        try {
            ins.runOnMainSync {
                view = ReadingHomeView(activity, api, host, Theme.colors(activity)) { true }
                activity.setContentView(view)
                view.onShow()
            }
            ins.waitForIdleSync()
            Thread.sleep(800)
            ins.runOnMainSync {
                // The book read last is the hero, the others "Also reading": everything the row says of a book, by its words.
                val shown = all(view).flatMap { listOfNotNull(it.contentDescription?.toString(), (it as? TextView)?.text?.toString()) }
                assertTrue(shown.toString(), shown.any { it.contains("book1") })
                assertTrue(shown.toString(), shown.any { it.contains("book4") })
                assertFalse("a book put down is not being read: $shown", shown.any { it.contains("book2") })
                assertFalse("a finished book is not being read: $shown", shown.any { it.contains("book3") })
            }
            // A picture for the review; the surface is sometimes not there to be captured, and nothing is asserted by it.
            ins.uiAutomation.takeScreenshot()?.let { picture ->
                File(activity.getExternalFilesDir(null), "reading-status-home.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // The place is the hub's and was not touched: the work still has it.
            assertEquals(.6, works[1].progress!!.percentage, 0.0)
        } finally {
            ins.runOnMainSync { view.destroy(); activity.finish() }
            HubSettings.selectUser(activity, oldUser.first, oldUser.second)
        }
    }
}

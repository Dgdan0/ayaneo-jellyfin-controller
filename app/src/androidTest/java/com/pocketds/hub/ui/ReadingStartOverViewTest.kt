package com.pocketds.hub.ui

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.ReadingPublicationManifest
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudioPlace
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingCheckpointKey
import com.pocketds.hub.reader.ReadingLocation
import com.pocketds.hub.reader.ReadingManifestCache
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.reader.ReadingResets
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.HubSettings
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Start over (#60), through the real client against a stand-in hub that answers it the way the hub does: a book with a
 * place offers it in its ⋯ menu, asks first with the harmless answer first and under the cursor, and once confirmed the
 * hub forgets the place and this device forgets what it kept of it (the text, the listening, a cached copy's page, an
 * audiobook out of its ZIP). A place kept offline from before a start over is dropped, never offered as a conflict.
 * Generated book and covers; nothing here reaches a real server, book or place, or the everyday app.
 */
@RunWith(AndroidJUnit4::class)
class ReadingStartOverViewTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun start(): ReaderFixtureActivity =
        (ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity)
            .also { check(it.packageName.endsWith(".uitest")) }

    private suspend fun until(what: String, timeoutMs: Long = 20_000, check: () -> Boolean) {
        try { withTimeout(timeoutMs) { while (!withContext(Dispatchers.Main) { check() }) delay(80) } }
        catch (e: Exception) { throw AssertionError("Timed out waiting for $what", e) }
    }

    private suspend fun shot(activity: ReaderFixtureActivity, name: String) {
        ins.waitForIdleSync()
        delay(700)
        File(activity.getExternalFilesDir(null), "start-over-$name.png").outputStream().use {
            ins.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun shown(root: View, words: String) = all(root).any { it is TextView && it.isShown && it.text.toString() == words }

    /** A row of a side sheet by its words, as a finger would press it. */
    private fun row(root: View, words: String): View? {
        var view: View? = all(root).firstOrNull { it is TextView && it.isShown && it.text.toString() == words } ?: return null
        while (view != null && !view.isClickable) view = view.parent as? View
        return view
    }

    private fun work(id: String) = JSONObject()
        .put("id", id).put("libraryId", "storyteller:books").put("entityType", "work").put("kind", "book")
        .put("title", "The Last Observatory").put("authors", JSONArray(listOf("A. Fixture")))
        .put("overview", "A generated novel about a lighthouse that watches the sky instead of the sea.")
        .put("artwork", "/v1/img/fixture/cover").put("genres", JSONArray(listOf("Fantasy")))
        .put("year", 2006)
        .put("editions", JSONArray()
            .put(JSONObject().put("id", "e1").put("workId", id).put("source", "storyteller").put("sourceItemId", "book1")
                .put("kind", "ebook").put("format", "epub").put("pageCount", 541).put("availability", "available"))
            .put(JSONObject().put("id", "e2").put("workId", id).put("source", "storyteller").put("sourceItemId", "book1")
                .put("kind", "audiobook").put("narrator", "A generated voice").put("durationMs", 88_680_000L).put("availability", "available")))
        .put("progress", JSONObject().put("percentage", 0.32).put("completed", false))

    private fun text(progress: Double) = ReadingLocation(locator = buildJsonObject {
        put("href", "ch14.xhtml"); put("type", "application/xhtml+xml"); put("title", "Chapter 14")
        put("locations", buildJsonObject { put("progression", 0.5); put("totalProgression", progress) })
    })

    /** What a test has put in the device's settings, put back. */
    private class Saved(val activity: ReaderFixtureActivity) {
        val url = HubSettings.baseUrl(activity)
        val token = HubSettings.token(activity)
        val user = HubSettings.userId(activity) to HubSettings.userName(activity)
        fun restore() {
            HubSettings.save(activity, url, token)
            HubSettings.selectUser(activity, user.first, user.second)
        }
    }

    private fun host(activity: ReaderFixtureActivity, notices: MutableList<String>) = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
        when (method.name) {
            "getViewContext" -> activity
            "notify" -> { notices += args[0] as String; null }
            "back" -> true
            else -> null
        }
    } as ScreenHost

    @Test fun aBookWithAPlaceOffersStartOverAsksFirstAndThenTheHubAndThisDeviceForgetIt(): Unit = runBlocking {
        val activity = start()
        val saved = Saved(activity)
        val workId = "start-over-${System.nanoTime()}"
        val hub = StandInHub(workId, "book1", listOf(60))
        hub.cover = ReaderFixtures.cover(300, 450, "The Last Observatory")
        hub.page = work(workId)
        hub.held = StandInHub.Place(hub.tracks[0].id, 12_000)
        hub.you.put("rating", 4).put("finished", "2025-09").put("readCount", 2).put("status", "read")
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Start over test")
        val progress = ReadingProgress.get(activity)
        val scope = progress.session().identity
        // This device keeps places of the book too: in the text, in the listening, in a cached copy's pages, and
        // the one an audiobook out of its ZIP kept before the hub kept any.
        val epubKey = progress.session().key(workId, "book1", "epub")
        val audioKey = progress.session().key(workId, "book1", AudioPlace.KIND)
        val pagesKey = ReadingCheckpointKey(scope, workId, "issue", "pages")
        progress.store.save(epubKey, text(0.32), System.currentTimeMillis())
        progress.store.save(audioKey, AudioPlace(hub.tracks[0].id, 12_000).location(), System.currentTimeMillis())
        val legacy = ReadingResets.legacyAudioKeys(scope, workId, listOf("book1"))
        ReadingAudio.positions(activity).edit().putInt(legacy[0], 1).putLong(legacy[1], 5_000).commit()
        ReadingManifestCache.at(activity).save(pagesKey, ReadingPublicationManifest(workId = workId, sourceItemId = "issue", pageCount = 12, currentPage = 9))
        val notices = mutableListOf<String>()
        val screen = ReadingWorkScreen(HubClient(activity), workId, "The Last Observatory", ringVisible = { true })
        lateinit var root: View
        try {
            withContext(Dispatchers.Main) {
                root = screen.onCreateView(host(activity, notices), FrameLayout(activity))
                activity.setContentView(root); screen.onShow()
            }
            until("the page with its place") { all(root).any { it is TextView && it.isShown && it.text.toString().startsWith("Resume") } }
            fun openMore() = all(root).first { it.contentDescription == "More actions for The Last Observatory" }.performClick()

            // ⋯ offers Start over: the book has a place.
            withContext(Dispatchers.Main) { openMore() }
            until("the menu") { shown(root, "Start over") }
            shot(activity, "1-menu")

            // Asked first, the harmless answer first and under the cursor, naming what is forgotten and what stays.
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the question") { shown(root, "Keep my place") && shown(root, "Start The Last Observatory over?") }
            until("the cursor on the harmless answer") {
                all(root).firstOrNull { it.isFocused }?.contentDescription?.toString()?.startsWith("Keep my place") == true
            }
            withContext(Dispatchers.Main) {
                assertTrue(shown(root, "Your place in the ebook and audiobook is forgotten on every device. Your rating, notes and bookmarks stay."))
                assertTrue(shown(root, "Start over"))
            }
            shot(activity, "2-confirm")

            // Keeping changes nothing, anywhere.
            withContext(Dispatchers.Main) { row(root, "Keep my place")!!.performClick() }
            until("the question closed") { !shown(root, "Keep my place") }
            assertTrue("the hub was not asked", hub.startOvers.isEmpty())
            assertNotNull(progress.store.read(epubKey)?.local)
            assertNotNull(hub.held)

            // Start over: the hub forgets the book's place, this device forgets what it kept of it.
            withContext(Dispatchers.Main) { openMore() }
            until("the menu again") { shown(root, "Start over") }
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the question again") { shown(root, "Keep my place") }
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the hub started it over") { hub.startOvers.size == 1 }
            until("the page not started") { shown(root, "Read book") }

            assertNull("the hub forgot the listening place", hub.held)
            assertEquals("the rating stays", 4, hub.you.optInt("rating"))
            assertFalse("the finish is gone", hub.you.has("finished"))
            assertNull("the text place", progress.store.read(epubKey))
            assertNull("the listening place, and the outbox with it", progress.store.read(audioKey))
            assertFalse("the audiobook's own place", ReadingAudio.positions(activity).contains(legacy[0]) || ReadingAudio.positions(activity).contains(legacy[1]))
            assertEquals("a cached copy's page", 0, ReadingManifestCache.at(activity).read(pagesKey)!!.currentPage)
            assertEquals("this device has seen it", hub.resetAt, progress.resets.seen(scope, workId))
            assertTrue(notices.last(), notices.last().startsWith("Started over"))

            // Nothing is left to start over from.
            withContext(Dispatchers.Main) { openMore() }
            until("the menu once more") { shown(root, "Add to a list") }
            withContext(Dispatchers.Main) { assertFalse("no place, nothing to start over", shown(root, "Start over")) }
            shot(activity, "3-after")
        } finally {
            withContext(Dispatchers.Main) { screen.onHide(); screen.onDestroyView(); activity.finish() }
            progress.store.dropWork(scope, workId)
            ReadingManifestCache.at(activity).dropPlace(workId)
            saved.restore()
            hub.shutdown()
        }
    }

    @Test fun aPlaceKeptOfflineFromBeforeAStartOverIsDroppedAndNotAskedAbout(): Unit = runBlocking {
        val activity = start()
        val saved = Saved(activity)
        val workId = "start-over-${System.nanoTime()}"
        val hub = StandInHub(workId, "book1", listOf(60))
        hub.held = StandInHub.Place(hub.tracks[0].id, 12_000)
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Start over test")
        val progress = ReadingProgress.get(activity)
        val scope = progress.session().identity
        val key = progress.session().key(workId, "book1", AudioPlace.KIND)
        try {
            // This phone read the place the hub had, then listened on offline: a place waiting in the outbox.
            progress.resume(progress.session(), key)
            progress.save(key, AudioPlace(hub.tracks[0].id, 30_000).location(), sync = false)
            assertTrue(progress.store.read(key)!!.pending)
            // Another device started the book over.
            val stamp = hub.startOverNow()

            progress.flush()

            // The place is gone, not a conflict to choose from, and nothing was written to the hub.
            assertTrue("nothing was written to the hub", hub.writes.isEmpty())
            val kept = progress.store.read(key)
            assertFalse("not waiting to be sent", kept?.pending ?: false)
            assertNull("no place kept", kept?.local)
            assertFalse("no conflict to choose from", kept?.conflicted ?: false)
            assertEquals(stamp, progress.resets.seen(scope, workId))
            assertNull(hub.held)
        } finally {
            progress.store.dropWork(scope, workId)
            saved.restore()
            activity.finish()
            hub.shutdown()
        }
    }

    @Test fun aWriteTheHubRefusesBecauseTheBookWasStartedOverMeanwhileDropsThePlaceToo(): Unit = runBlocking {
        val activity = start()
        val saved = Saved(activity)
        val workId = "start-over-${System.nanoTime()}"
        val hub = StandInHub(workId, "book1", listOf(60))
        hub.held = StandInHub.Place(hub.tracks[0].id, 12_000)
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Start over test")
        val progress = ReadingProgress.get(activity)
        val scope = progress.session().identity
        val key = progress.session().key(workId, "book1", AudioPlace.KIND)
        try {
            progress.resume(progress.session(), key)
            progress.save(key, AudioPlace(hub.tracks[0].id, 30_000).location(), sync = false)
            // The hub is started over just after this phone reads the place it will check its write against.
            hub.startOverAfterNextRead = true

            progress.flush()

            assertEquals("the write went out once and was refused for what it was", 1, hub.refusedAsReset.size)
            assertEquals("it carried the start over this phone had seen: none", 0L, hub.refusedAsReset[0].getLong("resetSeen"))
            val kept = progress.store.read(key)
            assertFalse("not waiting to be sent", kept?.pending ?: false)
            assertNull("no place kept", kept?.local)
            assertEquals("it read the book again and saw the start over", hub.resetAt, progress.resets.seen(scope, workId))
            // A place read after it is kept, and sent knowing of it: the book is opened (its place read, here none), then listened to.
            progress.resume(progress.session(), key)
            progress.save(key, AudioPlace(hub.tracks[0].id, 4_000).location(), sync = false)
            progress.flush()
            assertEquals(1, hub.refusedAsReset.size)
            assertEquals(4_000L, hub.held?.offsetMs)
            assertEquals(hub.resetAt, hub.writes.last().first.getLong("resetSeen"))
        } finally {
            progress.store.dropWork(scope, workId)
            saved.restore()
            activity.finish()
            hub.shutdown()
        }
    }

    /** The older page, a comic's: its ⋯ menu offers Start over too, and a finish by reading is only taken back by it. */
    @Test fun aComicOffersStartOverInItsMenuTooAndAFinishedOneIsOnlyTakenBackByIt(): Unit = runBlocking {
        val activity = start()
        val saved = Saved(activity)
        HubSettings.selectUser(activity, UUID.randomUUID().toString(), "Start over test")
        val progress = ReadingProgress.get(activity)
        val scope = progress.session().identity
        val asked = mutableListOf<String>()
        val notices = mutableListOf<String>()
        fun comic(id: String, done: Boolean, started: Boolean) = com.pocketds.hub.model.ReadingWork(
            id = id, title = "Saga", kind = "comic", entityType = "work",
            progress = if (started) com.pocketds.hub.model.ReadingProgress(if (done) 1.0 else 0.4, done) else null)
        var current = comic("saga-1", done = false, started = true)
        val api = FixtureHub.of(
            "readingWork" to { _ -> com.pocketds.hub.net.HubResult.Ok(current) },
            "startOverReading" to { args ->
                asked += args[0] as String
                current = comic(current.id, done = false, started = false)
                com.pocketds.hub.net.HubResult.Ok(com.pocketds.hub.model.ReadingStartOverResponse(true, "start_over", current.id, 9_000, null))
            },
            "imageUrl" to { _ -> "" }
        )
        val pagesKey = ReadingCheckpointKey(scope, "saga-1", "issue-6", "pages")
        progress.store.save(pagesKey, ReadingLocation(pageIndex = 7), System.currentTimeMillis())
        var screen = ReadingWorkScreen(api, "saga-1", "Saga", ringVisible = { true })
        lateinit var root: View
        fun open() {
            root = screen.onCreateView(host(activity, notices), FrameLayout(activity))
            activity.setContentView(root); screen.onShow()
        }
        try {
            withContext(Dispatchers.Main) { open() }
            until("the page") { all(root).any { it.contentDescription == "More actions for Saga" } }
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "More actions for Saga" }.performClick() }
            until("the menu") { shown(root, "Start over") }
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the question") { shown(root, "Start Saga over?") && shown(root, "Keep my place") }
            withContext(Dispatchers.Main) {
                assertTrue(shown(root, "Your place is forgotten and every issue is unread again, on every device. Your lists stay."))
                row(root, "Keep my place")!!.performClick()
            }
            until("the question closed") { !shown(root, "Keep my place") }
            assertTrue(asked.isEmpty())
            assertNotNull(progress.store.read(pagesKey)?.local)
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "More actions for Saga" }.performClick() }
            until("the menu again") { shown(root, "Start over") }
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the question again") { shown(root, "Keep my place") }
            withContext(Dispatchers.Main) { row(root, "Start over")!!.performClick() }
            until("the hub asked") { asked == listOf("saga-1") }
            until("this device forgot it") { progress.store.read(pagesKey) == null }
            assertEquals(9_000L, progress.resets.seen(scope, "saga-1"))

            // A comic finished by reading: no "Mark ... unread" that would leave the place; Start over is what takes it back.
            withContext(Dispatchers.Main) { screen.onHide(); screen.onDestroyView() }
            current = comic("saga-2", done = true, started = true)
            screen = ReadingWorkScreen(api, "saga-2", "Saga", ringVisible = { true })
            withContext(Dispatchers.Main) { open() }
            until("the finished page") { all(root).any { it.contentDescription == "More actions for Saga" } }
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "More actions for Saga" }.performClick() }
            until("the finished menu") { shown(root, "Reading lists") }
            withContext(Dispatchers.Main) {
                assertTrue(shown(root, "Start over"))
                assertFalse("nothing here can take a finish by reading away but Start over",
                    all(root).any { it is TextView && it.isShown && it.text.toString().startsWith("Mark") && it.text.toString().endsWith("unread") })
            }
        } finally {
            withContext(Dispatchers.Main) { screen.onHide(); screen.onDestroyView(); activity.finish() }
            progress.store.dropWork(scope, "saga-1")
            progress.store.dropWork(scope, "saga-2")
            saved.restore()
        }
    }
}

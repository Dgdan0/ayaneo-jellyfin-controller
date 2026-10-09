package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudioPlace
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ModeEntry
import com.pocketds.hub.reader.ModePlace
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingCheckpointKey
import com.pocketds.hub.reader.ReadingProgress
import com.pocketds.hub.reader.RemoteReadingPosition
import com.pocketds.hub.settings.HubSettings
import com.pocketds.hub.ui.ChoiceOverlay
import java.lang.reflect.Proxy
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The audio side of the mode button, and of "You listened further on ..." (#62), on a stand-in hub with generated silence: the ebook's
 * selected sentence starts the audiobook at that moment; from the audiobook the page opens where the voice was, and Read along carries
 * the voice on; another device's place further on is asked about, Go there takes it and Stay here keeps this device's. Nothing reaches a
 * real server, book or place.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderAudioModesTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    /** Its own for every test (JUnit makes a new instance for each): a track's bytes are kept on the device under the book, the track and the etag, and tracks of different lengths must not meet. */
    private val book = "book${System.nanoTime()}"

    /** [seconds] long: the question is asked only about a place a minute or more further on. */
    private fun stand(work: String, seconds: Int = 40) = StandInHub(work, book, listOf(seconds), alignment = listOf(Triple("EPUB/voice.wav", 0, 0L)),
        whole = ReaderFixtures.selectionEpub(aligned = false),
        slim = ReaderFixtures.selectionEpub(aligned = true, sentenceSeconds = 2, withAudio = false))

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private inline fun <reified T> Any.field(name: String): T = javaClass.getDeclaredField(name).apply { isAccessible = true }.get(this) as T

    private suspend fun until(what: String, limitMs: Long = 30_000, check: () -> Boolean) {
        try { withTimeout(limitMs) { while (!withContext(Dispatchers.Main) { check() }) delay(100) } }
        catch (e: Exception) {
            val now = ReadingAudio.state.value
            throw AssertionError("Timed out waiting for $what (book=${now.book != null} ready=${now.ready} playing=${now.playing} part=${now.part} at=${now.positionMs} problem=${now.problem} work=${now.book?.workId} duration=${runCatching { withContext(Dispatchers.Main) { player().duration } }.getOrNull()} playerAt=${runCatching { withContext(Dispatchers.Main) { player().currentPosition } }.getOrNull()})", e)
        }
    }

    private fun shot(name: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/reader62-$name.png")).use { it.readBytes() }
    }

    private val right = PadAction.Step(Direction.RIGHT)
    private val left = PadAction.Step(Direction.LEFT)
    private val y = PadAction.Secondary
    private val a = PadAction.Activate

    private fun player() = (ReadingAudio::class.java.getDeclaredField("service").apply { isAccessible = true }.get(null) as com.pocketds.hub.reader.ReadingAudioService).player

    /** The audiobook screen, opened on its own (the book has an ebook and a read-along edition too). */
    private suspend fun withAudiobook(hub: StandInHub, work: String, entry: ModeEntry = ModeEntry(), block: suspend (AudiobookScreen, MutableList<Any>, MutableList<String>) -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, ReaderFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReaderFixtureActivity
        check(activity.packageName.endsWith(".uitest"))
        val oldUrl = HubSettings.baseUrl(activity)
        val oldToken = HubSettings.token(activity)
        HubSettings.save(activity, hub.server.url("/").toString(), "fixture")
        val pushed = ArrayList<Any>()
        val notes = ArrayList<String>()
        val hosting = Proxy.newProxyInstance(ScreenHost::class.java.classLoader, arrayOf(ScreenHost::class.java)) { _, method, args ->
            when (method.name) { "getViewContext" -> activity; "back" -> true; "push" -> { pushed += args!![0]; null }; "notify" -> { notes += args!![0] as String; null }; else -> null }
        } as ScreenHost
        var screen: AudiobookScreen? = null
        try {
            withContext(Dispatchers.Main) {
                val audio = ReadingEdition(id = book, workId = work, source = "storyteller", sourceItemId = book, kind = "audiobook", narrator = "A generated voice")
                val aligned = ReadingEdition(id = book, workId = work, source = "storyteller", sourceItemId = book, kind = "readaloud", narrator = "A generated voice")
                val ebook = ReadingEdition(id = "e", workId = work, source = "storyteller", sourceItemId = book, kind = "ebook")
                screen = AudiobookScreen(HubClient(activity), work, audio, "The Lantern Keeper", { true }, listOf(audio), ebook, listOf(aligned),
                    work = ReadingWork(id = work, title = "The Lantern Keeper", authors = listOf("A. Fixture")), entry = entry)
                activity.setContentView(screen!!.onCreateView(hosting, FrameLayout(activity))); screen!!.onShow()
            }
            block(screen!!, pushed, notes)
        } catch (failure: Throwable) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/reader62-failure.png")).use { it.readBytes() }
            throw failure
        } finally {
            withContext(Dispatchers.Main) {
                screen?.let { it.onHide(); it.onDestroyView() }
                if (ReadingAudio.state.value.book != null) ReadingAudio.stop()
                activity.finish()
            }
            // The player is a service: the next test begins when this one's book is off it.
            for (i in 0 until 50) { if (ReadingAudio.state.value.book == null) break; delay(100) }
            HubSettings.save(activity, oldUrl, oldToken)
            hub.shutdown()
        }
    }

    private suspend fun AudiobookScreen.pad(vararg actions: PadAction) {
        for (action in actions) { withContext(Dispatchers.Main) { onPad(action) }; delay(300) }
    }

    @Test fun theSentenceSelectedInTheEbookIsWhereTheAudiobookBegins() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            selectAndWait("lantern")
            withContext(Dispatchers.Main) { screen.onPad(y) }
            until("the button open and the card closed") { screen.field<com.pocketds.hub.reader.ModeButtonView>("modeButton").isOpen && !card.isOpen }
            withContext(Dispatchers.Main) { screen.onPad(right) }
            delay(300)
            withContext(Dispatchers.Main) { screen.onPad(a) }
            until("the reader asked for the audiobook", 20_000) { pushed.isNotEmpty() }
            val next = pushed.single() as AudiobookScreen
            val entry = next.field<ModeEntry>("entry")
            assertEquals(ModePlace.Start.SELECTED_SENTENCE, entry.start)
            assertEquals("lantern", entry.anchor?.quote?.highlight)
            adopt(next)
            // The sentence "Somewhere above her, the lantern was still burning." is the sixth, narrated from the tenth second.
            until("the audiobook plays from the sentence", 45_000) {
                val now = ReadingAudio.state.value
                now.book != null && now.playing && abs(player().currentPosition - 10_000) < 4_000
            }
            assertTrue(notes.toString(), notes.any { it == "Listening from the start of the sentence you selected" })
            shot("audio-from-selection")
        }
    }

    @Test fun fromTheAudiobookTheEbookOpensWhereTheVoiceWas() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work)
        // The voice is at the sixth sentence, ten seconds in.
        hub.held = StandInHub.Place(hub.tracks[0].id, 10_500)
        withAudiobook(hub, work) { screen, pushed, _ ->
            until("the audiobook on the player") { ReadingAudio.state.value.book != null && ReadingAudio.state.value.ready }
            shot("audio-screen")
            // To the ebook: Ⓨ, ◀, Ⓐ.
            screen.pad(y)
            until("the button open") { screen.field<com.pocketds.hub.reader.ModeButtonView>("modeButton").isOpen }
            shot("audio-modes-open")
            screen.pad(left, a)
            until("the ebook asked for", 30_000) { pushed.isNotEmpty() }
            val ebook = pushed.single() as EpubReaderScreen
            val entry = ebook.field<ModeEntry>("entry")
            assertEquals("Somewhere above her, the lantern was still burning.", entry.anchor?.quote?.highlight)
            assertEquals(ModePlace.Start.WHERE_VOICE_STOPPED, entry.start)
        }
    }

    @Test fun fromTheAudiobookToReadAlongTheVoiceKeepsGoingFromTheSamePlace() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work)
        hub.held = StandInHub.Place(hub.tracks[0].id, 10_500)
        withAudiobook(hub, work) { screen, pushed, _ ->
            until("the audiobook on the player") { ReadingAudio.state.value.book != null && ReadingAudio.state.value.ready }
            withContext(Dispatchers.Main) { ReadingAudio.play() }
            until("playing") { ReadingAudio.state.value.playing }
            screen.pad(y)
            until("the button open") { screen.field<com.pocketds.hub.reader.ModeButtonView>("modeButton").isOpen }
            screen.pad(right, a)
            until("Read along asked for", 30_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            val carried = along.field<ModeEntry>("entry")
            assertEquals(hub.tracks[0].id, carried.audioPlace?.trackId)
            assertTrue("the place it was at: ${carried.audioPlace}", abs((carried.audioPlace?.offsetMs ?: 0) - 10_500) < 4_000)
            assertTrue("and playing", carried.playing)
            assertEquals(ModePlace.Start.WHERE_VOICE_STOPPED, carried.start)
        }
    }

    private suspend fun settle(hub: StandInHub, work: String, ours: AudioPlace) {
        val progress = ReadingProgress.get(ins.targetContext)
        val session = progress.session()
        val key = ReadingCheckpointKey(session.identity, work, book, AudioPlace.KIND)
        progress.store.reconcile(key, RemoteReadingPosition.Available(ours.location()))
        assertFalse(progress.store.read(key)!!.pending)
    }

    /** The question a sheet asks is a card of its own, over the sheet. */
    private fun question(screen: AudiobookScreen): ChoiceOverlay? = screen.field<ChoiceOverlay>("overlay").field<ChoiceOverlay?>("question")

    private fun overlayTexts(screen: AudiobookScreen): List<String> =
        question(screen)?.let { card -> all(card).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() } }.orEmpty()

    @Test fun anotherDevicesPlaceFurtherOnIsAskedAboutAndGoThereTakesIt() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work, seconds = 900)
        HubSettings.save(ins.targetContext, hub.server.url("/").toString(), "fixture")
        settle(hub, work, AudioPlace(hub.tracks[0].id, 5_000))
        hub.held = StandInHub.Place(hub.tracks[0].id, 300_000)
        hub.device = "ipad-dan"
        hub.writtenAt = System.currentTimeMillis() - 12 * 60_000
        withAudiobook(hub, work) { screen, _, _ ->
            until("the question") { question(screen)?.isOpen == true }
            val words = withContext(Dispatchers.Main) { overlayTexts(screen) }
            assertTrue(words.toString(), "You listened further on ipad-dan" in words)
            assertTrue(words.toString(), "Part 1 · 12 minutes ago" in words)
            assertTrue("Go there" in words && "Stay here" in words)
            shot("audio-away")
            withContext(Dispatchers.Main) {
                question(screen)!!.rows.first { row -> all(row).filterIsInstance<TextView>().any { it.text == "Go there" } }.performClick()
            }
            until("playing from the other device's place", 40_000) { ReadingAudio.state.value.book != null && ReadingAudio.state.value.ready && abs(player().currentPosition - 300_000) < 2_000 }
        }
    }

    @Test fun stayHereKeepsThisDevicesPlaceAndMakesItTheHubs() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work, seconds = 900)
        HubSettings.save(ins.targetContext, hub.server.url("/").toString(), "fixture")
        settle(hub, work, AudioPlace(hub.tracks[0].id, 5_000))
        hub.held = StandInHub.Place(hub.tracks[0].id, 300_000)
        hub.device = "ipad-dan"
        withAudiobook(hub, work) { screen, _, _ ->
            until("the question") { question(screen)?.isOpen == true }
            withContext(Dispatchers.Main) {
                question(screen)!!.rows.first { row -> all(row).filterIsInstance<TextView>().any { it.text == "Stay here" } }.performClick()
            }
            until("playing from this device's place", 40_000) { ReadingAudio.state.value.book != null && ReadingAudio.state.value.ready && abs(player().currentPosition - 5_000) < 2_000 }
            val progress = ReadingProgress.get(ins.targetContext)
            val key = ReadingCheckpointKey(progress.session().identity, work, book, AudioPlace.KIND)
            val kept = progress.store.read(key)!!
            assertTrue("this device's place goes out", kept.pending)
            assertEquals(5_000L, AudioPlace.of(kept.local)?.offsetMs)
        }
    }

    @Test fun aPlaceThisDeviceWroteItselfOrNotFurtherOnIsNotAskedAbout() = runBlocking {
        val work = "audio-${System.nanoTime()}"
        val hub = stand(work, seconds = 900)
        HubSettings.save(ins.targetContext, hub.server.url("/").toString(), "fixture")
        settle(hub, work, AudioPlace(hub.tracks[0].id, 5_000))
        hub.held = StandInHub.Place(hub.tracks[0].id, 300_000)
        hub.byThisDevice = true
        withAudiobook(hub, work) { screen, _, _ ->
            until("playing from the hub's place without a question", 40_000) { ReadingAudio.state.value.book != null && ReadingAudio.state.value.ready && abs(player().currentPosition - 300_000) < 2_000 }
            assertFalse(withContext(Dispatchers.Main) { screen.field<SidePanelView>("overlay").isOpen || question(screen)?.isOpen == true })
        }
    }
}

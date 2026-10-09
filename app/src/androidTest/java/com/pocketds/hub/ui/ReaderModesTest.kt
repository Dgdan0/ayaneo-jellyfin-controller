package com.pocketds.hub.ui

import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ModeButtonView
import com.pocketds.hub.reader.ModeEntry
import com.pocketds.hub.reader.ModePlace
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The one mode button on the real reader and a stand-in hub (#62): it shows only the mode you are in and opens out to the others the book
 * has; Ⓨ opens it, ◀▶ choose, Ⓐ switches, Ⓑ closes, and it closes by itself. A switch begins by one rule: at the sentence of the words
 * just selected, else where the voice stopped (if heard on the page), else the top of the page; the page opens where the voice was,
 * with "Heard to here". Generated book, generated silence; nothing reaches a real server or book.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderModesTest {
    /** Its own for every test (JUnit makes a new instance for each): a track's bytes are kept on the device under the book, the track and the etag, and tracks of different lengths must not meet. */
    private val book = "book${System.nanoTime()}"

    private fun stand(work: String) = StandInHub(work, book, listOf(40), alignment = listOf(Triple("EPUB/voice.wav", 0, 0L)),
        whole = ReaderFixtures.selectionEpub(aligned = false),
        slim = ReaderFixtures.selectionEpub(aligned = true, sentenceSeconds = 2, withAudio = false))

    private suspend fun SelectionBook.pad(vararg actions: PadAction) {
        for (action in actions) {
            withContext(Dispatchers.Main) { screen.onPad(action) }
            delay(250)
        }
    }

    private fun SelectionBook.modeButton(): ModeButtonView = screen.field("modeButton")

    private val right = PadAction.Step(Direction.RIGHT)
    private val left = PadAction.Step(Direction.LEFT)
    private val y = PadAction.Secondary
    private val a = PadAction.Activate

    @Test fun aBookWithOneFormatHasNoModeButtonAndYStaysContents() = runBlocking {
        withSelectionBook {
            assertEquals(android.view.View.GONE, withContext(Dispatchers.Main) { modeButton().visibility })
            assertEquals("Contents", withContext(Dispatchers.Main) { screen.hints().first { it.glyph == "Ⓨ" }.label })
            pad(y)
            until("the Navigator") { overlay.isOpen }
        }
    }

    @Test fun theButtonShowsOnlyTheModeYouAreInAndOpensOutToTheOthersAndClosesBySelf() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            // The menu is up so that the bar shows.
            pad(PadAction.Menu)
            until("the bar") { modeButton().isShown }
            assertEquals(listOf(ReadingMode.EBOOK), withContext(Dispatchers.Main) { modeButton().shown })
            assertEquals("Mode", withContext(Dispatchers.Main) { screen.hints().first { it.glyph == "Ⓨ" }.label })
            shot("mode-closed")
            pad(y)
            until("it is open") { modeButton().isOpen }
            assertEquals(listOf(ReadingMode.EBOOK, ReadingMode.AUDIO, ReadingMode.ALONG), withContext(Dispatchers.Main) { modeButton().shown })
            assertEquals(ReadingMode.EBOOK, withContext(Dispatchers.Main) { modeButton().focusedMode })
            val keys = withContext(Dispatchers.Main) { screen.hints().associate { it.glyph to it.label } }
            assertEquals("Choose a mode", keys["D-pad ← →"])
            assertEquals("Switch", keys["Ⓐ"])
            assertEquals("Close", keys["Ⓑ"])
            pad(right)
            assertEquals(ReadingMode.AUDIO, withContext(Dispatchers.Main) { modeButton().focusedMode })
            assertEquals("Audio", withContext(Dispatchers.Main) { all(root).filterIsInstance<android.widget.TextView>().first { it.text == "Audio" && it.visibility == android.view.View.VISIBLE }.text.toString() })
            shot("mode-open")
            pad(PadAction.Back)
            assertFalse(withContext(Dispatchers.Main) { modeButton().isOpen })
            assertEquals(listOf(ReadingMode.EBOOK), withContext(Dispatchers.Main) { modeButton().shown })
            // It closes by itself after five seconds.
            pad(y)
            until("it is open again") { modeButton().isOpen }
            delay(5_800)
            assertFalse(withContext(Dispatchers.Main) { modeButton().isOpen })
        }
    }

    @Test fun aTapBesideTheOpenButtonClosesIt() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            pad(PadAction.Menu)
            until("the bar") { modeButton().isShown }
            pad(y)
            until("it is open") { modeButton().isOpen }
            // A tap on the page, well away from the bar.
            tap(root.width / 2f, root.height * 0.75f)
            until("it is closed") { !modeButton().isOpen }
            assertEquals(listOf(ReadingMode.EBOOK), withContext(Dispatchers.Main) { modeButton().shown })
        }
    }

    @Test fun whereTheVoiceStoppedIsWhereReadAlongBeginsWhenTheMarkIsOnThePage() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            // The ebook is opened at where the voice was: the fourth sentence, marked.
            val heard = com.pocketds.hub.reader.SentenceAnchor("EPUB/one.xhtml",
                com.pocketds.hub.reader.AnnotationQuote("", "In spite of the cold, she pulled off her gloves and set her palm against the lighthouse door.", ""))
            val ebook = EpubReaderScreen(com.pocketds.hub.net.HubClient(activity), work, book, "The Lantern Keeper", { true }, bookPages = 120,
                readAlongAvailable = true, ebookSourceItemId = book,
                alignedEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = book)),
                audioEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = book)),
                entry = ModeEntry(ReadingMode.ALONG, ModePlace.Start.WHERE_VOICE_STOPPED, heard))
            adopt(ebook)
            until("the mark is on the page", 30_000) { current.field<com.pocketds.hub.reader.SentenceAnchor?>("heardAnchor") != null }
            var marked = 0
            for (i in 0 until 60) { marked = count("[data-group=\"heard\"] [data-style] > *"); if (marked > 0) break; delay(200) }
            assertTrue("the mark: $marked", marked > 0)
            pushed.clear()
            // Ⓨ, ▶ ▶, Ⓐ: nothing is selected, so the voice begins where it stopped, not at the top of the page.
            withContext(Dispatchers.Main) { ebook.onPad(y) }
            until("the button open") { ebook.field<ModeButtonView>("modeButton").isOpen }
            withContext(Dispatchers.Main) { ebook.onPad(right); delay(250); ebook.onPad(right); delay(250); ebook.onPad(a) }
            until("Read along asked for", 20_000) { pushed.isNotEmpty() }
            val next = pushed.single() as EpubReaderScreen
            val entry = next.field<ModeEntry>("entry")
            assertEquals(ModePlace.Start.WHERE_VOICE_STOPPED, entry.start)
            assertEquals("In spite of the cold, she pulled off her gloves and set her palm against the lighthouse door.", entry.anchor?.quote?.highlight)
            adopt(next)
            until("the voice is on that sentence", 40_000) {
                val voice = next.field<ReadAlongPlayback?>("narration") ?: return@until false
                voice.timeline.active(voice.position.track, voice.position.offsetMs)?.fragment == "s3" && voice.isPlaying
            }
            assertTrue(notes.toString(), notes.any { it == "Going on from where the voice stopped" })
        }
    }

    @Test fun readAlongAsksAboutAPlaceAnotherDeviceReachedFurtherOn() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            // This device had settled on a place early in the book.
            val progress = com.pocketds.hub.reader.ReadingProgress.get(activity)
            val key = com.pocketds.hub.reader.ReadingCheckpointKey(progress.session().identity, work, book, "epub")
            val early = com.pocketds.hub.reader.ReadingLocation(locator = kotlinx.serialization.json.buildJsonObject {
                put("href", kotlinx.serialization.json.JsonPrimitive("EPUB/one.xhtml"))
                put("type", kotlinx.serialization.json.JsonPrimitive("application/xhtml+xml"))
                put("locations", kotlinx.serialization.json.buildJsonObject {
                    put("fragments", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("s1"))))
                    put("totalProgression", kotlinx.serialization.json.JsonPrimitive(0.1))
                })
            })
            val along = EpubReaderScreen(com.pocketds.hub.net.HubClient(activity), work, book, "The Lantern Keeper", { true }, bookPages = 120,
                readAlong = true, readAlongAvailable = true, ebookSourceItemId = book,
                alignedEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = book)),
                audioEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = book)))
            // Leaving the first screen saved the place it was on, and that is sent while the hub is asked for its own. When that
            // is over, this device's settled place is the early one, the hub's already having it (what a device that had synced
            // looks like before another one reads on).
            leave()
            progress.flush()
            progress.save(key, early, sync = false)
            progress.store.acknowledge(key, progress.store.read(key)!!.revision, early)
            // And then an iPad, twelve minutes ago, read on to the middle of the book (the hub keeps what a device writes, so this
            // is said last).
            this.hub.textPlaceFrom("""{"href":"EPUB/one.xhtml","type":"application/xhtml+xml","title":"Chapter One","locations":{"fragments":["s8"],"totalProgression":0.6}}""",
                "ipad-dan", System.currentTimeMillis() - 12 * 60_000)
            adopt(along)
            fun asked() = along.field<com.pocketds.hub.ui.ChoiceOverlay>("overlay").field<com.pocketds.hub.ui.ChoiceOverlay?>("question")
            until("the question", 40_000) { asked()?.isOpen == true }
            val words = withContext(Dispatchers.Main) { all(asked()!!).filterIsInstance<android.widget.TextView>().filter { it.isShown }.map { it.text.toString() } }
            assertTrue(words.toString(), "You listened further on ipad-dan" in words)
            assertTrue(words.toString(), "Chapter One \u00B7 12 minutes ago" in words)
            // The sentence there is quoted under Go there.
            assertTrue(words.toString(), words.any { it.contains("Inside, the stair wound up into darkness") || it.contains("Maren counted the steps") || it.contains("\u201C") })
            shot("along-away")
            withContext(Dispatchers.Main) { asked()!!.rows.first { row -> all(row).filterIsInstance<android.widget.TextView>().any { it.text == "Stay here" } }.performClick() }
            // This device's place is kept and goes to the hub: it is the early one (the page goes there and Readium writes its own words for
            // it, so how far through the book it is is what is compared, not the stand-in's locator), and not the iPad's, which is at 0.6.
            fun share(place: com.pocketds.hub.reader.ReadingLocation?): Double? =
                (((place?.locator?.get("locations") as? kotlinx.serialization.json.JsonObject)?.get("totalProgression")) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()
            until("this device's place is kept", 20_000) {
                com.pocketds.hub.reader.ReadingProgress.get(activity).store.read(key)?.let { it.pending && (share(it.local) ?: 1.0) < 0.3 } == true
            }
        }
    }

    @Test fun readAlongBeginsAtTheSentenceOfTheWordsJustSelectedAndTheVoicePlays() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            selectAndWait("lantern")
            pad(y)
            until("the button is open and the card closed") { modeButton().isOpen && !card.isOpen }
            pad(right, right)
            assertEquals(ReadingMode.ALONG, withContext(Dispatchers.Main) { modeButton().focusedMode })
            pad(a)
            until("the reader asked for Read along", 15_000) { pushed.isNotEmpty() }
            val next = pushed.single() as EpubReaderScreen
            val entry = next.field<ModeEntry>("entry")
            assertEquals(ModePlace.Start.SELECTED_SENTENCE, entry.start)
            assertEquals("lantern", entry.anchor?.quote?.highlight)
            assertTrue(entry.playing)
            adopt(next)
            with(next) {
                until("the voice is on the sentence", 40_000) {
                    val voice = field<ReadAlongPlayback?>("narration") ?: return@until false
                    voice.timeline.active(voice.position.track, voice.position.offsetMs)?.fragment == "s5" && voice.isPlaying
                }
            }
            assertTrue(notes.toString(), notes.any { it == "Reading along from the start of the sentence you selected" })
            shot("along-from-selection")
        }
    }

    @Test fun withNothingSelectedReadAlongBeginsAtTheTopOfThePage() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            pad(y, right, right, a)
            until("the reader asked for Read along", 15_000) { pushed.isNotEmpty() }
            val next = pushed.single() as EpubReaderScreen
            assertEquals(ModePlace.Start.TOP_OF_PAGE, next.field<ModeEntry>("entry").start)
            adopt(next)
            with(next) {
                until("the voice plays from the first sentence of the page", 40_000) {
                    val voice = field<ReadAlongPlayback?>("narration") ?: return@until false
                    voice.isPlaying && voice.timeline.active(voice.position.track, voice.position.offsetMs)?.fragment in listOf("s0", "s1")
                }
            }
            assertTrue(notes.toString(), notes.any { it == "Reading along from the top of your page" })
        }
    }

    @Test fun backToTheEbookTheBookOpensWhereTheVoiceWasWithHeardToHere() = runBlocking {
        val work = "modes-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            // Read along, from the sentence with "palm against" in it (the fourth).
            selectAndWait("palm against")
            pad(y, right, right, a)
            until("Read along asked for", 15_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            val alongRoot = adopt(along)
            with(along) {
                until("the voice is on the sentence", 40_000) {
                    val voice = field<ReadAlongPlayback?>("narration") ?: return@until false
                    voice.timeline.active(voice.position.track, voice.position.offsetMs)?.fragment == "s3" && voice.isPlaying
                }
            }
            // Back to the ebook, with Ⓨ and ◀ ◀ and Ⓐ, on the read-along screen. The voice reads on while the keys are pressed (a
            // sentence of the fixture is a couple of seconds), so the sentence it is on is read at the moment of Ⓐ.
            var heard = ""
            withContext(Dispatchers.Main) {
                val padOn = { action: PadAction -> along.onPad(action) }
                padOn(y); delay(400); padOn(left); delay(300); padOn(left); delay(300)
                val voice = along.field<ReadAlongPlayback?>("narration")!!
                heard = voice.timeline.active(voice.position.track, voice.position.offsetMs)!!.fragment
                padOn(a)
            }
            until("the ebook asked for", 20_000) { pushed.size == 2 }
            val ebook = pushed[1] as EpubReaderScreen
            val entry = ebook.field<ModeEntry>("entry")
            // Fragments are s0, s1, ... in the order the sentences are read.
            val sentence = ReaderFixtures.SELECTION_PARAGRAPHS.flatten()[heard.removePrefix("s").toInt()]
            assertEquals("the voice was on $heard", sentence, entry.anchor?.quote?.highlight)
            adopt(ebook)
            with(ebook) {
                until("the sentence is marked", 30_000) { field<com.pocketds.hub.reader.SentenceAnchor?>("heardAnchor") != null }
            }
            // Said once, as the page comes up, and it names the mark.
            assertEquals(notes.toString(), 1, notes.count { it == "Heard to here: paused, your place is kept" })
            var marked = 0
            for (i in 0 until 60) {
                marked = count("[data-group=\"heard\"] [data-style] > *")
                if (marked > 0) break
                delay(200)
            }
            assertTrue("the mark is on the page: $marked", marked > 0)
            delay(500)
            shot("heard-to-here")
            alongRoot.hashCode()
        }
    }
}

package com.pocketds.hub.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AudiobookScreen
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubPagePalette
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.EpubTheme
import com.pocketds.hub.reader.HighlightColor
import com.pocketds.hub.reader.ModeButtonView
import com.pocketds.hub.reader.ModeEntry
import com.pocketds.hub.reader.ModePlace
import com.pocketds.hub.reader.PageSpan
import com.pocketds.hub.reader.ReadAlongGlow
import com.pocketds.hub.reader.ReadAlongHighlights
import com.pocketds.hub.reader.ReadAlongPlayback
import com.pocketds.hub.reader.ReadAlongSegment
import com.pocketds.hub.reader.ReadAlongWordHighlight
import com.pocketds.hub.reader.ReaderMarks
import com.pocketds.hub.reader.ReaderSelection
import com.pocketds.hub.reader.ReadingAnnotation
import com.pocketds.hub.reader.ReadingAudio
import com.pocketds.hub.reader.ReadingMode
import com.pocketds.hub.reader.SentenceAnchor
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * #62's features on a WORD edition (#66), end to end on the real reader and a stand-in hub: the audio manifest says `wordLevel`, read along
 * asks for `granularity=word`, and the edition it gets has every word its own span and `<par>`. The mode button from the ebook (a word
 * selected, nothing selected, where the voice stopped), back to the ebook from a word ("Heard to here" under the whole sentence, the place kept
 * as the sentence), Read along to Audio and back across a second stretch of narration, the selection bar inside the word spans, highlights
 * between the word edition and the plain ebook, a highlight under the moving word, a selection drag, and fast turns with the voice turning
 * the page. Generated books and generated silence; nothing reaches a real server, book or place.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderWordModesTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    /** Its own for every test: a track's bytes are kept on the device under the book, and books of different lengths must not meet. */
    private val book = "book${System.nanoTime()}"
    private val sentences = ReaderFixtures.SELECTION_PARAGRAPHS.flatten()
    private val wordPattern = Regex("[A-Za-z0-9]+")

    /** The sentence edition's narration, the first [SPLIT] sentences from voice.wav and the rest from voice2.wav, [SECONDS] each. */
    private fun stand(work: String) = StandInHub(work, book, listOf(TRACK_SECONDS),
        alignment = listOf(Triple("EPUB/voice.wav", 0, 0L), Triple("EPUB/voice2.wav", 0, SPLIT * SECONDS * 1000L)),
        whole = ReaderFixtures.selectionEpub(aligned = false),
        slim = ReaderFixtures.selectionEpub(aligned = true, sentenceSeconds = SECONDS, withAudio = false, split = SPLIT),
        words = ReaderFixtures.selectionEpub(aligned = true, sentenceSeconds = SECONDS, withAudio = false, words = true, split = SPLIT))

    private val right = PadAction.Step(Direction.RIGHT)
    private val left = PadAction.Step(Direction.LEFT)
    private val y = PadAction.Secondary
    private val a = PadAction.Activate

    // ------------------------------------------------------------------ helpers

    private suspend fun SelectionBook.pad(vararg actions: PadAction) {
        for (action in actions) {
            withContext(Dispatchers.Main) { current.onPad(action) }
            delay(300)
        }
    }

    private fun editions() = listOf(ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = book, narrator = "A generated voice"))
    private fun audiobooks() = listOf(ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = book, narrator = "A generated voice"))

    /** The reader as Read along, its speaker the test's own. */
    private fun SelectionBook.alongScreen(work: String, entry: ModeEntry = ModeEntry()): EpubReaderScreen =
        EpubReaderScreen(HubClient(activity), work, book, "The Lantern Keeper", { true }, bookPages = 120, readAlong = true, readAlongAvailable = true,
            ebookSourceItemId = book, alignedEditions = editions(), audioEditions = audiobooks(), entry = entry).also { speaker(it) }

    /** The reader as the plain ebook. */
    private fun SelectionBook.ebookScreen(work: String, entry: ModeEntry = ModeEntry()): EpubReaderScreen =
        EpubReaderScreen(HubClient(activity), work, book, "The Lantern Keeper", { true }, bookPages = 120, readAlongAvailable = true,
            ebookSourceItemId = book, alignedEditions = editions(), audioEditions = audiobooks(), entry = entry).also { speaker(it) }

    private fun SelectionBook.speaker(screen: Any) {
        if (screen is EpubReaderScreen) screen.javaClass.getDeclaredField("voiceFactory").apply { isAccessible = true }
            .set(screen, { _: android.content.Context, _: (String) -> Unit -> voice })
    }

    private fun SelectionBook.voiceOf(screen: Any): ReadAlongPlayback? = screen.field("narration")
    private fun SelectionBook.lit(screen: Any): ReadAlongSegment? = screen.field("highlightedSegment")

    /** Read along on show, its narration ready and nothing over the page. */
    private suspend fun SelectionBook.ready(screen: EpubReaderScreen) {
        until("the narration", 60_000) { voiceOf(screen) != null && screen.field<View>("loading").visibility != View.VISIBLE }
        assertTrue("the edition read along got is the word edition", withContext(Dispatchers.Main) { voiceOf(screen)!!.timeline.wordLevel })
        delay(600)
        withContext(Dispatchers.Main) { if (screen.field<Boolean>("controlsVisible")) screen.onPad(PadAction.Menu) }
        until("the menu away") { !screen.field<Boolean>("controlsVisible") }
        delay(500)
    }

    /** "s5-w3" as (5, 3), a sentence as (5, -1): the order the book reads them in. */
    private fun order(fragment: String): Pair<Int, Int> {
        val sentence = fragment.removePrefix("s").substringBefore("-w").toInt()
        val word = if ("-w" in fragment) fragment.substringAfter("-w").toInt() else -1
        return sentence to word
    }

    private fun rank(fragment: String) = order(fragment).let { (sentence, word) -> sentence * 1_000 + word + 1 }

    private fun sentenceOf(segment: ReadAlongSegment) = segment.sentenceFragment.removePrefix("s").toInt()

    /** The words lit, in the order they were lit, read every 15 ms until [done] says so. */
    private suspend fun SelectionBook.litWords(screen: Any, limitMs: Long = 60_000, done: (List<ReadAlongSegment>) -> Boolean): List<ReadAlongSegment> {
        val seen = ArrayList<ReadAlongSegment>()
        try {
            withTimeout(limitMs) {
                while (true) {
                    val now = withContext(Dispatchers.Main) { lit(screen) }
                    if (now != null && seen.lastOrNull() != now) seen += now
                    if (done(seen)) break
                    delay(15)
                }
            }
        } catch (e: Exception) { throw AssertionError("Timed out watching the words: ${seen.map { it.fragment }}", e) }
        return seen
    }

    /** Where in the audiobook's one track the voice is now: its stretch's file mapped onto the track, and the moment in it. */
    private fun trackMs(voice: ReadAlongPlayback): Long {
        val position = voice.position
        val stretch = voice.timeline.tracks[position.track]
        return fileStart(stretch.audioHref) + stretch.startMs + position.offsetMs
    }

    private fun fileStart(audio: String) = if (audio.endsWith("voice2.wav")) SPLIT * SECONDS * 1000L else 0L

    /** The word the fixture narrates at [trackMs] of the track: the last one begun (between two words, the one before). */
    private fun wordAt(trackMs: Long): String {
        val sentence = (trackMs / (SECONDS * 1000L)).toInt().coerceAtMost(sentences.lastIndex)
        val inSentence = trackMs - sentence * SECONDS * 1000L
        val count = wordPattern.findAll(sentences[sentence]).count()
        val slot = SECONDS * 1000L / count
        return "s$sentence-w${(inSentence / slot).toInt().coerceAtMost(count - 1)}"
    }

    /** The words of [text] wherever they are on the page, across the spans of a word edition: the first line's box and the whole box, in CSS pixels. */
    private suspend fun SelectionBook.findText(text: String, select: Boolean): Pair<RectF, RectF> = lines(text, select).let { it.first to it.third }

    /** The first line's box, the last line's and the whole box of [text] on the page, in CSS pixels. */
    private suspend fun SelectionBook.lines(text: String, select: Boolean = false): Triple<RectF, RectF, RectF> {
        val raw = js("($FIND)(${JSONObject.quote(text)}, $select)").trim('"')
        check(raw.startsWith("ok ")) { "'$text' is not on the page: $raw" }
        val (first, last, whole) = raw.removePrefix("ok ").split(';').map { part -> part.split(',').map { it.toFloat() }.let { RectF(it[0], it[1], it[2], it[3]) } }
        return Triple(first, last, whole)
    }

    private suspend fun SelectionBook.toScreen(css: RectF): RectF {
        val d = activity.resources.displayMetrics.density
        val page = IntArray(2)
        withContext(Dispatchers.Main) { current.field<EpubNavigatorFragment?>("navigator")!!.view!!.getLocationOnScreen(page) }
        return RectF(css.left * d + page[0], css.top * d + page[1], css.right * d + page[0], css.bottom * d + page[1])
    }

    /** Selects [text] as a finger and the handles would, across word spans too, and lets the reader look at it. */
    private suspend fun SelectionBook.selectWords(text: String) {
        val (_, whole) = findText(text, select = true)
        selected = toScreen(whole)
        withContext(Dispatchers.Main) {
            current.javaClass.getDeclaredMethod("inspectSelection", kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }.invoke(current, null)
        }
        until("the card for '$text'") { card.isOpen }
        delay(500)
    }

    private suspend fun SelectionBook.highlightWords(text: String, color: HighlightColor) {
        selectWords(text)
        press("Highlight ${color.label.lowercase()}")
        until("the card closed") { !card.isOpen }
        delay(300)
    }

    private suspend fun SelectionBook.light(screen: EpubReaderScreen, segment: ReadAlongSegment?) = withContext(Dispatchers.Main) {
        screen.javaClass.getDeclaredMethod("highlightNarration", ReadAlongSegment::class.java).apply { isAccessible = true }.invoke(screen, segment)
    }

    private suspend fun SelectionBook.segment(screen: EpubReaderScreen, fragment: String): ReadAlongSegment = withContext(Dispatchers.Main) {
        voiceOf(screen)!!.timeline.tracks.flatMap { it.segments }.first { it.fragment == fragment }
    }

    /** The boxes the highlights are drawn with on the page, and each one's colour. */
    private suspend fun SelectionBook.highlightBoxes(): List<Pair<RectF, String>> {
        var raw = js("""(function(){return JSON.stringify([].map.call(document.querySelectorAll('[data-group="annotations"] .pd-hl'),function(e){
            var r=e.getBoundingClientRect();return [r.left,r.top,r.right,r.bottom,getComputedStyle(e).backgroundColor];}));})()""").trim()
        if (raw.startsWith("\"")) raw = JSONArray("[$raw]").getString(0)
        val list = JSONArray(raw)
        return (0 until list.length()).map { i -> list.getJSONArray(i).let { RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) to it.getString(4) } }
    }

    /** The colour of the page behind each of [points] (CSS pixels), the words made invisible for the moment it is read. */
    private suspend fun SelectionBook.behind(points: List<Pair<Float, Float>>): List<Int> {
        js("""(function(){var s=document.createElement('style');s.id='pd-test-ink';s.textContent='body, body * { color: transparent !important; -webkit-text-fill-color: transparent !important; text-shadow: none !important; }';document.head.appendChild(s);return 1;})()""")
        delay(700)
        ins.waitForIdleSync()
        val shot: Bitmap = ins.uiAutomation.takeScreenshot() ?: error("no screenshot")
        js("""(function(){var s=document.getElementById('pd-test-ink');if(s)s.remove();return 1;})()""")
        val d = activity.resources.displayMetrics.density
        val page = IntArray(2)
        withContext(Dispatchers.Main) { current.field<EpubNavigatorFragment?>("navigator")!!.view!!.getLocationOnScreen(page) }
        return points.map { (x, y) -> shot.getPixel((x * d + page[0]).toInt(), (y * d + page[1]).toInt()) }
    }

    private suspend fun SelectionBook.wordBox(id: String): RectF {
        var raw = js("""(function(){var e=document.getElementById(${JSONObject.quote(id)});if(!e)return '';var r=e.getBoundingClientRect();return [r.left,r.top,r.right,r.bottom].join(',');})()""").trim('"')
        check(raw.isNotEmpty()) { "no element $id on the page" }
        return raw.split(',').map { it.toFloat() }.let { RectF(it[0], it[1], it[2], it[3]) }
    }

    private fun near(a: Int, b: Int, tolerance: Int = 4) =
        abs(Color.red(a) - Color.red(b)) <= tolerance && abs(Color.green(a) - Color.green(b)) <= tolerance && abs(Color.blue(a) - Color.blue(b)) <= tolerance

    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    private fun annotationsOf(book: SelectionBook): List<ReadingAnnotation> = book.shelf.live.value

    // ------------------------------------------------------------------ 1. a word selected

    /**
     * Ebook → Read along with a word selected: the voice begins at the start of that word's sentence, so the first word lit is the sentence's
     * first word and the toast ("from the start of the sentence you selected") is true. Read along asked for the word edition.
     */
    @Test fun aWordSelectedStartsReadAlongAtTheFirstWordOfItsSentence() = runBlocking {
        val work = "words-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            selectAndWait("lantern")
            pad(y)
            until("the button open and the card closed") { current.field<ModeButtonView>("modeButton").isOpen && !card.isOpen }
            pad(right, right)
            assertEquals(ReadingMode.ALONG, withContext(Dispatchers.Main) { current.field<ModeButtonView>("modeButton").focusedMode })
            pad(a)
            until("Read along asked for", 20_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            assertEquals(ModePlace.Start.SELECTED_SENTENCE, along.field<ModeEntry>("entry").start)
            speaker(along)
            adopt(along)
            val seen = litWords(along) { it.size >= 3 }
            assertTrue("read along asked for the word edition: ${hub.fileQueries()}", hub.fileQueries().any { "granularity=word" in it && "audio=omit" in it })
            assertTrue(withContext(Dispatchers.Main) { voiceOf(along)!!.timeline.wordLevel })
            // "Somewhere above her, the lantern was still burning.": the sixth sentence, s5.
            assertEquals("the first word lit: ${seen.map { it.fragment }}", "s5-w0", seen.first().fragment)
            assertTrue("then the sentence's next words: ${seen.map { it.fragment }}", seen.zipWithNext().all { (x, z) -> rank(z.fragment) > rank(x.fragment) })
            assertTrue(notes.toString(), notes.any { it == "Reading along from the start of the sentence you selected" })
            assertTrue(withContext(Dispatchers.Main) { voiceOf(along)!!.isPlaying })
            shot("word-along-from-selection")
        }
    }

    // ------------------------------------------------------------------ 2. nothing selected

    @Test fun withNothingSelectedReadAlongBeginsAtTheFirstWordOfThePage() = runBlocking {
        val work = "words-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            pad(y, right, right, a)
            until("Read along asked for", 20_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            assertEquals(ModePlace.Start.TOP_OF_PAGE, along.field<ModeEntry>("entry").start)
            speaker(along)
            adopt(along)
            val seen = litWords(along) { it.size >= 2 }
            assertEquals("the page's first word: ${seen.map { it.fragment }}", "s0-w0", seen.first().fragment)
            assertTrue(notes.toString(), notes.any { it == "Reading along from the top of your page" })
        }
    }

    // ------------------------------------------------------------------ 3. back to the ebook from a word, and on from there

    /**
     * Read along → Ebook while a word is lit: the ebook opens on that word's sentence, the underline under all of it (not one word) and the tab
     * in the margin, and the place kept is the sentence's id, never a word's. Then Ebook → Read along with nothing selected goes on from there:
     * the first word lit is that sentence's first.
     */
    @Test fun backToTheEbookFromAWordMarksItsWholeSentenceKeepsTheSentenceAndReadAlongGoesOnFromIt() = runBlocking {
        val work = "words-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            selectAndWait("palm against")
            pad(y, right, right, a)
            until("Read along asked for", 20_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            speaker(along)
            adopt(along)
            // A few words into "In spite of the cold, she pulled off her gloves and set her palm against the lighthouse door." (s3).
            litWords(along) { seen -> seen.size >= 4 }
            var word: ReadAlongSegment? = null
            withContext(Dispatchers.Main) {
                along.onPad(y); delay(400); along.onPad(left); delay(300); along.onPad(left); delay(300)
                word = lit(along)
                along.onPad(a)
            }
            val heard = word!!
            assertTrue("a word was lit: ${heard.fragment}", heard.isWord && heard.fragment.startsWith(heard.sentenceFragment + "-w"))
            until("the ebook asked for", 20_000) { pushed.size == 2 }
            val ebook = pushed[1] as EpubReaderScreen
            val entry = ebook.field<ModeEntry>("entry")
            val sentence = sentences[sentenceOf(heard)]
            assertEquals("the anchor is the whole sentence of ${heard.fragment}", sentence, entry.anchor?.quote?.highlight)
            assertEquals("EPUB/one.xhtml", entry.anchor?.document)
            // What read along kept as its place, here and on the hub: a sentence, never a word.
            until("read along's place on the hub", 15_000) { "fragments" in this.hub.heldTextPlace() }
            val place = JSONObject(this.hub.heldTextPlace())
            val fragments = place.getJSONObject("locations").optJSONArray("fragments")
            assertNotNull("the place names its sentence: $place", fragments)
            for (i in 0 until fragments!!.length()) {
                val id = fragments.getString(i)
                assertTrue("the place kept is a sentence, not a word: $place", Regex("""s\d+""").matches(id))
            }
            assertEquals("the place is the lit word's sentence or the one after it: $place", true,
                fragments.getString(0).removePrefix("s").toInt() in sentenceOf(heard)..sentenceOf(heard) + 1)
            speaker(ebook)
            adopt(ebook)
            until("the sentence is marked", 30_000) { ebook.field<SentenceAnchor?>("heardAnchor") != null }
            var marked = 0
            for (i in 0 until 80) { marked = count("[data-group=\"heard\"] .pd-heard"); if (marked > 0) break; delay(200) }
            assertTrue("the underline is on the page: $marked", marked > 0)
            delay(800)
            // Under the whole sentence: from its first word on its first line to its last word on its last line.
            val (firstLine, lastLine, whole) = lines(sentence)
            var raw = js("""(function(){return JSON.stringify([].map.call(document.querySelectorAll('[data-group="heard"] .pd-heard'),function(e){var r=e.getBoundingClientRect();return [r.left,r.top,r.right,r.bottom];}));})()""").trim()
            if (raw.startsWith("\"")) raw = JSONArray("[$raw]").getString(0)
            val under = JSONArray(raw).let { l -> (0 until l.length()).map { i -> l.getJSONArray(i).let { RectF(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat(), it.getDouble(3).toFloat()) } } }
                .filter { it.width() > 0 && it.right > 0 }
            fun on(line: RectF) = under.filter { minOf(it.bottom, line.bottom) - maxOf(it.top, line.top) > line.height() / 2 }
            assertTrue("the underline starts at the sentence's first word: ${on(firstLine)} under $firstLine (sentence $whole)",
                on(firstLine).any { it.left <= firstLine.left + 3 && it.right >= firstLine.right - 3 })
            assertTrue("and ends at its last word: ${on(lastLine)} under $lastLine (sentence $whole)",
                on(lastLine).any { it.left <= lastLine.left + 3 && it.right >= lastLine.right - 3 })
            if (lastLine.top > firstLine.top + 2) assertTrue("a line of underline for each of the sentence's: $under", under.size >= 2)
            assertTrue("the tab is in the margin", count("[data-group=\"heard\"] .pd-tab-bar") > 0)
            shot("word-heard-to-here")
            // Ebook → Read along, nothing selected: on from where the voice stopped, at the first word of that sentence.
            withContext(Dispatchers.Main) { ebook.onPad(y) }
            until("the button open") { ebook.field<ModeButtonView>("modeButton").isOpen }
            withContext(Dispatchers.Main) { ebook.onPad(right); delay(300); ebook.onPad(right); delay(300); ebook.onPad(a) }
            until("Read along asked for again", 20_000) { pushed.size == 3 }
            val again = pushed[2] as EpubReaderScreen
            assertEquals(ModePlace.Start.WHERE_VOICE_STOPPED, again.field<ModeEntry>("entry").start)
            speaker(again)
            adopt(again)
            val seen = litWords(again) { it.size >= 2 }
            assertEquals("on from the sentence of ${heard.fragment}: ${seen.map { it.fragment }}", "${heard.sentenceFragment}-w0", seen.first().fragment)
            assertTrue(notes.toString(), notes.any { it == "Going on from where the voice stopped" })
        }
    }

    // ------------------------------------------------------------------ 4. Read along and Audio, across a stretch

    /**
     * Read along → Audio → Read along on the word edition, the voice going from the first stretch of narration (voice.wav) into the second
     * (voice2.wav): the words are lit in order without one twice; the audiobook goes on from where read along was, neither ahead of it nor
     * further back than the word being said; and back in read along the word lit first is the one the audiobook was saying.
     */
    @Test fun readAlongAndAudioCarryTheVoiceOnAcrossAStretchWithoutAJumpOrARepeat() = runBlocking {
        val work = "words-${System.nanoTime()}"
        val hub = stand(work)
        withSelectionBook(stand = hub, workId = work, sourceItemId = book) {
            // "lantern" is in s5, the last sentence of the first stretch.
            selectAndWait("lantern")
            pad(y, right, right, a)
            until("Read along asked for", 20_000) { pushed.isNotEmpty() }
            val along = pushed.single() as EpubReaderScreen
            speaker(along)
            adopt(along)
            val seen = litWords(along, 60_000) { list -> list.lastOrNull()?.let { sentenceOf(it) == SPLIT && order(it.fragment).second >= 2 } == true }
            val voice = withContext(Dispatchers.Main) { voiceOf(along)!! }
            assertEquals("two stretches", 2, voice.timeline.tracks.size)
            assertEquals("the voice is in the second", 1, withContext(Dispatchers.Main) { voice.position.track })
            val fragments = seen.map { it.fragment }
            assertTrue("in order, none twice: $fragments", seen.zipWithNext().all { (x, z) -> rank(z.fragment) > rank(x.fragment) })
            assertTrue("through the end of s5 into s6: $fragments", fragments.any { it.startsWith("s5-w") } && fragments.any { it.startsWith("s6-w") })
            // To Audio: Ⓨ ◀ Ⓐ, the moment and the word read at Ⓐ.
            var atSwitch = 0L
            var wordAtSwitch: ReadAlongSegment? = null
            withContext(Dispatchers.Main) {
                along.onPad(y); delay(400); along.onPad(left); delay(300)
                atSwitch = trackMs(voice); wordAtSwitch = lit(along)
                along.onPad(a)
            }
            until("the audiobook asked for", 20_000) { pushed.size == 2 }
            val audio = pushed[1] as AudiobookScreen
            val carried = audio.field<ModeEntry>("entry")
            val place = carried.audioPlace!!
            val word = wordAtSwitch!!
            val wordLength = word.endMs - word.beginMs
            android.util.Log.i("WORDMODES", "to audio: voice at $atSwitch ms on ${word.fragment} (${wordLength} ms), carried ${place.offsetMs} ms")
            assertTrue("no jump ahead: carried ${place.offsetMs}, voice at $atSwitch", place.offsetMs <= atSwitch + 100)
            assertTrue("no further back than the word being said: carried ${place.offsetMs}, voice at $atSwitch, ${word.fragment} $wordLength ms",
                atSwitch - place.offsetMs <= wordLength + 100)
            assertTrue("it plays on", carried.playing)
            adopt(audio)
            until("the audiobook plays from there", 45_000) {
                val now = ReadingAudio.state.value
                now.book != null && now.playing && abs(now.positionMs - place.offsetMs) < 2_500
            }
            delay(1_500)
            // Back to Read along: Ⓨ ▶ Ⓐ.
            withContext(Dispatchers.Main) { audio.onPad(y) }
            until("the audiobook's button open") { audio.field<ModeButtonView>("modeButton").isOpen }
            var audioAt = 0L
            withContext(Dispatchers.Main) { audio.onPad(right); delay(300); audioAt = ReadingAudio.state.value.positionMs; audio.onPad(a) }
            until("Read along asked for again", 20_000) { pushed.size == 3 }
            val back = pushed[2] as EpubReaderScreen
            val place2 = back.field<ModeEntry>("entry").audioPlace!!
            android.util.Log.i("WORDMODES", "to read along: audio at $audioAt ms, carried ${place2.offsetMs} ms")
            assertTrue("the audiobook's place is carried: ${place2.offsetMs} against $audioAt", abs(place2.offsetMs - audioAt) < 1_500)
            speaker(back)
            adopt(back)
            val again = litWords(back) { it.isNotEmpty() }
            assertEquals("the word lit first is the one the audiobook was saying at ${place2.offsetMs} ms", wordAt(place2.offsetMs), again.first().fragment)
            val first = withContext(Dispatchers.Main) { trackMs(voiceOf(back)!!) }
            assertTrue("read along begins where the audiobook was: $first against ${place2.offsetMs}", first >= place2.offsetMs - 600)
        }
    }

    // ------------------------------------------------------------------ 5. the selection bar in the word spans

    @Test fun inAWordEditionOneWordOpensTheCardWithItsSpeakerAndAPhraseAcrossWordsIsLookedUp() = runBlocking {
        val work = "words-${System.nanoTime()}"
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) {
            val along = alongScreen(work)
            adopt(along)
            ready(along)
            // One word: its own span.
            assertTrue("the words are spans of their own", count("span[id^=\"s5-w\"]") >= 8)
            selectWords("lantern")
            withContext(Dispatchers.Main) {
                assertEquals("lantern", card.headingText.toString())
                assertTrue(card.showsDefinitions)
                assertTrue(card.definitionText.length > 20)
                assertEquals(listOf("Say it", "Highlight yellow", "Highlight blue", "Highlight pink", "Highlight green", "Add a note", "Copy the words"),
                    card.controlLabels.map { it.toString() })
            }
            val cardBox = withContext(Dispatchers.Main) { onScreen(card.bounds) }
            assertFalse("the card covers the word: card=$cardBox word=$selected", RectF.intersects(cardBox, selected))
            press("Say it")
            assertEquals(listOf("lantern"), voice.said)
            shot("word-card")
            press("Copy the words")
            until("the card closed") { !card.isOpen }
            // A phrase across two word spans, which the dictionary has as one entry.
            selectWords("pulled off")
            withContext(Dispatchers.Main) {
                assertEquals("pull off", card.headingText.toString())
                assertTrue(card.showsDefinitions)
            }
            press("Say the phrase")
            assertEquals(listOf("lantern", "pulled off"), voice.said)
            shot("word-card-phrase")
            press("Copy the words")
            until("the card closed") { !card.isOpen }
            // And one it has not, across three spans: the bar, and Look up says so.
            selectWords("harbor bells were")
            delay(700)
            withContext(Dispatchers.Main) { assertFalse(card.showsDefinitions) }
            press("Look it up in the dictionary")
            until("the answer") { card.showsDefinitions }
            withContext(Dispatchers.Main) {
                assertEquals("harbor", card.headingText.toString())
                assertEquals("No entry for “harbor bells were”. Showing “harbor”.", card.noteText.toString())
            }
        }
    }

    // ------------------------------------------------------------------ 5. highlights between the word edition and the ebook

    /**
     * The four colours and a note made in Read along (inside the word spans) are drawn over the same words in the plain ebook, and one made in
     * the ebook over the same words in Read along; the same words selected in either keep the same document and quote, which is what another
     * device resolves.
     */
    @Test fun highlightsMadeInReadAlongShowOnTheSameWordsInTheEbookAndTheOtherWayRound() = runBlocking {
        val work = "words-${System.nanoTime()}"
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) {
            val along = alongScreen(work)
            adopt(along)
            ready(along)
            val made = linkedMapOf("harbor bells" to HighlightColor.YELLOW, "soft as flour" to HighlightColor.BLUE,
                "pulled off her gloves" to HighlightColor.PINK, "lantern was still" to HighlightColor.GREEN)
            for ((words, color) in made) highlightWords(words, color)
            // A note, on a passage across several spans.
            selectWords("stair wound up")
            press("Add a note")
            until("the note's sheet") { overlay.isOpen }
            withContext(Dispatchers.Main) { all(overlay).filterIsInstance<android.widget.EditText>().single().setText("Up into the dark.") }
            withContext(Dispatchers.Main) { overlay.rows.first { all(it).filterIsInstance<android.widget.TextView>().any { t -> t.text == "Save" } }.performClick() }
            until("the sheet closed") { !overlay.isOpen }
            val inAlong = annotationsOf(this)
            assertEquals(5, inAlong.size)
            for ((words, color) in made) {
                val kept = inAlong.single { it.quote.highlight == words }
                assertEquals(color.id, kept.color)
                assertEquals("EPUB/one.xhtml", kept.document)
            }
            // The quote is the page's text, the same as the plain book has it.
            val pink = inAlong.single { it.quote.highlight == "pulled off her gloves" }
            assertTrue(pink.quote.toString(), pink.quote.before.trimEnd().endsWith("In spite of the cold, she") && pink.quote.after.trimStart().startsWith("and set her palm"))
            until("the hub holds all five", 20_000) { hub.stored().size == 5 }
            delay(600)
            shot("word-highlights-along")
            // The plain ebook: each is drawn over its words, in its colour.
            val ebook = ebookScreen(work)
            adopt(ebook)
            until("the ebook", 30_000) { ebook.field<Any?>("navigator") != null && ebook.field<View>("loading").visibility != View.VISIBLE }
            until("the highlights arrived", 20_000) { annotationsOf(this).size == 5 }
            val noted = annotationsOf(this).single { it.quote.highlight == "stair wound up" }
            assertEquals("Up into the dark.", noted.note)
            checkDrawn(made + ("stair wound up" to noted.highlightColor))
            var notes = 0
            for (i in 0 until 50) { notes = count("[data-group=\"annotation-notes\"] [data-style]"); if (notes > 0) break; delay(100) }
            val noteBoxes = js("""(function(){return JSON.stringify([].map.call(document.querySelectorAll('[data-group="annotation-notes"] .pd-note'),function(e){var r=e.getBoundingClientRect();return [r.left,r.top,r.width,r.height];}));})()""")
            val (upFirst, upLast, _) = lines("stair wound up")
            if (notes != 1) android.util.Log.i("WORDMODES", "note items: " +
                js("""(function(){return JSON.stringify(readium.getDecorations('annotation-notes').items.map(function(i){return i.decoration.id;}));})()"""))
            assertEquals("the note's mark, one: $noteBoxes (the passage from $upFirst to $upLast)", 1, notes)
            assertEquals("one box, so one mark is drawn: $noteBoxes (the passage from $upFirst to $upLast)", 1, count("[data-group=\"annotation-notes\"] .pd-note"))
            shot("word-highlights-ebook")
            // The same words selected in the ebook carry the same document and quote as the highlight made in Read along.
            for (words in made.keys) {
                selectWords(words)
                val chosen = current.field<ReaderSelection?>("selection")!!
                val kept = annotationsOf(this).single { it.quote.highlight == words }
                assertEquals("'$words' in the ebook", kept.document, chosen.document)
                assertEquals("'$words' in the ebook", kept.quote, chosen.quote)
                withContext(Dispatchers.Main) { current.onPad(PadAction.Back) }
                until("the card closed") { !card.isOpen }
            }
            // And one made in the ebook shows in Read along.
            highlightWords("lighthouse door", HighlightColor.BLUE)
            until("the hub holds six", 20_000) { hub.stored().size == 6 }
            val fromEbook = annotationsOf(this).single { it.quote.highlight == "lighthouse door" }
            val again = alongScreen(work)
            adopt(again)
            ready(again)
            until("the highlights arrived in read along", 20_000) { annotationsOf(this).size == 6 }
            checkDrawn(made + ("stair wound up" to noted.highlightColor) + ("lighthouse door" to HighlightColor.BLUE))
            selectWords("lighthouse door")
            val chosen = current.field<ReaderSelection?>("selection")!!
            assertEquals(fromEbook.document, chosen.document)
            assertEquals(fromEbook.quote, chosen.quote)
            shot("word-highlights-along-again")
        }
    }

    /**
     * A book opened with highlights already kept (another device's, here) draws each one once, in Read along and in the ebook: a highlight
     * drawn twice is not seen until it is removed, and then one copy stays on the page. Removed from its menu, nothing of it is left.
     */
    @Test fun aBookOpenedWithItsHighlightsDrawsEachOnceAndOneRemovedLeavesNothing() = runBlocking {
        val work = "words-${System.nanoTime()}"
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book, prepare = {
            elsewhere("an_" + "c".repeat(32), "yellow", "EPUB/one.xhtml", "The ", "harbor bells", " were still ringing when Maren")
            elsewhere("an_" + "d".repeat(32), "blue", "EPUB/one.xhtml", "fallen through the night, ", "soft as flour", ", and it lay", note = "Like flour")
            elsewhere("an_" + "e".repeat(32), "green", "EPUB/one.xhtml", "Somewhere above her, the ", "lantern was still", " burning.")
        }) {
            val problems = ArrayList<String>()
            suspend fun drawnOnce(where: String, kept: Int) {
                until("$where: the highlights arrived", 20_000) { annotationsOf(this).size == kept }
                var items = 0
                for (i in 0 until 60) { items = count("[data-group=\"annotations\"] [data-style]"); if (items >= kept) break; delay(150) }
                delay(2_000)
                items = count("[data-group=\"annotations\"] [data-style]")
                val marks = count("[data-group=\"annotation-notes\"] [data-style]")
                android.util.Log.i("WORDMODES", "$where: $items highlight items, $marks note items")
                problems += listOfNotNull(
                    "$where: each highlight drawn once, but $items items for $kept".takeIf { items != kept },
                    "$where: the note's mark drawn once, but $marks".takeIf { marks != 1 })
            }
            drawnOnce("the ebook", 3)
            // Removed from its menu in the ebook: nothing of it is left.
            val bells = toScreen(findText("harbor bells", select = false).first)
            tap(bells.centerX(), bells.centerY())
            until("the highlight's menu in the ebook") { card.isOpen }
            press("Remove the highlight")
            until("the menu closed") { !card.isOpen }
            delay(1_500)
            val (bellsLine, _) = findText("harbor bells", select = false)
            val ghost = highlightBoxes().filter { (box, _) -> box.contains(bellsLine.centerX(), bellsLine.centerY()) }
            if (ghost.isNotEmpty()) problems += "the ebook: the removed highlight is still drawn: $ghost"
            shot("word-ebook-highlight-removed")
            until("two left", 20_000) { annotationsOf(this).size == 2 }
            val along = alongScreen(work)
            adopt(along)
            ready(along)
            drawnOnce("read along", 2)
            // Removed from its menu: no box of it is left over its words.
            val word = toScreen(findText("soft as flour", select = false).first)
            tap(word.centerX(), word.centerY())
            until("the highlight's menu") { card.isOpen }
            press("Remove the highlight")
            until("the menu closed") { !card.isOpen }
            delay(1_500)
            val (first, _) = findText("soft as flour", select = false)
            val left = highlightBoxes().filter { (box, _) -> box.contains(first.centerX(), first.centerY()) }
            if (left.isNotEmpty()) problems += "the removed highlight is still drawn: $left"
            count("[data-group=\"annotation-notes\"] [data-style]").let { if (it != 0) problems += "its note's mark is still drawn: $it" }
            count("[data-group=\"annotations\"] [data-style]").let { if (it != 1) problems += "read along: the other one stays, but $it items" }
            shot("word-highlight-removed")
            assertTrue(problems.joinToString("; "), problems.isEmpty())
        }
    }

    /** Every highlight in [made] has a box of its colour over its words' first line on the page in front. */
    private suspend fun SelectionBook.checkDrawn(made: Map<String, HighlightColor>) {
        val (page, ink) = withContext(Dispatchers.Main) { current.javaClass.getDeclaredMethod("pageColors").apply { isAccessible = true }.invoke(current) as Pair<Int, Int> }
        var boxes = emptyList<Pair<RectF, String>>()
        for (i in 0 until 60) { boxes = highlightBoxes(); if (boxes.size >= made.size) break; delay(150) }
        for ((words, color) in made) {
            val (first, _) = findText(words, select = false)
            val want = ReadAlongGlow.rgb(ReaderMarks.tint(color, page, ink))
            val over = boxes.filter { (box, _) -> box.contains(first.centerX(), first.centerY()) }
            assertTrue("'$words' has a box over it: words $first, boxes $boxes", over.isNotEmpty())
            assertTrue("'$words' is $want: ${over.map { it.second }}", over.any { it.second == want })
        }
    }

    // ------------------------------------------------------------------ 6. a highlight under the moving word

    /**
     * A highlight saved on words the voice is saying: the word and the trail are drawn over it, the highlight shows on the rest of its words,
     * and the ink is 4.5:1 on every one of them; when the voice has gone on, the highlight is whole again. On Paper and on Dark, the
     * highlight made after the word was lit (its boxes later in the page than the narration's).
     */
    @Test fun aHighlightUnderTheMovingWordShowsTheWordAndTrailOnTopAndTheInkStaysReadable() = runBlocking {
        for (theme in listOf(EpubTheme.LIGHT, EpubTheme.BLACK)) {
            val work = "words-${System.nanoTime()}"
            withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) {
                EpubAppearanceStore.save(activity, EpubReaderPreferences(theme = theme, columns = EpubColumns.ONE))
                val along = alongScreen(work)
                adopt(along)
                ready(along)
                // s3: In(0) spite(1) of(2) the(3) cold(4) she(5) pulled(6) off(7) her(8) gloves(9) and(10) set(11) her(12) palm(13) ...
                light(along, segment(along, "s3-w7"))
                delay(1_400)
                highlightWords("pulled off her gloves and set", HighlightColor.YELLOW)
                // The selection's card may have redrawn the page: the voice's word is lit again where it was.
                light(along, segment(along, "s3-w7"))
                delay(1_400)
                val (page, ink) = EpubPagePalette.of(theme)
                val dark = ReadAlongWordHighlight.isDark(theme)
                val tints = ReadAlongWordHighlight.tints(ReadAlongHighlights().of(theme), page, ink, dark)
                val marked = ReaderMarks.tint(HighlightColor.YELLOW, page, ink)
                val ids = listOf("s3-w5", "s3-w6", "s3-w7", "s3-w8", "s3-w11", "s3-w13")
                val boxes = ids.map { wordBox(it) }
                val colours = behind(boxes.map { it.centerX() to it.centerY() })
                val got = ids.zip(colours).joinToString { (id, c) -> "$id ${hex(c)}" }
                android.util.Log.i("WORDMODES", "$theme: $got; word ${hex(tints.word)} trail ${hex(tints.trail!!)} highlight ${hex(marked)} page ${hex(page)}")
                shot("word-under-highlight-${theme.name.lowercase()}")
                val want = listOf(tints.trail!!, tints.trail!!, tints.word, marked, marked, page)
                ids.indices.forEach { i ->
                    assertTrue("$theme ${ids[i]}: ${hex(colours[i])}, wanted ${hex(want[i])} (word ${hex(tints.word)}, trail ${hex(tints.trail!!)}, highlight ${hex(marked)}); all: $got",
                        near(colours[i], want[i]))
                    assertTrue("$theme ${ids[i]}: the ink reads at ${ReadAlongGlow.contrast(ink, colours[i])}", ReadAlongGlow.contrast(ink, colours[i]) >= ReadAlongGlow.MIN_CONTRAST)
                }
                // The voice goes on to the next sentence: the highlight is whole again.
                light(along, segment(along, "s4-w0"))
                delay(1_400)
                val after = behind(listOf(boxes[1], boxes[2]).map { it.centerX() to it.centerY() })
                assertTrue("$theme: the highlight is whole again: ${after.map(::hex)} against ${hex(marked)}", after.all { near(it, marked) })
            }
        }
    }

    // ------------------------------------------------------------------ 7. a selection drag

    @Test fun aSelectionDragInAWordEditionNeverTurnsThePage() = runBlocking {
        val work = "words-${System.nanoTime()}"
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) {
            val along = alongScreen(work)
            adopt(along)
            ready(along)
            val locator = { along.field<org.readium.r2.shared.publication.Locator?>("latestLocator") }
            until("the page's place is known") { locator() != null }
            val before = withContext(Dispatchers.Main) { locator()!!.locations.progression }
            val pageBefore = withContext(Dispatchers.Main) { along.field<Int>("pageIndex") }
            val from = toScreen(findText("stone", select = false).first)
            val to = toScreen(findText("harbor", select = false).first)
            drag(from.centerX(), from.centerY(), to.left, to.centerY(), holdMs = 700, moveMs = 300)
            delay(1_500)
            val after = withContext(Dispatchers.Main) { locator()!!.locations.progression }
            shot("word-selection-drag")
            assertEquals("the page turned under a selection drag ($before, then $after)", before, after)
            assertEquals(pageBefore, withContext(Dispatchers.Main) { along.field<Int>("pageIndex") })
            assertTrue("the long press selected words", withContext(Dispatchers.Main) { card.isOpen })
        }
    }

    // ------------------------------------------------------------------ 8. fast turns, and the voice turning the page

    private fun finger(x0: Float, y0: Float, x1: Float, y1: Float, durationMs: Long) {
        val down = SystemClock.uptimeMillis()
        fun send(at: Long, action: Int, x: Float, y: Float) {
            val event = MotionEvent.obtain(down, down + at, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            ins.sendPointerSync(event)
            event.recycle()
        }
        send(0, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = maxOf(4, (durationMs / 8).toInt())
        for (i in 1..steps) send(durationMs * i / steps, MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        send(durationMs, MotionEvent.ACTION_UP, x1, y1)
    }

    private fun swipe(root: View, forward: Boolean) {
        val at = IntArray(2).also(root::getLocationOnScreen)
        val x0 = at[0] + if (forward) root.width * 0.72f else root.width * 0.28f
        val x1 = if (forward) x0 - root.width * 0.40f else x0 + root.width * 0.40f
        val y = at[1] + root.height * 0.5f
        finger(x0, y, x1, y, 90)
    }

    private suspend fun SelectionBook.settledPage(screen: EpubReaderScreen): Int {
        var last = -1
        var since = System.currentTimeMillis()
        val start = since
        while (System.currentTimeMillis() - start < 15_000) {
            val now = withContext(Dispatchers.Main) { screen.field<Int>("pageIndex") }
            if (now != last) { last = now; since = System.currentTimeMillis() } else if (System.currentTimeMillis() - since >= 1_000) break
            delay(50)
        }
        return last
    }

    /**
     * Read along by the word on a book of many pages: bursts of swipes, forward, back and mixed, 90 ms apart, turn exactly as many pages as
     * were swiped; and with the voice playing, the page it turns to is the one with the word being said on it, the word's own box on the page.
     */
    @Test fun inReadAlongByTheWordABurstOfSwipesTurnsThatManyPagesAndTheHighlightFollowsTheVoice() = runBlocking {
        val work = "words-${System.nanoTime()}"
        val long = StandInHub(work, book, listOf(370), alignment = listOf(Triple("EPUB/voice.wav", 0, 0L)),
            whole = ReaderFixtures.longEpub(), slim = ReaderFixtures.longEpub(), words = ReaderFixtures.longEpub(words = true))
        withSelectionBook(stand = long, workId = work, sourceItemId = book) {
            EpubAppearanceStore.save(activity, EpubReaderPreferences(columns = EpubColumns.ONE))
            val along = alongScreen(work)
            val root = adopt(along)
            ready(along)
            val log = StringBuilder()
            for ((name, moves) in listOf("forward" to List(6) { 1 }, "back" to List(4) { -1 }, "mixed" to listOf(1, 1, -1, 1, 1, -1, 1))) {
                val before = settledPage(along)
                for (move in moves) { swipe(root, move > 0); delay(90) }
                val after = settledPage(along)
                log.append("$name: sent ${moves.size} (net ${moves.sum()}), from $before to $after; ")
                assertEquals("$name: $log", moves.sum(), after - before)
            }
            android.util.Log.i("WORDMODES", "turns: $log")
            // The voice from the last sentence on this page: it turns the page when its word is on the next one.
            until("the page looked at") { along.field<PageSpan?>("pageSpan") != null }
            val span = withContext(Dispatchers.Main) { along.field<PageSpan>("pageSpan") }
            val voice = withContext(Dispatchers.Main) { voiceOf(along)!! }
            val page = settledPage(along)
            val lastOnPage = span.visible.maxByOrNull { rank(it) }!!
            withContext(Dispatchers.Main) {
                val at = voice.timeline.find("EPUB/one.xhtml", lastOnPage)!!
                voice.seek(at)
                if (!voice.isPlaying) voice.toggle()
            }
            until("the voice turned the page", 30_000) { along.field<Int>("pageIndex") == page + 1 }
            delay(1_500)
            val word = withContext(Dispatchers.Main) { lit(along)!! }
            var raw = js("""(function(){var b=[].filter.call(document.querySelectorAll('.${ReadAlongGlow.WORD_CLASS}'),function(e){return getComputedStyle(e).display!=='none'&&e.getBoundingClientRect().width>0;});
                return JSON.stringify(b.map(function(e){var r=e.getBoundingClientRect();return [r.left,r.right,innerWidth];}));})()""").trim()
            if (raw.startsWith("\"")) raw = JSONArray("[$raw]").getString(0)
            val boxes = JSONArray(raw)
            assertTrue("the word's box is drawn: $raw (${word.fragment})", boxes.length() > 0)
            for (i in 0 until boxes.length()) {
                val b = boxes.getJSONArray(i)
                assertTrue("the word's box is on the page in front: $raw", b.getDouble(0) >= -8 && b.getDouble(1) <= b.getDouble(2) + 8)
            }
            val shownSpan = withContext(Dispatchers.Main) { along.field<PageSpan?>("pageSpan") }
            assertTrue("the page in front has the word's sentence: ${word.fragment} on ${shownSpan?.visible}", shownSpan == null || word.sentenceFragment in shownSpan.visible)
            assertEquals("one page on", page + 1, withContext(Dispatchers.Main) { along.field<Int>("pageIndex") })
            withContext(Dispatchers.Main) { voice.pause() }
            shot("word-voice-turned-page")
        }
    }

    private companion object {
        const val SECONDS = 4
        const val SPLIT = 6
        const val TRACK_SECONDS = 50

        /** Finds a string across the page's text nodes (not the decorations'): the first line's box and the whole box, selected or not. */
        const val FIND = """function(find, select){
            var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){return n.parentElement&&n.parentElement.closest('[data-group]')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;}});
            var nodes=[],all='',n;
            while((n=w.nextNode())){nodes.push([n,all.length]);all+=n.nodeValue;}
            var i=all.indexOf(find);if(i<0)return 'none';
            function at(k,end){for(var j=0;j<nodes.length;j++){var s=nodes[j][1],l=nodes[j][0].nodeValue.length;if(end?(k>s&&k<=s+l):(k>=s&&k<s+l))return [nodes[j][0],k-s];}return null;}
            var x=at(i,false),z=at(i+find.length,true);
            var r=document.createRange();r.setStart(x[0],x[1]);r.setEnd(z[0],z[1]);
            if(select){var s=getSelection();s.removeAllRanges();s.addRange(r);}
            var q=[].filter.call(r.getClientRects(),function(c){return c.width>0;}),f=q[0],e=q[q.length-1],b=r.getBoundingClientRect();
            return 'ok '+[f.left,f.top,f.right,f.bottom].join(',')+';'+[e.left,e.top,e.right,e.bottom].join(',')+';'+[b.left,b.top,b.right,b.bottom].join(',');}"""
    }
}

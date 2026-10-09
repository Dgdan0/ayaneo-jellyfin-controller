package com.pocketds.hub.ui

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketds.hub.reader.AnnotationQuote
import com.pocketds.hub.reader.EpubAppearanceStore
import com.pocketds.hub.reader.EpubColumns
import com.pocketds.hub.reader.EpubReaderPreferences
import com.pocketds.hub.reader.EpubReaderScreen
import com.pocketds.hub.reader.ModeEntry
import com.pocketds.hub.reader.ModePlace
import com.pocketds.hub.reader.ReadingMode
import com.pocketds.hub.reader.SentenceAnchor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * "Heard to here" on the real reader (#62): an underline under the sentence and a tab in the page's side margin beside its first line,
 * and nothing drawn over a word. Measured on the page itself, in the text's largest size with its tightest lines and the narrowest margins,
 * in one column and in two (and as the book stands by default): the tab is a visible bar clear of every word's box, level with the sentence's
 * first line, inside the page, and the mark says "Heard to here" to a screen reader. Generated book; nothing reaches a real server or book.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderHeardMarkTest {
    private val book = "book${System.nanoTime()}"
    private val sentence = "In spite of the cold, she pulled off her gloves and set her palm against the lighthouse door."

    /** A rectangle from the page, in CSS pixels in the page's viewport. */
    private data class Box(val l: Double, val t: Double, val r: Double, val b: Double) {
        fun overlaps(o: Box, tolerance: Double = 0.5) = minOf(r, o.r) - maxOf(l, o.l) > tolerance && minOf(b, o.b) - maxOf(t, o.t) > tolerance
        override fun toString() = "[%.1f,%.1f %.1f,%.1f]".format(l, t, r, b)
    }

    private data class Measure(val width: Double, val height: Double, val columns: String, val tab: Box?, val label: String, val paint: String,
        val underlines: List<Box>, val words: List<Box>)

    private fun box(o: JSONObject) = Box(o.getDouble("l"), o.getDouble("t"), o.getDouble("r"), o.getDouble("b"))
    private fun boxes(a: JSONArray) = (0 until a.length()).map { box(a.getJSONObject(it)) }

    private suspend fun SelectionBook.measure(): Measure {
        var raw = js(MEASURE).trim()
        if (raw.startsWith("\"")) raw = JSONArray("[$raw]").getString(0)
        val o = JSONObject(raw)
        return Measure(o.getDouble("w"), o.getDouble("h"), o.getString("cols"), o.optJSONObject("tab")?.let(::box), o.getString("label"), o.getString("paint"),
            boxes(o.getJSONArray("underlines")), boxes(o.getJSONArray("words")))
    }

    private val ins = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun description(p: EpubReaderPreferences) =
        "font ${p.fontScale} lines ${p.lineHeight} margins ${p.pageMargins} columns ${p.columns}"

    /** Opens the ebook at the voice's sentence as a switch from Read along does, in the look [prefs] describes. */
    private suspend fun SelectionBook.openMarked(prefs: EpubReaderPreferences, work: String): EpubReaderScreen {
        EpubAppearanceStore.save(activity, prefs)
        val anchor = SentenceAnchor("EPUB/one.xhtml", AnnotationQuote("", sentence, ""))
        val ebook = EpubReaderScreen(com.pocketds.hub.net.HubClient(activity), work, book, "The Lantern Keeper", { true }, bookPages = 120,
            readAlongAvailable = true, ebookSourceItemId = book,
            alignedEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "readaloud", sourceItemId = book)),
            audioEditions = listOf(com.pocketds.hub.model.ReadingEdition(source = "storyteller", kind = "audiobook", sourceItemId = book)),
            entry = ModeEntry(ReadingMode.ALONG, ModePlace.Start.WHERE_VOICE_STOPPED, anchor))
        adopt(ebook)
        until("the book is open", 40_000) { current.field<Any?>("navigator") != null }
        var shown = 0
        for (i in 0 until 200) { shown = count("[data-group=\"heard\"] .pd-tab-bar"); if (shown > 0) break; delay(200) }
        assertTrue("the tab is on the page: ${js("(function(){var g=document.querySelectorAll('[data-group]');return [].map.call(g,function(e){return e.getAttribute('data-group')+': '+e.outerHTML.slice(0,600);}).join(' | ');})()")}", shown > 0)
        delay(1_500)
        return ebook
    }

    private fun stand(work: String) = StandInHub(work, book, listOf(40), alignment = listOf(Triple("EPUB/voice.wav", 0, 0L)),
        whole = ReaderFixtures.selectionEpub(aligned = false), slim = ReaderFixtures.selectionEpub(aligned = true, sentenceSeconds = 2, withAudio = false))

    /** For each look: open the ebook at the sentence, measure the page, and hold the mark to what it must be. [columns] is what the page must be in. */
    private suspend fun SelectionBook.checkLooks(looks: List<Pair<String, EpubReaderPreferences>>, work: String, columns: String) {
        for ((name, prefs) in looks) {
            val ebook = openMarked(prefs, work)
            val m = measure()
            val why = "${description(prefs)} (columns ${m.columns}, page ${m.width}x${m.height})"
            assertEquals("the page is in $columns column(s): $why", columns, m.columns)
            val tab = m.tab
            assertNotNull("no tab on the page for $why", tab)
            tab!!
            // A visible bar, inside the page (a line may begin a few pixels above its top), in the margin: clear of every word's box.
            assertTrue("the tab has size $tab for $why", tab.r - tab.l >= 4 && tab.b - tab.t >= 8)
            assertTrue("the tab is inside the page $tab for $why", tab.l >= 0 && tab.r <= m.width && tab.t >= -4 && tab.b <= m.height + 4)
            val covered = m.words.filter { it.overlaps(tab) }
            assertTrue("the tab $tab covers words $covered for $why", covered.isEmpty())
            // Level with the sentence's first line: the topmost underline box.
            val first = m.underlines.minByOrNull { it.t }
            assertNotNull("the sentence is underlined for $why", first)
            assertTrue("the tab $tab is not beside the first line $first for $why", tab.t >= first!!.t - 2 && tab.b <= first.b + 2)
            // In the margin: the words of its own column start to its right (nothing between it and the column's edge but space).
            val rightOf = m.words.filter { it.t < tab.b && it.b > tab.t && it.l >= tab.r }
            assertTrue("a word starts right of the tab on its line for $why", rightOf.isNotEmpty())
            // It is painted: the page has made no element's background transparent over it (Readium's stylesheet does that to a plain one).
            assertTrue("the tab is not painted (${m.paint}) for $why", m.paint.startsWith("rgb(") || m.paint.startsWith("rgba(") && !m.paint.endsWith(", 0)"))
            // A screen reader hears it once.
            assertEquals("Heard to here", m.label)
            shot("heard-$name")
            ebook.hashCode()
        }
    }

    /**
     * The Pocket's page is 838 CSS pixels wide, and Readium's stylesheet (which the app does not change) lays out one column below 960,
     * whatever the reader asks for: so on the device the mark is measured in one column, at the sizes the sheet offers at its extremes and
     * as the book stands by default.
     */
    @Test fun theTabStandsInTheMarginBesideTheFirstLineAndNoWordIsCoveredInOneColumn() = runBlocking {
        val work = "heard-${System.nanoTime()}"
        val looks = listOf(
            "default" to EpubReaderPreferences(),
            // The text's largest size with its tightest lines and the narrowest margins.
            "largest-one" to EpubReaderPreferences(fontScale = 2.0f, lineHeight = 1.3f, pageMargins = 0.5f, columns = EpubColumns.ONE),
            "smallest-one" to EpubReaderPreferences(fontScale = 0.7f, lineHeight = 1.3f, pageMargins = 0.5f, columns = EpubColumns.ONE),
            // Asking for two on this page changes nothing, and the mark is still where it must be.
            "largest-two-asked" to EpubReaderPreferences(fontScale = 2.0f, lineHeight = 1.3f, pageMargins = 0.5f, columns = EpubColumns.TWO)
        )
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) { checkLooks(looks, work, "1") }
    }

    /**
     * A page wide enough for Readium's two columns (a screen of 1280 CSS pixels: the density set to 240 for the test, and put back), where a
     * sentence's first line may be in either column and the tab is in the margin of the column it is in.
     */
    @Test fun theTabStandsInTheMarginOfItsColumnWhenThePageIsInTwo() = runBlocking {
        val work = "heard-${System.nanoTime()}"
        val looks = listOf(
            "normal-two" to EpubReaderPreferences(columns = EpubColumns.TWO),
            "largest-two" to EpubReaderPreferences(fontScale = 2.0f, lineHeight = 1.3f, pageMargins = 0.5f, columns = EpubColumns.TWO),
            "smallest-two" to EpubReaderPreferences(fontScale = 0.7f, lineHeight = 1.3f, pageMargins = 0.5f, columns = EpubColumns.TWO)
        )
        shell("wm density 240")
        try {
            delay(1_500)
            withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) { checkLooks(looks, work, "2") }
        } finally {
            shell("wm density reset")
            delay(1_500)
        }
    }

    @Test fun aTapOnTheMarkedSentenceIsThePagesTapAndBringsTheBars() = runBlocking {
        val work = "heard-${System.nanoTime()}"
        withSelectionBook(stand = stand(work), workId = work, sourceItemId = book) {
            val ebook = openMarked(EpubReaderPreferences(), work)
            val at: RectF = rectOf("of the cold")
            assertFalse(withContext(Dispatchers.Main) { ebook.field<Boolean>("controlsVisible") })
            tap(at.centerX(), at.centerY())
            until("the bars come up on a tap on the marked sentence", 8_000) { ebook.field<Boolean>("controlsVisible") }
        }
    }

    private companion object {
        /**
         * What the page has for the mark: the tab's bar (null when none is showing), its accessibility label, the underline boxes, every word's
         * boxes (one a line of each text node, the page's own text and not the decorations'), and the page's size.
         */
        const val MEASURE = """(function(){
            function r(q){return {l:q.left,t:q.top,r:q.right,b:q.bottom};}
            var bar=document.querySelector('[data-group="heard"] .pd-tab-bar');
            var tab=null;if(bar&&getComputedStyle(bar).display!=='none'){var q=bar.getBoundingClientRect();if(q.width>0)tab=r(q);}
            var under=[].slice.call(document.querySelectorAll('[data-group="heard"] .pd-heard')).map(function(e){return r(e.getBoundingClientRect());});
            var words=[];var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);var n;
            while((n=w.nextNode())){
              if(!n.nodeValue.trim())continue;
              if(n.parentElement&&n.parentElement.closest('[data-group]'))continue;
              var g=document.createRange();g.selectNodeContents(n);var rs=g.getClientRects();
              for(var i=0;i<rs.length;i++){var c=rs[i];if(c.width>0&&c.height>0&&c.right>0&&c.left<innerWidth)words.push(r(c));}
            }
            return JSON.stringify({w:innerWidth,h:innerHeight,cols:getComputedStyle(document.documentElement).columnCount,tab:tab,
              label:bar?(bar.getAttribute('aria-label')||''):'',paint:bar?getComputedStyle(bar).backgroundColor:'',underlines:under,words:words});
        })()"""
    }
}

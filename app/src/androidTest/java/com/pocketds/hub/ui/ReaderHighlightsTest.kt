package com.pocketds.hub.ui

import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.reader.AnnotationIds
import com.pocketds.hub.reader.annotationRemote
import com.pocketds.hub.reader.HighlightColor
import com.pocketds.hub.settings.HighlightSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Highlights and notes on the real reader (#62), on a generated book and a stand-in hub: a colour keeps a highlight, draws it in that colour
 * behind the words, remembers the colour and sends it to the hub; a tap on it opens its menu; a note puts its mark at the end of it; the
 * Navigator lists them with their colours and notes, and says so for a passage the book no longer holds.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderHighlightsTest {
    private suspend fun SelectionBook.highlight(text: String, color: HighlightColor) {
        selectAndWait(text)
        press("Highlight ${color.label.lowercase()}")
        until("the card closed") { !card.isOpen }
    }

    private suspend fun SelectionBook.openTab(id: String) = withContext(Dispatchers.Main) {
        all(overlay).filterIsInstance<BlobSegmentedView>().single().onPick?.invoke(id)
    }

    @Test fun aColourMakesAHighlightDrawnInThatColourRememberedAndSentToTheHub() = runBlocking {
        withSelectionBook {
            selectAndWait("gloves")
            press("Highlight pink")
            until("the card closed") { !card.isOpen }
            val kept = shelf.live.value
            assertEquals(1, kept.size)
            assertEquals("pink", kept[0].color)
            assertEquals("gloves", kept[0].quote.highlight)
            assertEquals("EPUB/one.xhtml", kept[0].document)
            assertTrue("the words around it are kept", kept[0].quote.before.endsWith("pulled off her") && kept[0].quote.after.startsWith("and set"))
            assertEquals(HighlightColor.PINK, withContext(Dispatchers.Main) { HighlightSettings.color(activity) })
            until("the highlight on the page", 8_000) { true }
            var drawn = 0
            for (i in 0 until 40) { drawn = count("[data-group=\"annotations\"] .pd-hl"); if (drawn > 0) break; delay(100) }
            assertTrue("one box over the word, $drawn", drawn >= 1)
            var sent = false
            for (i in 0 until 60) { if (hub.stored().any { it.getString("id") == kept[0].id }) { sent = true; break }; delay(150) }
            assertTrue("the hub holds it: ${hub.log}", sent)
            val held = hub.stored().first { it.getString("id") == kept[0].id }
            assertEquals("pink", held.getString("color"))
            assertEquals("gloves", held.getJSONObject("quote").getString("highlight"))
            shot("highlight-pink")
        }
    }

    @Test fun theFourColoursAreFourDistinctWashesBehindTheWords() = runBlocking {
        withSelectionBook {
            highlight("harbor", HighlightColor.YELLOW)
            highlight("flour", HighlightColor.BLUE)
            highlight("gloves", HighlightColor.PINK)
            highlight("lantern", HighlightColor.GREEN)
            var colours = emptyList<String>()
            for (i in 0 until 40) {
                colours = js("""(function(){return JSON.stringify(Array.prototype.map.call(document.querySelectorAll('[data-group="annotations"] .pd-hl'),
                    function(e){return getComputedStyle(e).backgroundColor}));})()""").trim('"').replace("\\\"", "\"").let {
                    org.json.JSONArray(it).let { a -> (0 until a.length()).map(a::getString) }
                }
                if (colours.size >= 4) break
                delay(100)
            }
            assertEquals("four boxes: $colours", 4, colours.toSet().size)
            shot("highlight-colours")
        }
    }

    @Test fun aTapOnAHighlightOpensItsMenuToRecolourAndRemoveIt() = runBlocking {
        withSelectionBook {
            highlight("gloves", HighlightColor.YELLOW)
            delay(800)
            val word = rectOf("gloves")
            tap(word.centerX(), word.centerY())
            until("the highlight's menu") { card.isOpen }
            withContext(Dispatchers.Main) {
                assertFalse(card.showsDefinitions)
                assertEquals(listOf("Highlight yellow", "Highlight blue", "Highlight pink", "Highlight green", "Add a note", "Remove the highlight"),
                    card.controlLabels.map { it.toString() })
            }
            shot("highlight-menu")
            press("Highlight green")
            until("the menu closed") { !card.isOpen }
            assertEquals("green", shelf.live.value.single().color)
            delay(600)
            tap(word.centerX(), word.centerY())
            until("the highlight's menu again") { card.isOpen }
            press("Remove the highlight")
            until("the menu closed") { !card.isOpen }
            assertTrue(shelf.live.value.isEmpty())
            var tombstone = false
            for (i in 0 until 60) { if (hub.stored().any { it.optBoolean("deleted") }) { tombstone = true; break }; delay(150) }
            assertTrue("the hub holds a tombstone: ${hub.log}", tombstone)
        }
    }

    @Test fun aNoteIsWrittenInItsSheetAndItsMarkSitsAtTheEndOfTheHighlight() = runBlocking {
        withSelectionBook {
            selectAndWait("palm against the lighthouse door")
            press("Add a note")
            until("the note's sheet") { overlay.isOpen }
            val field = withContext(Dispatchers.Main) { all(overlay).filterIsInstance<EditText>().single() }
            withContext(Dispatchers.Main) { field.setText("A door that is warm. Who lit it?") }
            shot("note-sheet")
            withContext(Dispatchers.Main) { overlay.rows.first { all(it).filterIsInstance<TextView>().any { t -> t.text == "Save" } }.performClick() }
            until("the sheet closed") { !overlay.isOpen }
            val kept = shelf.live.value.single()
            assertEquals("A door that is warm. Who lit it?", kept.note)
            var marks = 0
            for (i in 0 until 40) { marks = count("[data-group=\"annotation-notes\"] .pd-note"); if (marks > 0) break; delay(100) }
            assertEquals("one mark, at the end of the passage", 1, marks)
            shot("note-mark")
        }
    }

    @Test fun theNavigatorListsHighlightsWithTheirNotesAndFiltersThem() = runBlocking {
        withSelectionBook {
            highlight("harbor", HighlightColor.YELLOW)
            highlight("gloves", HighlightColor.BLUE)
            val blue = shelf.live.value.first { it.quote.highlight == "gloves" }
            withContext(Dispatchers.Main) { shelf.save(blue.copy(note = "Warm iron")) }
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
            until("the Navigator") { overlay.isOpen }
            openTab("highlights")
            until("the list of highlights") { overlay.rows.size == 2 }
            val rows = withContext(Dispatchers.Main) { overlay.rows.map { row -> all(row).filterIsInstance<TextView>().map { it.text.toString() } } }
            assertTrue(rows.toString(), rows.any { it.any { t -> t.contains("harbor") } })
            assertTrue(rows.toString(), rows.any { it.any { t -> t.contains("gloves") } && it.any { t -> t == "Warm iron" } })
            shot("highlights-tab")
            withContext(Dispatchers.Main) { all(overlay).first { it.contentDescription == "Highlights with notes" }.performClick() }
            until("only the one with a note") { overlay.rows.size == 1 }
            withContext(Dispatchers.Main) { all(overlay).first { it.contentDescription == "Blue highlights" }.performClick() }
            until("only the blue one") { overlay.rows.size == 1 && all(overlay.rows[0]).filterIsInstance<TextView>().any { it.text.contains("gloves") } }
            withContext(Dispatchers.Main) { all(overlay).first { it.contentDescription == "Green highlights" }.performClick() }
            until("nothing green") { overlay.rows.size == 1 && all(overlay.rows[0]).filterIsInstance<TextView>().any { it.text == "Nothing with this filter" } }
            withContext(Dispatchers.Main) { all(overlay).first { it.contentDescription == "All highlights" }.performClick() }
            until("all again") { overlay.rows.size == 2 }
            withContext(Dispatchers.Main) { overlay.rows[0].performClick() }
            until("the Navigator closed after a jump") { !overlay.isOpen }
        }
    }

    @Test fun whatAnotherDeviceMadeArrivesAndAPassageTheBookNoLongerHoldsIsKeptAndSaysSo() = runBlocking {
        withSelectionBook(prepare = {
            elsewhere("an_" + "a".repeat(32), "green", "EPUB/one.xhtml", "set her palm against the ", "lighthouse door", ". The iron was warm.", note = "from the iPad")
            elsewhere("an_" + "b".repeat(32), "pink", "EPUB/one.xhtml", "in another edition ", "a sentence this text does not have", " at all")
        }) {
            until("both arrived", 15_000) { shelf.live.value.size == 2 }
            var drawn = 0
            for (i in 0 until 50) { drawn = count("[data-group=\"annotations\"] .pd-hl"); if (drawn > 0) break; delay(100) }
            assertTrue("the one that is in the book is drawn: $drawn", drawn >= 1)
            withContext(Dispatchers.Main) { all(root).first { it.contentDescription == "Table of contents" }.performClick() }
            until("the Navigator") { overlay.isOpen }
            openTab("highlights")
            until("the rows") { overlay.rows.size == 2 }
            until("the missing passage is named", 15_000) {
                overlay.rows.any { row -> all(row).filterIsInstance<TextView>().any { it.text.toString() == "Can’t find this passage" } }
            }
            val rows = withContext(Dispatchers.Main) { overlay.rows.map { row -> all(row).filterIsInstance<TextView>().map { it.text.toString() } } }
            assertTrue(rows.toString(), rows.any { it.contains("from the iPad") })
            shot("highlights-cant-find")
        }
    }

    @Test fun anEditMadeWhileTheHubIsDownWaitsAndGoesOutWhenItIsBack() = runBlocking {
        withSelectionBook(prepare = { down = true }) {
            highlight("harbor", HighlightColor.BLUE)
            delay(2_500)
            assertTrue("waiting for the hub", shelf.waiting)
            assertTrue(hub.stored().isEmpty())
            hub.down = false
            val result = shelf.syncNow(HubClient(activity).annotationRemote())
            assertFalse(result.retry)
            assertFalse(shelf.waiting)
            assertEquals(1, hub.stored().size)
            assertTrue(AnnotationIds.valid(hub.stored()[0].getString("id")))
        }
    }
}

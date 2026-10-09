package com.pocketds.hub.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.reader.TextCursor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * The controller's cursor on the real reader (#62): Ⓧ brings it, the D-pad moves it a word or a line, Ⓐ starts a selection and Ⓐ finishes it,
 * L1 and R1 grow it to the sentence and the paragraph, Ⓑ cancels, ◀▶ walk the card, and the hint bar says what each does now. Keys are sent
 * to the screen as the app sends them; nothing here touches the real Pocket.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderCursorTest {
    private suspend fun SelectionBook.pad(vararg actions: PadAction) {
        for (action in actions) {
            withContext(Dispatchers.Main) { screen.onPad(action) }
            delay(250)
        }
    }

    private fun SelectionBook.cursor(): TextCursor? = screen.field("cursorWords")

    private suspend fun SelectionBook.cursorWord(): String? = withContext(Dispatchers.Main) { cursor()?.cursor?.text }

    private suspend fun SelectionBook.hints(): Map<String, String> = withContext(Dispatchers.Main) { screen.hints().associate { it.glyph to it.label } }

    private val right = PadAction.Step(Direction.RIGHT)
    private val down = PadAction.Step(Direction.DOWN)

    @Test fun xBringsTheCursorToTheFirstWordAndTheHintBarSaysWhatTheKeysDoNow() = runBlocking {
        withSelectionBook {
            assertNull(withContext(Dispatchers.Main) { cursor() })
            assertEquals("Select text", hints()["Ⓧ"])
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            assertEquals("Chapter", cursorWord())
            val keys = hints()
            assertEquals("Move the cursor", keys["D-pad"])
            assertEquals("Start selecting", keys["Ⓐ"])
            assertEquals("Sentence", keys["L1"])
            assertEquals("Paragraph", keys["R1"])
            assertEquals("Stop selecting", keys["Ⓑ"])
            assertEquals(1, count("#__pd-cursor"))
            shot("cursor")
            pad(PadAction.Back)
            assertNull(withContext(Dispatchers.Main) { cursor() })
            assertEquals(0, count("#__pd-cursor"))
        }
    }

    @Test fun theDpadMovesAWordAtATimeAndALineAtATime() = runBlocking {
        withSelectionBook {
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            pad(right, right, right)
            assertEquals("harbor", cursorWord())
            pad(down)
            // The next line down, nearest the same column: a word of the second line of the paragraph.
            val line = cursorWord()
            assertTrue("a word on the line below: $line", line in listOf("steps.", "Ash", "had", "fallen", "through", "the", "night,"))
            pad(PadAction.Step(Direction.UP))
            assertEquals("harbor", cursorWord())
            pad(PadAction.Step(Direction.LEFT))
            assertEquals("The", cursorWord())
        }
    }

    @Test fun aStartsAndAFinishesAndTheWordsGoToTheCard() = runBlocking {
        withSelectionBook {
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            pad(right, right, right)
            pad(PadAction.Activate)
            assertEquals("Finish", hints()["Ⓐ"])
            assertEquals("Grow the selection", hints()["D-pad"])
            assertEquals("Cancel", hints()["Ⓑ"])
            pad(right, right)
            shot("cursor-selecting")
            pad(PadAction.Activate)
            until("the bar for the phrase") { card.isOpen }
            delay(600)
            withContext(Dispatchers.Main) {
                assertFalse(card.showsDefinitions)
                assertNull("the cursor is put away for the card", cursor())
                assertTrue(card.controlLabels.map { it.toString() }.contains("Look it up in the dictionary"))
            }
            // The card is walked with the D-pad: it starts on the colour in use, a press of the right key is the next one.
            assertEquals("Highlight yellow", withContext(Dispatchers.Main) { card.focusedLabel.toString() })
            pad(right)
            assertEquals("Highlight blue", withContext(Dispatchers.Main) { card.focusedLabel.toString() })
            pad(PadAction.Activate)
            until("the card closed") { !card.isOpen }
            val made = shelf.live.value.single()
            assertEquals("blue", made.color)
            assertEquals("harbor bells were", made.quote.highlight)
        }
    }

    @Test fun theShouldersGrowTheSelectionToTheSentenceAndTheParagraph() = runBlocking {
        withSelectionBook {
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            pad(right, right, right, PadAction.Section(-1))
            assertEquals("L1: the sentence under the cursor", 2..16, withContext(Dispatchers.Main) { cursor()?.selectedIndexes })
            pad(PadAction.Section(-1))
            assertEquals("L1 again: the next sentence too", 2..43, withContext(Dispatchers.Main) { cursor()?.selectedIndexes })
            pad(PadAction.Section(1))
            assertEquals("R1: the paragraph", 2..43, withContext(Dispatchers.Main) { cursor()?.selectedIndexes })
            assertEquals(true, withContext(Dispatchers.Main) { cursor()?.selecting })
            shot("cursor-paragraph")
            pad(PadAction.Activate)
            until("the card") { card.isOpen }
            delay(500)
            withContext(Dispatchers.Main) {
                assertFalse("a paragraph is not looked up", card.controlLabels.map { it.toString() }.contains("Look it up in the dictionary"))
                assertTrue(card.controlLabels.map { it.toString() }.contains("Say the phrase"))
            }
            shot("cursor-paragraph-card")
        }
    }

    @Test fun bCancelsTheSelectionStartedAndThenPutsTheCursorAway() = runBlocking {
        withSelectionBook {
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            pad(PadAction.Activate, right, right)
            assertEquals(true, withContext(Dispatchers.Main) { cursor()?.selecting })
            pad(PadAction.Back)
            assertEquals(false, withContext(Dispatchers.Main) { cursor()?.selecting })
            assertEquals("The", cursorWord())
            pad(PadAction.Back)
            assertNull(withContext(Dispatchers.Main) { cursor() })
        }
    }

    @Test fun atTheEdgeOfThePageTheCursorTurnsItAndGoesOn() = runBlocking {
        withSelectionBook {
            pad(PadAction.Primary)
            until("the cursor") { cursor() != null }
            val before = withContext(Dispatchers.Main) { screen.field<Int>("pageIndex") }
            // Down the page, a line at a time, until the page turns under the cursor.
            var turned = false
            for (i in 0 until 40) {
                pad(down)
                if (withContext(Dispatchers.Main) { screen.field<Int>("pageIndex") } > before) { turned = true; break }
            }
            assertTrue("the page turned", turned)
            delay(1_000)
            val word = cursorWord()
            assertNotNull("the cursor is on the next page, on a word: $word", word)
            assertTrue(withContext(Dispatchers.Main) { cursor()?.cursor?.visible == true })
            shot("cursor-next-page")
        }
    }
}

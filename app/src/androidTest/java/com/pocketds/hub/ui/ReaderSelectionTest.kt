package com.pocketds.hub.ui

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Selecting words in the book (#62), on the real reader and a generated book: one word opens the dictionary card at once, with a speaker
 * inside it, beside the word and never over it; a phrase is looked up as one entry first; a phrase with no entry gets the bar, and Look
 * up never fails silently. The speaker is the test's own stand-in, so nothing is said aloud.
 */
@OptIn(ExperimentalReadiumApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderSelectionTest {
    @Test fun oneWordOpensTheCardAtOnceWithTheSpeakerInsideItBesideTheWord() = runBlocking {
        withSelectionBook {
            selectAndWait("lantern")
            withContext(Dispatchers.Main) {
                assertEquals("lantern", card.headingText.toString())
                assertTrue("the definitions: ${card.definitionText}", card.definitionText.length > 20 && !card.definitionText.contains("Looking up"))
                assertTrue(card.showsDefinitions)
                val labels = card.controlLabels.map { it.toString() }
                assertEquals(listOf("Say it", "Highlight yellow", "Highlight blue", "Highlight pink", "Highlight green", "Add a note", "Copy the words"), labels)
            }
            val word = selected
            val cardBox = withContext(Dispatchers.Main) { onScreen(card.bounds) }
            assertFalse("the card covers the word: card=$cardBox word=$word anchored=${anchored()}", android.graphics.RectF.intersects(cardBox, word))
            shot("card-word")
            press("Say it")
            assertEquals(listOf("lantern"), voice.said)
        }
    }

    @Test fun aPhraseTheDictionaryHasAsOneEntryOpensTheCardWithTheFirstWordsBaseForm() = runBlocking {
        withSelectionBook {
            selectAndWait("pulled off")
            withContext(Dispatchers.Main) {
                assertEquals("pull off", card.headingText.toString())
                assertTrue(card.showsDefinitions)
                assertTrue(card.controlLabels.map { it.toString() }.containsAll(listOf("Say the phrase", "Copy the words")))
                assertFalse("no Look up: it was found", card.controlLabels.any { it.toString().startsWith("Look it up") })
            }
            shot("card-phrase")
            press("Say the phrase")
            assertEquals(listOf("pulled off"), voice.said)
        }
    }

    @Test fun aPhraseWithNoEntryGetsTheBarAndLookUpSaysSoAndShowsTheFirstWordThatHasOne() = runBlocking {
        withSelectionBook {
            select("harbor bells were")
            until("the bar") { card.isOpen }
            delay(700)
            withContext(Dispatchers.Main) {
                assertFalse("only the bar", card.showsDefinitions)
                val labels = card.controlLabels.map { it.toString() }
                assertTrue(labels.toString(), labels.containsAll(listOf("Look it up in the dictionary", "Say the phrase", "Add a note", "Copy the words")))
                assertFalse("no speaker on the bar", "Say it" in labels)
            }
            shot("bar-phrase")
            press("Look it up in the dictionary")
            until("the answer") { card.showsDefinitions }
            withContext(Dispatchers.Main) {
                assertEquals("harbor", card.headingText.toString())
                assertEquals("No entry for “harbor bells were”. Showing “harbor”.", card.noteText.toString())
                assertTrue(card.definitionText.length > 20)
            }
            shot("card-lookup-fallback")
        }
    }

    @Test fun aNameThatIsNotInTheDictionaryIsSaidToHaveNoEntryAndNothingIsHidden() = runBlocking {
        withSelectionBook {
            selectAndWait("Maren")
            withContext(Dispatchers.Main) {
                assertEquals("Maren", card.headingText.toString())
                assertEquals("No entry for “Maren”.", card.noteText.toString())
                assertEquals("No entry in the offline dictionary.", card.definitionText.toString())
            }
        }
    }

    @Test fun theCardAlwaysStandsBesideTheWordsWhereverTheyAre() = runBlocking {
        withSelectionBook {
            for (text in listOf("harbor", "gloves and set her palm", "Inside, the stair wound up")) {
                selectAndWait(text)
                val word = selected
                val cardBox = withContext(Dispatchers.Main) { onScreen(card.bounds) }
                assertFalse("'$text': the card covers the words: card=$cardBox words=$word anchored=${anchored()} insets=${card.field<Int>("topInset")}/${card.field<Int>("bottomInset")} height=${card.height} root=${root.height}", android.graphics.RectF.intersects(cardBox, word))
                press("Copy the words")
                until("the card closed") { !card.isOpen }
            }
        }
    }

    @Test fun copyPutsTheWordsOnTheClipboardAndClosesTheCard() = runBlocking {
        withSelectionBook {
            selectAndWait("lighthouse door")
            press("Copy the words")
            until("the card closed") { !card.isOpen }
            val clip = withContext(Dispatchers.Main) {
                (activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()
            }
            assertEquals("lighthouse door", clip)
        }
    }
}

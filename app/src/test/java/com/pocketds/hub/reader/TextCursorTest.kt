package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class TextCursorTest {
    private val paragraphs = listOf(
        "The harbor bells were still ringing when Maren climbed the last of the stone steps. Ash had fallen through the night.",
        "She did not stop to look at them. In spite of the cold, she pulled off her gloves. The iron was warm.",
        "“They said it would take off,” her brother had told her. She had laughed at him then."
    )

    /** The paragraphs as words on a page 8 words wide: each line 20px tall, each word 40px wide; [visibleUntil] words are on the page. */
    private fun page(visibleUntil: Int = Int.MAX_VALUE, firstNumber: Int = 0): List<PageWord> {
        val out = ArrayList<PageWord>()
        var line = 0
        paragraphs.forEachIndexed { p, text ->
            val parts = text.split(" ")
            parts.forEachIndexed { k, word ->
                if (k % 8 == 0 && k > 0) line++
                val n = out.size
                out += PageWord(firstNumber + n, word, p, (k % 8) * 40f, line * 20f, (k % 8) * 40f + 36f, line * 20f + 18f, n < visibleUntil)
            }
            line++
        }
        return out
    }

    private fun text(cursor: TextCursor, words: List<PageWord>) = cursor.selectedIndexes?.let { r -> words.filter { it.index in r }.joinToString(" ") { it.text } }

    @Test fun sentencesEndAtTheirFullStopsAndNotAtAbbreviationsOrInitials() {
        val words = "Mr. Keld met J. R. Tolan at 3.5 o'clock. “Stop!” she said. Then, he left… and she stayed. Done.".split(" ")
        val numbers = SentenceSplitter.split(words, words.map { 0 })
        val sentences = words.indices.groupBy { numbers[it] }.values.map { it.joinToString(" ") { i -> words[i] } }
        assertEquals(listOf("Mr. Keld met J. R. Tolan at 3.5 o'clock.", "“Stop!” she said.", "Then, he left… and she stayed.", "Done."), sentences)
    }

    @Test fun aNewParagraphAlwaysBeginsANewSentence() {
        val numbers = SentenceSplitter.split(listOf("no", "full", "stop", "here"), listOf(0, 0, 1, 1))
        assertEquals(listOf(0, 0, 1, 1), numbers.toList())
    }

    @Test fun aQuotedSentenceEndsAfterItsClosingQuoteWhenACapitalFollows() {
        val words = "“I know.” She left. He said, “Go.” and went.".split(" ")
        val numbers = SentenceSplitter.split(words, words.map { 0 })
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 2, 2, 2), numbers.toList())
    }

    @Test fun theCursorStartsOnTheFirstWordOnThePageAndMovesAWordAtATime() {
        val words = page()
        val cursor = TextCursor(words)
        assertEquals("The", cursor.cursor!!.text)
        assertEquals(TextCursor.Step.Moved, cursor.next())
        assertEquals("harbor", cursor.cursor!!.text)
        assertEquals(TextCursor.Step.Moved, cursor.previous())
        assertEquals(TextCursor.Step.Stuck, cursor.previous())
    }

    @Test fun upAndDownMoveALineKeepingTheColumn() {
        val words = page()
        val cursor = TextCursor(words, startIndex = 2)
        assertEquals(TextCursor.Step.Moved, cursor.line(down = true))
        assertEquals(2, cursor.cursor!!.index - words.first { it.top == cursor.cursor!!.top }.index)
        assertEquals(TextCursor.Step.Moved, cursor.line(down = false))
        assertEquals(2, cursor.cursor!!.index)
        assertEquals(TextCursor.Step.Stuck, cursor.line(down = false))
    }

    @Test fun downFromTheLastLineOfAParagraphGoesToTheNextParagraph() {
        val words = page()
        val lastOfFirst = words.last { it.paragraph == 0 }
        val cursor = TextCursor(words, lastOfFirst.index)
        assertEquals(TextCursor.Step.Moved, cursor.line(down = true))
        assertEquals(1, cursor.cursor!!.paragraph)
    }

    @Test fun aSelectionRunsFromWhereItStartedToTheCursorEitherWay() {
        val words = page()
        val cursor = TextCursor(words, startIndex = 3)
        cursor.start()
        cursor.next(); cursor.next()
        assertEquals("were still ringing", text(cursor, words))
        val finished = cursor.finish()
        assertEquals(3..5, finished)
        assertFalse(cursor.selecting)
        // Backwards: the same words.
        val back = TextCursor(words, startIndex = 5)
        back.start()
        back.previous(); back.previous()
        assertEquals("were still ringing", text(back, words))
    }

    @Test fun cancelDropsTheSelectionButNotTheCursor() {
        val words = page()
        val cursor = TextCursor(words, startIndex = 3)
        cursor.start(); cursor.next()
        cursor.cancelSelection()
        assertFalse(cursor.selecting)
        assertEquals(4, cursor.cursor!!.index)
        assertEquals(4..4, cursor.selectedIndexes)
    }

    @Test fun l1GrowsToTheSentenceAndAgainToTheNextOne() {
        val words = page()
        val cursor = TextCursor(words, startIndex = 4)
        cursor.sentence()
        assertEquals("The harbor bells were still ringing when Maren climbed the last of the stone steps.", text(cursor, words))
        cursor.sentence()
        assertEquals("The harbor bells were still ringing when Maren climbed the last of the stone steps. Ash had fallen through the night.", text(cursor, words))
        assertEquals(TextCursor.Step.Stuck, cursor.sentence())
    }

    @Test fun r1GrowsToTheWholeParagraph() {
        val words = page()
        val cursor = TextCursor(words, startIndex = words.first { it.text == "pulled" }.index)
        cursor.paragraph()
        assertEquals(paragraphs[1], text(cursor, words))
        assertTrue(cursor.selecting)
    }

    @Test fun theSelectionKeepsTheWordsWhenTheCursorThenMovesOn() {
        val words = page()
        val cursor = TextCursor(words, startIndex = 40)
        cursor.start()
        cursor.next(); cursor.next(); cursor.next()
        assertEquals(40..43, cursor.selectedIndexes)
    }

    @Test fun atTheEndOfThePageTheNextWordAsksForTheNextPage() {
        val words = page(visibleUntil = 10)
        val cursor = TextCursor(words, startIndex = 9)
        val step = cursor.next()
        assertEquals(TextCursor.Step.TurnPage(forward = true, cursor = 10), step)
        assertEquals(9, cursor.cursor!!.index)
    }

    @Test fun downFromTheLastVisibleLineAsksForTheNextPageToo() {
        val words = page(visibleUntil = 12)
        val last = words.last { it.visible }
        val cursor = TextCursor(words, last.index)
        val step = cursor.line(down = true)
        assertTrue("$step", step is TextCursor.Step.TurnPage && step.forward)
    }

    @Test fun downFromAnywhereOnTheLastLineAsksForTheNextPage() {
        val words = page(visibleUntil = 12)
        // Word 9 is mid-line on the last visible line (words 8 to 11).
        val cursor = TextCursor(words, startIndex = 9)
        val step = cursor.line(down = true)
        assertEquals(TextCursor.Step.TurnPage(forward = true, cursor = 12), step)
        assertEquals(9, cursor.cursor!!.index)
    }

    @Test fun upFromTheFirstLineAsksForTheLastWordOfThePageBefore() {
        val words = page().map { it.copy(visible = it.index >= 8) }
        val cursor = TextCursor(words, startIndex = 9)
        assertEquals(TextCursor.Step.TurnPage(forward = false, cursor = 7), cursor.line(down = false))
    }

    @Test fun aSelectionStartedOnOnePageEndsOnTheNext() {
        val first = page(visibleUntil = 10)
        val cursor = TextCursor(first, startIndex = 7)
        cursor.start()
        cursor.next(); cursor.next()
        val turn = cursor.next() as TextCursor.Step.TurnPage
        val (number, anchor) = cursor.carry()
        assertEquals(9, number)
        assertEquals(7, anchor)
        // The next page shows the words from 10 on, and the paragraph that began before it.
        val second = page().map { it.copy(visible = it.index >= 10) }
        val carried = TextCursor.restoring(second, turn.cursor, anchor)
        assertTrue(carried.selecting)
        assertEquals(10, carried.cursor!!.index)
        carried.next()
        assertEquals(7..11, carried.selectedIndexes)
    }

    @Test fun anEmptyPageHasNothingToMoveOver() {
        val cursor = TextCursor(emptyList())
        assertTrue(cursor.isEmpty)
        assertEquals(TextCursor.Step.Stuck, cursor.next())
        assertEquals(TextCursor.Step.Stuck, cursor.line(true))
        assertEquals(TextCursor.Step.Stuck, cursor.sentence())
        assertNull(cursor.cursor)
        assertNull(cursor.finish())
    }
}

package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import org.junit.Assert.*
import org.junit.Test

class SelectionKeysTest {
    private val book = ReaderPadState(ReaderKind.BOOK)
    private val cursor = book.copy(cursor = true)
    private val anchored = cursor.copy(anchored = true)
    private fun on(state: ReaderPadState, action: PadAction) = ReaderPadMap.command(state, action)
    private fun hints(state: ReaderPadState) = ReaderPadMap.hints(state).associate { it.glyph to it.label }

    @Test fun readingAnXBringsTheCursorAndInTheMenuItIsStillTheBookmark() {
        assertEquals(ReaderCommand.SelectText, on(book, PadAction.Primary))
        assertEquals(ReaderCommand.Bookmark, on(book.copy(controlsVisible = true), PadAction.Primary))
    }

    @Test fun aYIsTheModeButtonWhenTheBookHasMoreThanOneFormatAndContentsOtherwise() {
        assertEquals(ReaderCommand.Contents, on(book, PadAction.Secondary))
        assertEquals(ReaderCommand.Mode, on(book.copy(modes = true), PadAction.Secondary))
        val audiobook = ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true, modes = true)
        assertEquals(ReaderCommand.Mode, on(audiobook, PadAction.Secondary))
        assertEquals(ReaderCommand.Formats, on(audiobook.copy(modes = false), PadAction.Secondary))
    }

    @Test fun withTheCursorOnTheDpadMovesItAndAStartsAndFinishes() {
        assertEquals(ReaderCommand.Cursor(Direction.LEFT, grow = false), on(cursor, PadAction.Step(Direction.LEFT)))
        assertEquals(ReaderCommand.Cursor(Direction.DOWN, grow = false), on(cursor, PadAction.Step(Direction.DOWN)))
        assertEquals(ReaderCommand.CursorAnchor(finish = false), on(cursor, PadAction.Activate))
        assertEquals(ReaderCommand.Cursor(Direction.RIGHT, grow = true), on(anchored, PadAction.Step(Direction.RIGHT)))
        assertEquals(ReaderCommand.CursorAnchor(finish = true), on(anchored, PadAction.Activate))
    }

    @Test fun theShouldersGrowTheSelectionToTheSentenceAndTheParagraph() {
        assertEquals(ReaderCommand.CursorGrow(paragraph = false), on(cursor, PadAction.Section(-1)))
        assertEquals(ReaderCommand.CursorGrow(paragraph = true), on(cursor, PadAction.Section(1)))
        assertEquals(ReaderCommand.CursorGrow(paragraph = false), on(anchored, PadAction.Section(-1)))
        // Read along, the same shoulders step the voice a sentence at a time; with the cursor on they are the selection's.
        assertEquals(ReaderCommand.CursorGrow(paragraph = true), on(cursor.copy(narration = true), PadAction.Section(1)))
        assertEquals(ReaderCommand.Sentence(1), on(book.copy(narration = true), PadAction.Section(1)))
    }

    @Test fun bCancelsTheSelectionStartedAndThenPutsTheCursorAway() {
        assertEquals(ReaderCommand.CursorCancel(anchored = true), on(anchored, PadAction.Back))
        assertEquals(ReaderCommand.CursorCancel(anchored = false), on(cursor, PadAction.Back))
        assertEquals(ReaderCommand.CursorCancel(anchored = false), on(cursor, PadAction.Primary))
    }

    @Test fun theCursorOffTheDpadTurnsPagesAsItAlwaysDid() {
        assertEquals(ReaderCommand.Page(-1), on(book, PadAction.Step(Direction.LEFT)))
        assertEquals(ReaderCommand.Forward, on(book, PadAction.Activate))
    }

    @Test fun inTheMenuTheCursorKeysAreTheMenusOwn() {
        val menu = cursor.copy(controlsVisible = true)
        assertEquals(ReaderCommand.Choose, on(menu, PadAction.Activate))
        assertEquals(ReaderCommand.Focus(Direction.LEFT), on(menu, PadAction.Step(Direction.LEFT)))
    }

    @Test fun withTheModeButtonOpenTheDpadChoosesAAdoptsAndBCloses() {
        val open = book.copy(modes = true, picking = true)
        assertEquals(ReaderCommand.ModeMove(-1), on(open, PadAction.Step(Direction.LEFT)))
        assertEquals(ReaderCommand.ModeMove(1), on(open, PadAction.Step(Direction.RIGHT)))
        assertEquals(ReaderCommand.ModePick, on(open, PadAction.Activate))
        assertEquals(ReaderCommand.ModeClose, on(open, PadAction.Back))
        assertEquals(ReaderCommand.ModeClose, on(open, PadAction.Secondary))
        assertEquals(ReaderCommand.Ignore, on(open, PadAction.Primary))
        assertEquals(ReaderCommand.Ignore, on(open, PadAction.Section(1)))
        val audio = ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true, modes = true, picking = true)
        assertEquals(ReaderCommand.ModePick, on(audio, PadAction.Activate))
        assertEquals(ReaderCommand.ModeClose, on(audio, PadAction.Back))
    }

    @Test fun theHintBarSaysWhatTheKeysDoNowAndNothingElse() {
        assertEquals("Select text", hints(book)[ReaderPadMap.X])
        assertEquals("Contents", hints(book)[ReaderPadMap.Y])
        assertEquals("Mode", hints(book.copy(modes = true))[ReaderPadMap.Y])
        val moving = hints(cursor.copy(modes = true))
        assertEquals("Move the cursor", moving[ReaderPadMap.DPAD])
        assertEquals("Start selecting", moving[ReaderPadMap.A])
        assertEquals("Sentence", moving[ReaderPadMap.L1])
        assertEquals("Paragraph", moving[ReaderPadMap.R1])
        assertEquals("Mode", moving[ReaderPadMap.Y])
        assertEquals("Stop selecting", moving[ReaderPadMap.B])
        val growing = hints(anchored)
        assertEquals("Grow the selection", growing[ReaderPadMap.DPAD])
        assertEquals("Finish", growing[ReaderPadMap.A])
        assertEquals("Cancel", growing[ReaderPadMap.B])
        assertFalse("no Mode with one format", ReaderPadMap.Y in growing)
        val picking = hints(book.copy(modes = true, picking = true))
        assertEquals(listOf(ReaderPadMap.DPAD_SIDES, ReaderPadMap.A, ReaderPadMap.B), ReaderPadMap.hints(book.copy(modes = true, picking = true)).map { it.glyph })
        assertEquals("Choose a mode", picking[ReaderPadMap.DPAD_SIDES])
        assertEquals("Switch", picking[ReaderPadMap.A])
        assertEquals("Close", picking[ReaderPadMap.B])
    }

    @Test fun everyHintChipDoesWhatItSaysWhenTapped() {
        listOf(book, book.copy(modes = true), cursor, cursor.copy(modes = true), anchored, book.copy(modes = true, picking = true),
            ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true, modes = true),
            ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true, modes = true, picking = true)).forEach { state ->
            ReaderPadMap.hints(state).forEach { hint ->
                assertEquals(ReaderPadMap.describe(state.kind, ReaderPadMap.command(state, hint.action)), hint.label)
            }
        }
    }

    @Test fun theControlsSheetNamesTheCursorKeys() {
        val sheet = ReaderPadMap.sheet(ReaderKind.BOOK, book.copy(modes = true))
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.X) && it.does == "Select text" })
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.Y) && it.does == "Mode" })
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.X, ReaderPadMap.L1, ReaderPadMap.R1) })
    }
}

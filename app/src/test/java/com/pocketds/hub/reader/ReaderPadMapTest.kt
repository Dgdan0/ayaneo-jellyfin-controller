package com.pocketds.hub.reader

import com.pocketds.hub.input.Direction
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.input.Stick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPadMapTest {
    @Test fun `read along, the shoulders step through sentences`() {
        val narrating = ReaderPadState(ReaderKind.BOOK, narration = true)
        assertEquals(ReaderCommand.Sentence(1), ReaderPadMap.command(narrating, PadAction.Section(1)))
        assertEquals(ReaderCommand.Sentence(-1), ReaderPadMap.command(narrating, PadAction.Section(-1)))
        // In the menu, or without narration, they still turn pages.
        assertEquals(ReaderCommand.Page(1), ReaderPadMap.command(narrating.copy(controlsVisible = true), PadAction.Section(1)))
        assertEquals(ReaderCommand.Page(1), ReaderPadMap.command(ReaderPadState(ReaderKind.BOOK), PadAction.Section(1)))
        val sheet = ReaderPadMap.sheet(ReaderKind.BOOK, narrating)
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.L1) && it.does == "Previous sentence" })
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.R1) && it.does == "Next sentence" })
    }

    private val comic = ReaderPadState(ReaderKind.COMIC)
    private val book = ReaderPadState(ReaderKind.BOOK)
    private val audiobook = ReaderPadState(ReaderKind.AUDIOBOOK, controlsVisible = true)

    /** Every action the pad can send, both clicks pressed and let go. */
    private val everything = listOf(
        PadAction.Activate, PadAction.Back, PadAction.Primary, PadAction.Secondary,
        PadAction.Section(-1), PadAction.Section(1), PadAction.Page(Direction.UP), PadAction.Page(Direction.DOWN),
        PadAction.Menu, PadAction.Refresh, PadAction.Pan(.1f, -.2f),
        PadAction.Click(Stick.LEFT), PadAction.Click(Stick.LEFT, down = false),
        PadAction.Click(Stick.RIGHT), PadAction.Click(Stick.RIGHT, down = false)
    ) + Direction.entries.map { PadAction.Step(it) }

    private fun on(state: ReaderPadState, action: PadAction) = ReaderPadMap.command(state, action)

    @Test fun `comics read on with A, back with B, and leave with Select`() {
        assertEquals(ReaderCommand.Forward, on(comic, PadAction.Activate))
        assertEquals(ReaderCommand.Backward, on(comic, PadAction.Back))
        assertEquals(ReaderCommand.Leave, on(comic, PadAction.Refresh))
        assertEquals(ReaderCommand.Page(1), on(comic, PadAction.Primary))
        assertEquals(ReaderCommand.Page(-1), on(comic, PadAction.Secondary))
        assertEquals(ReaderCommand.Move(Direction.LEFT), on(comic, PadAction.Step(Direction.LEFT)))
        // With the controls open B closes them first, and A presses the control in focus.
        val open = comic.copy(controlsVisible = true)
        assertEquals(ReaderCommand.Controls(false), on(open, PadAction.Back))
        assertEquals(ReaderCommand.Choose, on(open, PadAction.Activate))
        assertEquals(ReaderCommand.Focus(Direction.UP), on(open, PadAction.Step(Direction.UP)))
        // A page that failed to open: Select tries again rather than leaving.
        assertEquals(ReaderCommand.Retry, on(comic.copy(loading = true), PadAction.Refresh))
    }

    @Test fun `a book's B opens the menu, and B again leaves the book`() {
        assertEquals(ReaderCommand.Controls(true), on(book, PadAction.Back))
        assertEquals(ReaderCommand.Leave, on(book.copy(controlsVisible = true), PadAction.Back))
        assertEquals(ReaderCommand.Forward, on(book, PadAction.Activate))
        assertEquals(ReaderCommand.Page(-1), on(book, PadAction.Section(-1)))
        assertEquals(ReaderCommand.Chapter(1), on(book, PadAction.Page(Direction.DOWN)))
        assertEquals(ReaderCommand.Display, on(book, PadAction.Refresh))
    }

    @Test fun `an audiobook's B leaves, and its shoulders change part rather than the tab`() {
        assertEquals(ReaderCommand.Leave, on(audiobook, PadAction.Back))
        assertEquals(ReaderCommand.Chapter(-1), on(audiobook, PadAction.Section(-1)))
        assertEquals(ReaderCommand.Chapter(1), on(audiobook, PadAction.Section(1)))
        assertEquals(ReaderCommand.Seek(-10), on(audiobook, PadAction.Page(Direction.UP)))
        assertEquals(ReaderCommand.Seek(30), on(audiobook.copy(seekSeconds = 30), PadAction.Page(Direction.DOWN)))
        assertEquals(ReaderCommand.PlayPause, on(audiobook, PadAction.Primary))
    }

    @Test fun `the right stick pans a comic and scrolls only a scrolling book`() {
        assertEquals(ReaderCommand.Glide(.1f, -.2f), on(comic, PadAction.Pan(.1f, -.2f)))
        assertEquals(ReaderCommand.Ignore, on(book, PadAction.Pan(0f, .5f)))
        assertEquals(ReaderCommand.Glide(0f, .5f), on(book.copy(scrolling = true), PadAction.Pan(0f, .5f)))
        // A scrolling book's D-pad scrolls up and down, and still turns sideways.
        val scrolling = book.copy(scrolling = true)
        assertEquals(ReaderCommand.Scroll(Direction.DOWN), on(scrolling, PadAction.Step(Direction.DOWN)))
        assertEquals(ReaderCommand.Page(1), on(scrolling, PadAction.Step(Direction.RIGHT)))
        assertEquals(ReaderCommand.Controls(true), on(book, PadAction.Step(Direction.DOWN)))
    }

    @Test fun `L3 is a comic's magnifier while held, and R3 opens the keys everywhere`() {
        assertEquals(ReaderCommand.Magnifier(true), on(comic, PadAction.Click(Stick.LEFT)))
        assertEquals(ReaderCommand.Magnifier(false), on(comic, PadAction.Click(Stick.LEFT, down = false)))
        listOf(comic, book, audiobook).forEach { state ->
            assertEquals(ReaderCommand.Keys, on(state, PadAction.Click(Stick.RIGHT)))
            assertEquals(ReaderCommand.Ignore, on(state, PadAction.Click(Stick.RIGHT, down = false)))
        }
        assertEquals(ReaderCommand.FollowNarration, on(book.copy(narration = true), PadAction.Click(Stick.LEFT)))
        // A book that is not read along has no voice to follow: L3 moves the page's bottom-left corner on (#42).
        assertEquals(ReaderCommand.NextPageInfo, on(book, PadAction.Click(Stick.LEFT)))
        assertEquals(ReaderCommand.Ignore, on(book, PadAction.Click(Stick.LEFT, down = false)))
        assertEquals("Page info", ReaderPadMap.describe(ReaderKind.BOOK, ReaderCommand.NextPageInfo))
    }

    @Test fun `the hint row names only what the keys do now`() {
        val comicHints = ReaderPadMap.hints(comic.copy(controlsVisible = true)).associate { it.glyph to it.label }
        assertEquals("Choose", comicHints[ReaderPadMap.A])
        assertEquals("Hide controls", comicHints[ReaderPadMap.B])
        assertEquals("Leave", comicHints[ReaderPadMap.SELECT])
        assertEquals("Keys", comicHints[ReaderPadMap.R3])
        val bookHints = ReaderPadMap.hints(book.copy(controlsVisible = true)).associate { it.glyph to it.label }
        assertEquals("Leave the book", bookHints[ReaderPadMap.B])
        assertEquals("Back to the page", bookHints[ReaderPadMap.START])
        assertEquals("Page info", bookHints[ReaderPadMap.L3])
        assertEquals("Back to the narration", ReaderPadMap.hints(book.copy(controlsVisible = true, narration = true)).associate { it.glyph to it.label }[ReaderPadMap.L3])
        assertEquals("Previous part", ReaderPadMap.hints(audiobook).first { it.glyph == ReaderPadMap.L1 }.label)
        // Each chip does what it says when tapped: the label is its action's.
        listOf(comic.copy(controlsVisible = true), book.copy(controlsVisible = true), audiobook).forEach { state ->
            ReaderPadMap.hints(state).forEach { hint ->
                assertTrue(hint.label.isNotBlank())
                assertEquals(ReaderPadMap.describe(state.kind, ReaderPadMap.command(state, hint.action)), hint.label)
            }
        }
    }

    @Test fun `an audiobook's shoulders step by chapter where the book has chapters, and by part where it does not (#31)`() {
        val chapters = audiobook.copy(chapters = true)
        assertEquals(ReaderCommand.Chapter(1), on(chapters, PadAction.Section(1)))
        val hints = ReaderPadMap.hints(chapters).associate { it.glyph to it.label }
        assertEquals("Previous chapter", hints[ReaderPadMap.L1])
        assertEquals("Next chapter", hints[ReaderPadMap.R1])
        val sheet = ReaderPadMap.sheet(ReaderKind.AUDIOBOOK, chapters)
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.L1) && it.does == "Previous chapter" })
        assertTrue(sheet.toString(), sheet.any { it.keys == listOf(ReaderPadMap.R1) && it.does == "Next chapter" })
        // Without them, as it was.
        assertEquals("Next part", ReaderPadMap.hints(audiobook).first { it.glyph == ReaderPadMap.R1 }.label)
        assertTrue(ReaderPadMap.sheet(ReaderKind.AUDIOBOOK).any { it.keys == listOf(ReaderPadMap.R1) && it.does == "Next part" })
        // A book's own chapters are chapters whatever the flag, and the chips still do what they say.
        assertEquals("Next chapter", ReaderPadMap.describe(ReaderKind.BOOK, ReaderCommand.Chapter(1)))
        ReaderPadMap.hints(chapters).forEach { hint ->
            assertEquals(ReaderPadMap.describe(chapters.kind, ReaderPadMap.command(chapters, hint.action), chapters.chapters), hint.label)
        }
    }

    @Test fun `the controls sheet lists every key that does something, as the keys do it`() {
        val comicSheet = ReaderPadMap.sheet(ReaderKind.COMIC).associate { it.keys.joinToString(" ") to it.does }
        assertEquals("Forward", comicSheet[ReaderPadMap.A])
        assertEquals("Back", comicSheet[ReaderPadMap.B])
        assertEquals("Leave", comicSheet[ReaderPadMap.SELECT])
        assertEquals("Move around the page", comicSheet[ReaderPadMap.DPAD])
        assertEquals("Pan", comicSheet[ReaderPadMap.RIGHT_STICK])
        assertEquals("Magnifier, while held", comicSheet[ReaderPadMap.L3])
        assertEquals("Zoom out", comicSheet[ReaderPadMap.L1])
        val bookSheet = ReaderPadMap.sheet(ReaderKind.BOOK).associate { it.keys.joinToString(" ") to it.does }
        assertEquals("Menu", bookSheet[ReaderPadMap.B])
        assertEquals("Previous page, next page", bookSheet[ReaderPadMap.DPAD_SIDES])
        assertEquals("Menu", bookSheet[ReaderPadMap.DPAD_ENDS])
        // Paged, the stick does nothing, so it is not listed; L3 moves the corner on (#42).
        assertFalse(ReaderPadMap.RIGHT_STICK in bookSheet)
        assertEquals("Page info", bookSheet[ReaderPadMap.L3])
        val scrolling = ReaderPadMap.sheet(ReaderKind.BOOK, book.copy(scrolling = true, narration = true))
            .associate { it.keys.joinToString(" ") to it.does }
        assertEquals("Scroll", scrolling[ReaderPadMap.DPAD_ENDS])
        assertEquals("Scroll", scrolling[ReaderPadMap.RIGHT_STICK])
        assertEquals("Back to the narration", scrolling[ReaderPadMap.L3])
        val audioSheet = ReaderPadMap.sheet(ReaderKind.AUDIOBOOK).associate { it.keys.joinToString(" ") to it.does }
        assertEquals("Leave", audioSheet[ReaderPadMap.B])
        assertEquals("Move between controls", audioSheet[ReaderPadMap.DPAD])
        listOf(ReaderKind.COMIC, ReaderKind.BOOK, ReaderKind.AUDIOBOOK).forEach { kind ->
            ReaderPadMap.sheet(kind).forEach { assertTrue(it.does.isNotBlank() && it.keys.isNotEmpty()) }
        }
    }

    @Test fun `every key is a reader's own, in every reader and state`() {
        val states = listOf(comic, comic.copy(controlsVisible = true), book, book.copy(controlsVisible = true),
            book.copy(scrolling = true), audiobook)
        // The screens hand every command to themselves and return true; here the map never
        // answers a shoulder with anything the app would read as a tab switch.
        states.forEach { state ->
            everything.forEach { action -> ReaderPadMap.command(state, action) }
        }
        assertTrue(states.none { ReaderPadMap.command(it, PadAction.Section(1)) == ReaderCommand.Ignore })
    }
}

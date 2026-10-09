package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingProgress
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import com.pocketds.hub.model.ReadingYouPatch
import com.pocketds.hub.model.ReadingYouResponse
import com.pocketds.hub.model.YouEdit
import java.time.YearMonth
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A book's reading status (#63): the four words, how they are worked out, what each does and what it writes. */
class ReadingStatusTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun progress(percent: Double, completed: Boolean = false) = ReadingProgress(percent, completed)

    // ------------------------------------------------------------------ the words

    @Test fun `there are four statuses, in the order the picker shows them`() {
        assertEquals(listOf("want", "reading", "finished", "not-reading"), ReadingStatus.CHOICES)
        assertEquals(listOf("Want to read", "Reading", "Finished", "Not reading"), ReadingStatus.CHOICES.map(ReadingStatus::label))
        assertEquals("", ReadingStatus.label(""))
        assertEquals("", ReadingStatus.label("abandoned"))
    }

    @Test fun `each status says what it does in the picker`() {
        assertEquals("Keep it on your Want to read list", ReadingStatus.detail("want"))
        assertEquals("Show it in Continue reading", ReadingStatus.detail("reading"))
        assertEquals("Say when; puts the ✓ on its cover", ReadingStatus.detail("finished"))
        assertEquals("Take it off Continue reading and Home; your place stays", ReadingStatus.detail("not-reading"))
    }

    // ------------------------------------------------------------------ the one status of a book

    @Test fun `the hub's word is the status, whatever the page could work out`() {
        assertEquals("not-reading", ReadingStatus.of(ReadingWork(status = "not-reading", progress = progress(.4))))
        assertEquals("reading", ReadingStatus.of(ReadingWork(status = "reading", progress = progress(1.0, true))))
        assertEquals("finished", ReadingStatus.of(ReadingWork(status = "finished")))
        assertEquals("finished", ReadingStatus.of(ReadingSectionItem(status = "finished")))
    }

    @Test fun `without the hub's word, the status is worked out the way the hub does it`() {
        fun status(you: ReadingYou? = null, progress: ReadingProgress? = null) = ReadingStatus.of(ReadingWork(you = you, progress = progress))
        assertEquals("", status())
        assertEquals("", status(ReadingYou(rating = 4)))
        assertEquals("reading", status(progress = progress(.2)))
        assertEquals("finished", status(progress = progress(1.0, true)))
        assertEquals("finished", status(ReadingYou(finished = "2026-09")))
        assertEquals("finished", status(ReadingYou(status = "read")))
        assertEquals("want", status(ReadingYou(status = "to-read")))
        assertEquals("reading", status(ReadingYou(status = "currently-reading")))
        // Finished outranks a place begun (a second time through), to-read does not outrank a place at the end.
        assertEquals("finished", status(ReadingYou(finished = "2026-09"), progress(.03)))
        assertEquals("finished", status(ReadingYou(status = "to-read"), progress(1.0, true)))
        assertEquals("want", status(ReadingYou(status = "to-read"), progress(.04)))
        // What the person chose outranks all of it.
        assertEquals("not-reading", status(ReadingYou(chosen = "not-reading"), progress(.4)))
        assertEquals("reading", status(ReadingYou(chosen = "reading", finished = "2026-09")))
        assertEquals("want", status(ReadingYou(chosen = "want", status = "read")))
        // Wanted and then opened is being read, as the Want to read list always had it.
        assertEquals("reading", status(ReadingYou(chosen = "want"), progress(.2)))
        assertEquals("want", status(ReadingYou(chosen = "want"), progress(1.0, true)))
        assertEquals("not-reading", status(ReadingYou(chosen = "not-reading"), progress(.2)))
        // A word nobody can choose is not one.
        assertEquals("want", status(ReadingYou(chosen = "abandoned", status = "to-read")))
    }

    @Test fun `the tick is for every finished book, however it came to be finished`() {
        assertTrue(ReadingStatus.isFinished(ReadingWork(status = "finished")))
        assertTrue("an import that says read", ReadingStatus.isFinished(ReadingWork(you = ReadingYou(status = "read", finished = "2024-01"))))
        assertTrue("read to the end, from a hub that sends no status", ReadingStatus.isFinished(ReadingWork(progress = progress(1.0, true))))
        assertFalse(ReadingStatus.isFinished(ReadingWork()))
        assertFalse(ReadingStatus.isFinished(ReadingWork(status = "reading", progress = progress(1.0, true))))
        assertFalse("put down is not finished, whatever the place says", ReadingStatus.isFinished(ReadingWork(status = "not-reading", progress = progress(1.0, true))))
        assertTrue(ReadingStatus.isFinished(ReadingSectionItem(status = "finished")))
        assertTrue(ReadingStatus.isFinished(ReadingSectionItem(progress = progress(1.0, true))))
        assertFalse(ReadingStatus.isFinished(ReadingSectionItem(progress = progress(.5))))
    }

    @Test fun `a book is in Continue reading when it has a place and was not put down or finished`() {
        fun continues(status: String, p: ReadingProgress?) = ReadingStatus.continues(ReadingWork(status = status, progress = p))
        assertTrue(continues("", progress(.4)))
        assertTrue(continues("reading", progress(.4)))
        assertTrue("reading it again, from the end", continues("reading", progress(1.0, true)))
        assertFalse("put down, place kept", continues("not-reading", progress(.4)))
        assertFalse("marked finished by hand with a place short of the end", continues("finished", progress(.4)))
        assertFalse("read to the end", continues("", progress(1.0, true)))
        assertFalse("wanted again after finishing", continues("want", progress(1.0, true)))
        assertFalse("no place to continue from", continues("reading", progress(0.0)))
        assertFalse("no place at all", continues("reading", null))
    }

    // ------------------------------------------------------------------ the menu row

    @Test fun `the menu row names the status it holds`() {
        assertEquals("Reading status", ReadingStatus.rowLabel(""))
        assertEquals("Reading status · Want to read", ReadingStatus.rowLabel("want"))
        assertEquals("Reading status · Reading", ReadingStatus.rowLabel("reading"))
        assertEquals("Reading status · Finished", ReadingStatus.rowLabel("finished"))
        assertEquals("Reading status · Not reading", ReadingStatus.rowLabel("not-reading"))
    }

    @Test fun `under the row, what that status means for this book`() {
        assertEquals("Want to read, reading, finished or not reading", ReadingStatus.rowDetail("", null))
        assertEquals("On your Want to read list", ReadingStatus.rowDetail("want", null))
        assertEquals("In Continue reading", ReadingStatus.rowDetail("reading", null))
        assertEquals("Off Continue reading and Home; your place stays", ReadingStatus.rowDetail("not-reading", null))
        assertEquals("Finished Sep 2025 · change the date", ReadingStatus.rowDetail("finished", ReadingYou(finished = "2025-09")))
        assertEquals("Finished · say when", ReadingStatus.rowDetail("finished", null))
        assertEquals("Finished · say when", ReadingStatus.rowDetail("finished", ReadingYou(status = "read")))
    }

    // ------------------------------------------------------------------ choosing

    @Test fun `choosing the status a book has changes nothing, except Finished, which can change the date`() {
        assertEquals(ReadingStatus.Action.NOTHING, ReadingStatus.action("reading", "reading"))
        assertEquals(ReadingStatus.Action.NOTHING, ReadingStatus.action("want", "want"))
        assertEquals(ReadingStatus.Action.SET, ReadingStatus.action("reading", "not-reading"))
        assertEquals(ReadingStatus.Action.SET, ReadingStatus.action("not-reading", ""))
        // Finished asks the month, whether or not it is finished already: that is how a date is put right.
        assertEquals(ReadingStatus.Action.ASK_MONTH, ReadingStatus.action("finished", "reading"))
        assertEquals(ReadingStatus.Action.ASK_MONTH, ReadingStatus.action("finished", "finished"))
    }

    @Test fun `Want to read is the list, so choosing it adds the book and anything else takes it off`() {
        assertTrue(ReadingStatus.effects("want", wasFinished = false).wantList)
        listOf("reading", "finished", "not-reading").forEach { assertFalse(it, ReadingStatus.effects(it, false).wantList) }
    }

    @Test fun `a book is marked read here when it becomes finished and unmarked when it stops being finished`() {
        assertEquals(ReadingStatus.LocalRead.MARK, ReadingStatus.effects("finished", wasFinished = false).localRead)
        assertEquals(ReadingStatus.LocalRead.KEEP, ReadingStatus.effects("finished", wasFinished = true).localRead)
        assertEquals(ReadingStatus.LocalRead.UNMARK, ReadingStatus.effects("reading", wasFinished = true).localRead)
        assertEquals(ReadingStatus.LocalRead.UNMARK, ReadingStatus.effects("not-reading", wasFinished = true).localRead)
        assertEquals(ReadingStatus.LocalRead.UNMARK, ReadingStatus.effects("want", wasFinished = true).localRead)
        assertEquals(ReadingStatus.LocalRead.KEEP, ReadingStatus.effects("reading", wasFinished = false).localRead)
    }

    @Test fun `the page shows the status at once as the hub will settle it`() {
        assertEquals("reading", ReadingStatus.settle("reading", progress(.5)))
        assertEquals("not-reading", ReadingStatus.settle("not-reading", progress(.5)))
        assertEquals("want", ReadingStatus.settle("want", null))
        assertEquals("want", ReadingStatus.settle("want", progress(0.0)))
        // Wanted with a place begun, short of the end, is being read.
        assertEquals("reading", ReadingStatus.settle("want", progress(.2)))
        assertEquals("want", ReadingStatus.settle("want", progress(1.0, true)))
    }

    // ------------------------------------------------------------------ what is written

    @Test fun `a status alone is one key`() {
        assertEquals("""{"status":"want"}""", ReadingYouEdits.status("want").toJson())
        assertEquals("""{"status":"not-reading"}""", ReadingYouEdits.status("not-reading").toJson())
        assertFalse(ReadingYouEdits.status("reading").isEmpty)
    }

    @Test fun `a finish says the status too, so a client that reads statuses gets the word and one that does not gets the month`() {
        val now = YearMonth.of(2026, 10)
        assertEquals("""{"finished":"2026-10","status":"finished"}""", ReadingYouEdits.finish(null, now, finishedNow = false).toJson())
        assertEquals("""{"finished":"2026-10","readCount":2,"status":"finished"}""",
            ReadingYouEdits.finish(ReadingYou(finished = "2025-09", readCount = 1), now, finishedNow = false).toJson())
        assertEquals("""{"finished":"2026-09","status":"finished"}""",
            ReadingYouEdits.finish(ReadingYou(finished = "2026-10", readCount = 2), YearMonth.of(2026, 9), finishedNow = true).toJson())
    }

    @Test fun `leaving a finish marked in this visit puts the page back, and says the new status`() {
        val before = ReadingYou(finished = "2025-09", readCount = 1)
        val now = ReadingYou(finished = "2026-10", readCount = 2)
        val undo = ReadingYouEdits.unfinish(now, before)
        assertEquals("""{"finished":"2025-09","readCount":1,"status":"reading"}""", ReadingYouEdits.status("reading", undo).toJson())
        // Where there was nothing to put back, the status alone.
        assertEquals("""{"status":"not-reading"}""", ReadingYouEdits.status("not-reading", null).toJson())
        // Where the page had no finish at all, taking the finish away clears the month and the count.
        assertEquals("""{"finished":null,"readCount":null,"status":"want"}""",
            ReadingYouEdits.status("want", ReadingYouEdits.unfinish(ReadingYou(finished = "2026-10", readCount = 1), null)).toJson())
    }

    @Test fun `the status key is sent, cleared or left out like the others`() {
        assertEquals("""{"status":null}""", ReadingYouPatch(status = YouEdit.Clear).toJson())
        assertEquals("""{}""", ReadingYouPatch().toJson())
        assertTrue(ReadingYouPatch().isEmpty)
        assertFalse(ReadingYouPatch(status = YouEdit.Clear).isEmpty)
    }

    // ------------------------------------------------------------------ under the cover

    @Test fun `the finish under the cover is for a book that is finished, the count of readings for a book read before`() {
        val read = ReadingYou(finished = "2025-09", readCount = 2, rating = 4, shelves = listOf("cosmere"))
        assertEquals(read, ReadingStatus.youForLine(ReadingWork(status = "finished", you = read)))
        // Read again: the month it was finished is history, "2nd time" is not.
        val again = ReadingStatus.youForLine(ReadingWork(status = "reading", you = read))!!
        assertEquals("", again.finished)
        assertEquals(2, again.readCount)
        assertEquals(4, again.rating)
        assertEquals("2nd time", ReadingBookPage.finished(again, finished = false))
        assertEquals("Finished Sep 2025 · 2nd time", ReadingBookPage.finished(read, finished = true))
        // An import that says read no longer says it for a book put down.
        assertEquals("", ReadingStatus.youForLine(ReadingWork(status = "not-reading", you = ReadingYou(status = "read")))!!.status)
        assertNull(ReadingStatus.youForLine(ReadingWork()))
    }

    // ------------------------------------------------------------------ from the hub

    @Test fun `a work, a book of a series and an answer carry the status`() {
        val work = json.decodeFromString<ReadingWork>(
            """{"id":"rw_1","status":"not-reading","you":{"chosen":"not-reading","shelves":[],"source":"app"},
               |"sections":[{"id":"s","items":[{"workId":"rw_2","status":"finished"},{"workId":"rw_3"}]}]}""".trimMargin())
        assertEquals("not-reading", work.status)
        assertEquals("not-reading", work.you?.chosen)
        assertEquals(listOf("finished", ""), work.sections.single().items.map { it.status })
        val answer = json.decodeFromString<ReadingYouResponse>("""{"workId":"rw_1","you":{"chosen":"want","shelves":[],"source":"app"},"status":"want"}""")
        assertEquals("want", answer.status)
        assertEquals("want", answer.you?.chosen)
        // A hub from before this knows no status at all.
        val old = json.decodeFromString<ReadingWork>("""{"id":"rw_1","sections":[{"id":"s","items":[{"workId":"rw_2"}]}]}""")
        assertEquals("", old.status)
        assertEquals("", old.sections.single().items.single().status)
    }
}

package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingAudioChapter
import com.pocketds.hub.playback.PlayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An aligned audiobook's own chapters (#31). The hub lists the read-along edition's
 * table of contents as places in the tracks (`source` "book"), so a chapter can start
 * in one track and run on into the next: the contents, the steps, the line under the
 * title, its times and the time left go by the chapter, counted across tracks, and a
 * book without chapters is read as it always was.
 */
class AudiobookChaptersTest {
    // Three tracks of 75 minutes, as Dark Matter's are.
    private val lengths = listOf<Long?>(4_500_000, 4_500_000, 4_500_000)
    private val parts = lengths.mapIndexed { index, length ->
        AudiobookPart("Track 0${index + 1}", uri = "https://hub/t/$index", durationMs = length)
    }

    private fun book(title: String, track: Int, startMs: Long) = ReadingAudioChapter(title, startMs, track, "book")

    // Two starts in the first track and ends in the second, Four from the second into the third.
    private val chapters = listOf(
        book("Chapter One", 0, 0), book("Chapter Two", 0, 3_492_550), book("Chapter Three", 1, 1_070_930),
        book("Chapter Four", 1, 2_521_240), book("Chapter Five", 2, 100_000)
    )
    private val entries = AudiobookContents.entries(parts, lengths, chapters)

    // ------------------------------------------------------------------ the contents

    @Test fun `the contents list the book's chapters by title, each lasting to the next one across the tracks`() {
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three", "Chapter Four", "Chapter Five"), entries.map { it.title })
        assertEquals(listOf(0, 0, 1, 1, 2), entries.map { it.part })
        assertEquals(listOf(0L, 3_492_550L, 1_070_930L, 2_521_240L, 100_000L), entries.map { it.startMs })
        // Two starts in the first track and lasts into the second; Four into the third; Five to the book's end.
        assertEquals(listOf(3_492_550L, 2_078_380L, 1_450_310L, 2_078_760L, 4_400_000L), entries.map { it.durationMs })
        // Between them, the whole book, with no file's name in the way.
        assertEquals(13_500_000L, entries.sumOf { it.durationMs!! })
        assertTrue(entries.none { it.title.startsWith("Track") })
        assertTrue(entries.all { it.chapter })
    }

    @Test fun `the voice before the first chapter belongs to it, so the book's first entry starts with the book`() {
        // The Final Empire opens with a minute of credits before its Prologue.
        val credits = listOf(book("Prologue", 0, 66_990), book("Chapter 1", 0, 2_219_720), book("Chapter 2", 0, 3_482_090))
        val found = AudiobookContents.entries(parts, lengths, credits)
        assertEquals(listOf("Prologue", "Chapter 1", "Chapter 2"), found.map { it.title })
        assertEquals(listOf(0L, 2_219_720L, 3_482_090L), found.map { it.startMs })
        assertEquals(2_219_720L, found[0].durationMs)
        // The last lasts to the end of the book, across the tracks after it.
        assertEquals(4_500_000L - 3_482_090 + 2 * 4_500_000L, found[2].durationMs)
        // Where the first chapter is in a later track, the book still starts at its beginning.
        val late = AudiobookContents.entries(parts, lengths, listOf(book("One", 1, 200_000), book("Two", 1, 900_000)))
        assertEquals(listOf(0 to 0L, 1 to 900_000L), late.map { it.part to it.startMs })
        assertEquals(4_500_000L + 900_000, late[0].durationMs)
    }

    @Test fun `chapters are put in the order they are heard, one to a moment, and those outside their track are left out`() {
        val jumbled = listOf(
            book("Four", 2, 100_000), book("Two", 0, 3_000_000), book("One", 0, 0), book("Twin of two", 0, 3_000_000),
            book("Past the end", 1, 9_999_999), book("Nowhere", 7, 5), book("Before", 0, -5), book("", 1, 2_000_000)
        )
        val found = AudiobookContents.entries(parts, lengths, jumbled)
        // Of two at one moment the one the hub listed first stays; an untitled one is named by its place in the list.
        assertEquals(listOf("One", "Two", "Chapter 3", "Four"), found.map { it.title })
        assertEquals(listOf(0 to 0L, 0 to 3_000_000L, 1 to 2_000_000L, 2 to 100_000L), found.map { it.part to it.startMs })
    }

    @Test fun `the contents list chapters by title whatever their source`() {
        val titles = chapters.map { it.title }
        // The book's own, and the file marks of an older hub, which sends no source: both by title.
        val marks = chapters.map { it.copy(source = "marks") }
        val unlabelled = chapters.map { it.copy(source = "") }
        listOf(chapters, marks, unlabelled).forEach { list ->
            val found = AudiobookContents.entries(parts, lengths, list)
            assertTrue(found.map { it.title }.containsAll(titles))
            assertEquals("chapter", AudiobookContents.noun(found))
        }
        // Marks stay inside their file: a track's opening before its first mark is an entry of its own.
        val inFiles = AudiobookContents.entries(parts, lengths, marks)
        assertEquals(listOf("Chapter One", "Chapter Two", "Track 02", "Chapter Three", "Chapter Four", "Track 03", "Chapter Five"),
            inFiles.map { it.title })
        assertEquals(inFiles, AudiobookContents.entries(parts, lengths, unlabelled))
        // The book's own cross the files: none of them is named for one.
        assertEquals(5, entries.size)
    }

    @Test fun `a book's chapters win where a hub mixes them with marks`() {
        val mixed = chapters + ReadingAudioChapter("A mark", 1_000_000, 1, "marks")
        assertEquals(entries, AudiobookContents.entries(parts, lengths, mixed))
    }

    @Test fun `a chapter's length is unknown while a track it runs across has none`() {
        val unknown = AudiobookContents.entries(parts.map { it.copy(durationMs = null) }, listOf(null, null, null), chapters)
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three", "Chapter Four", "Chapter Five"), unknown.map { it.title })
        assertEquals(listOf(0 to 0L, 0 to 3_492_550L, 1 to 1_070_930L, 1 to 2_521_240L, 2 to 100_000L), unknown.map { it.part to it.startMs })
        // One and Three lie within one track; the rest run across, or to the end of, a length not known.
        assertEquals(listOf(3_492_550L, null, 1_450_310L, null, null), unknown.map { it.durationMs })
        // The manifest's length counts when the player has none of its own, as for the parts.
        val known = AudiobookContents.entries(parts, listOf(null, null, null), chapters)
        assertEquals(entries.map { it.durationMs }, known.map { it.durationMs })
    }

    @Test fun `a book without chapters is its parts, and chapters that fit nowhere are no chapters`() {
        val plain = AudiobookContents.entries(parts, lengths, emptyList())
        assertEquals(listOf("Track 01", "Track 02", "Track 03"), plain.map { it.title })
        assertEquals(listOf(4_500_000L, 4_500_000L, 4_500_000L), plain.map { it.durationMs })
        assertEquals("part", AudiobookContents.noun(plain))
        assertEquals("part", AudiobookContents.noun(emptyList()))
        // Marks past the end of their track, or in a track the book does not have, are not chapters.
        val stray = AudiobookContents.entries(parts, lengths, listOf(book("Gone", 9, 5), book("Late", 0, 99_999_999)))
        assertEquals(plain, stray)
        assertEquals("part", AudiobookContents.noun(stray))
    }

    // ------------------------------------------------------------------ playing, and the steps

    @Test fun `the chapter playing is the last begun, wherever its track began`() {
        assertEquals(0, AudiobookContents.current(entries, 0, 5_000))
        assertEquals(1, AudiobookContents.current(entries, 0, 4_499_999))
        // Two began in the first track and is still playing in the second.
        assertEquals(1, AudiobookContents.current(entries, 1, 0))
        assertEquals(1, AudiobookContents.current(entries, 1, 1_070_929))
        assertEquals(2, AudiobookContents.current(entries, 1, 1_070_930))
        assertEquals(3, AudiobookContents.current(entries, 2, 99_999))
        assertEquals(4, AudiobookContents.current(entries, 2, 100_000))
    }

    @Test fun `next and previous go by chapter, across a track's end`() {
        // Forward from inside Two, in the second track: Three. From the last: nothing.
        assertEquals("Chapter Three", AudiobookContents.step(entries, 1, 500_000, 1, lengths)?.title)
        assertNull(AudiobookContents.step(entries, 2, 4_000_000, 1, lengths))
        // Forward in the first track's last seconds: Two began there, and Three is in the next track.
        val next = AudiobookContents.step(entries, 0, 4_499_000, 1, lengths)!!
        assertEquals("Chapter Three", next.title)
        assertEquals(1 to 1_070_930L, next.part to next.startMs)
        // Back from well into Two, now in the second track: Two's own start, in the first. Not One, and not Three.
        val back = AudiobookContents.step(entries, 1, 500_000, -1, lengths)!!
        assertEquals("Chapter Two", back.title)
        assertEquals(0 to 3_492_550L, back.part to back.startMs)
        // From the start of Two: the one before.
        assertEquals("Chapter One", AudiobookContents.step(entries, 0, 3_492_550 + 2_000, -1, lengths)?.title)
        // At the first: its own start, and nothing before it.
        assertEquals("Chapter One", AudiobookContents.step(entries, 0, 100, -1, lengths)?.title)
        assertNull(AudiobookContents.step(emptyList(), 0, 0, -1, lengths))
    }

    @Test fun `back from three seconds in restarts the chapter, the seconds counted across the track's end`() {
        // Two starts a second and a half before the first track ends.
        val near = AudiobookContents.entries(parts, lengths, listOf(book("One", 0, 0), book("Two", 0, 4_498_500), book("Three", 1, 600_000)))
        // A second into the next track is two and a half seconds into Two: back goes to One.
        assertEquals("One", AudiobookContents.step(near, 1, 1_000, -1, lengths)?.title)
        // Three seconds exactly is still not further in, as the part steps were.
        assertEquals("One", AudiobookContents.step(near, 1, 1_500, -1, lengths)?.title)
        // Past three seconds back restarts Two, which began in the other track.
        val restart = AudiobookContents.step(near, 1, 1_600, -1, lengths)!!
        assertEquals("Two", restart.title)
        assertEquals(0 to 4_498_500L, restart.part to restart.startMs)
        // A track's length not known: it is taken as well in, so back restarts the chapter rather than skipping it.
        assertEquals("Two", AudiobookContents.step(near, 1, 1_000, -1, emptyList())?.title)
        assertEquals("Two", AudiobookContents.step(near, 1, 1_000, -1, listOf(null, null, null))?.title)
    }

    // ------------------------------------------------------------------ what the line and its times measure

    @Test fun `the line, its times and the timeline are the chapter's, counted across tracks`() {
        // Five hundred seconds into the second track is in Two, which began 1007 seconds before this track did.
        val span = AudiobookContents.span(entries, 1, 500_000, 4_500_000, lengths)
        assertEquals(1, span.entry)
        assertEquals("Chapter Two", span.title)
        assertEquals(1_507_450L, span.positionMs)
        assertEquals(2_078_380L, span.durationMs)
        assertEquals(570_930L, span.leftMs)
        assertTrue(span.measuresEntry)
        // In the first track, from its start.
        val first = AudiobookContents.span(entries, 0, 4_000_000, 4_500_000, lengths)
        assertEquals("Chapter Two", first.title)
        assertEquals(507_450L, first.positionMs)
        assertEquals(1_570_930L, first.leftMs)
    }

    @Test fun `the player's own length of the track playing counts before the manifest's`() {
        // Five runs to where the player says the book ends.
        val last = AudiobookContents.span(entries, 2, 1_000_000, 4_500_400, lengths)
        assertEquals(4, last.entry)
        assertEquals(900_000L, last.positionMs)
        assertEquals(4_400_400L, last.durationMs)
        // A player that has not read the length yet: the manifest's.
        assertEquals(4_400_000L, AudiobookContents.span(entries, 2, 1_000_000, 0, lengths).durationMs)
    }

    @Test fun `without chapters the line and its times are the part's, as they were`() {
        val plain = AudiobookContents.entries(parts, lengths, emptyList())
        val span = AudiobookContents.span(plain, 1, 12_345, 4_500_321, lengths)
        assertEquals(1, span.entry)
        assertEquals("Track 02", span.title)
        assertEquals(12_345L, span.positionMs)
        assertEquals(4_500_321L, span.durationMs)
        assertEquals(4_487_976L, span.leftMs)
        // Nothing known of the part yet: the position as the player has it.
        val opening = AudiobookContents.span(plain, 0, 2_000, 0, listOf(null, null, null))
        assertEquals(2_000L to 0L, opening.positionMs to opening.durationMs)
        assertFalse(opening.measuresEntry)
        // No book on the player at all.
        val none = AudiobookContents.span(emptyList(), 0, 0, 0, emptyList())
        assertEquals(-1, none.entry)
        assertEquals("", none.title)
    }

    @Test fun `a chapter whose length is not known yet is named, and measured by the part`() {
        val unknown = AudiobookContents.entries(parts.map { it.copy(durationMs = null) }, listOf(null, null, null), chapters)
        val span = AudiobookContents.span(unknown, 1, 500_000, 4_500_000, listOf(null, null, null))
        // Two began in the first track, whose length is not known: the chapter is named, the part's time is told.
        assertEquals("Chapter Two", span.title)
        assertFalse(span.measuresEntry)
        assertEquals(500_000L, span.positionMs)
        assertEquals(4_500_000L, span.durationMs)
        assertEquals(1 to 250_000L, AudiobookContents.place(span, 250_000, listOf(null, null, null)))
    }

    @Test fun `a moment in the chapter is a place in the tracks`() {
        val span = AudiobookContents.span(entries, 1, 500_000, 4_500_000, lengths)
        assertEquals(1 to 500_000L, AudiobookContents.place(span, 1_507_450, lengths))
        assertEquals(0 to 3_492_550L, AudiobookContents.place(span, 0, lengths))
        assertEquals(0 to 4_492_550L, AudiobookContents.place(span, 1_000_000, lengths))
        assertEquals(1 to 0L, AudiobookContents.place(span, 1_007_450, lengths))
        // Not before its start, and not past its end, which is where the next one begins.
        assertEquals(0 to 3_492_550L, AudiobookContents.place(span, -5, lengths))
        assertEquals(1 to 1_070_930L, AudiobookContents.place(span, 9_999_999, lengths))
        // A part's: the moment in it.
        val plain = AudiobookContents.entries(parts, lengths, emptyList())
        assertEquals(1 to 77_000L, AudiobookContents.place(AudiobookContents.span(plain, 1, 5_000, 4_500_000, lengths), 77_000, lengths))
    }

    // ------------------------------------------------------------------ where a track's end is a chapter's

    @Test fun `a track's end ends the chapter only when the next track begins another`() {
        // Two runs on past the first track's end, and Four past the second's.
        assertFalse(AudiobookContents.endsWithPart(entries, 0, 4_500_000))
        assertFalse(AudiobookContents.endsWithPart(entries, 1, 4_500_000))
        // Chapters that begin where their tracks do.
        val aligned = AudiobookContents.entries(parts, lengths, listOf(book("One", 0, 0), book("Two", 1, 0), book("Three", 2, 0)))
        assertTrue(AudiobookContents.endsWithPart(aligned, 0, 4_500_000))
        assertTrue(AudiobookContents.endsWithPart(aligned, 1, 4_500_000))
        // A part always ends where it does.
        val plain = AudiobookContents.entries(parts, lengths, emptyList())
        assertTrue(AudiobookContents.endsWithPart(plain, 0, 4_500_000))
        assertTrue(AudiobookContents.endsWithPart(emptyList(), 0, 4_500_000))
        // Marks stay in their file, which begins an entry of its own: every file's end is one's.
        val marks = AudiobookContents.entries(parts, lengths, chapters.map { it.copy(source = "marks") })
        assertTrue(AudiobookContents.endsWithPart(marks, 0, 4_500_000))
        assertTrue(AudiobookContents.endsWithPart(marks, 1, 4_500_000))
    }

    // ------------------------------------------------------------------ what the player's state says

    private fun playing(chapters: List<ReadingAudioChapter>, part: Int, position: Long, partMs: Long = 4_500_000, speed: Float = 1f) =
        ListeningState(book = ReadingAudioBook("rw_1", "book-1", "Dark Matter", parts, positionKey = "", chapters = chapters),
            playing = true, ready = true, part = part, positionMs = position, partMs = partMs, partsMs = lengths, speed = speed)

    @Test fun `the state names the chapter and counts what is left of it as heard, with the book's beside it`() {
        val state = playing(chapters, part = 1, position = 500_000, speed = 1.5f)
        assertEquals("chapter", state.noun)
        assertEquals("Chapter Two", state.span.title)
        assertEquals("Chapter Two", state.chapter)
        // 570,930 ms of the recording left in Two, as heard at one and a half.
        assertEquals(380_620L, state.spanLeftMs)
        // The book's own: the rest of this track and the one after, as heard.
        assertEquals(Listening.heard(4_000_000 + 4_500_000, 1.5f), state.bookLeftMs)
        assertEquals("6 min left in chapter · 1h 34m in book", PlayerLabels.timeLeft(state.spanLeftMs, state.bookLeftMs, state.noun))
    }

    @Test fun `a book without chapters is measured by part, as it was`() {
        val state = playing(emptyList(), part = 1, position = 500_000, partMs = 4_500_100)
        assertEquals("part", state.noun)
        assertNull(state.chapter)
        assertEquals("Track 02", state.span.title)
        // The part's own length, by the player, less the place in it.
        assertEquals(4_000_100L, state.spanLeftMs)
        assertEquals("1h 6m left in part", PlayerLabels.timeLeft(state.spanLeftMs, null, state.noun))
    }

    @Test fun `no book on the player has no chapter`() {
        val none = ListeningState()
        assertEquals("part", none.noun)
        assertNull(none.chapter)
        assertTrue(none.contents.isEmpty())
    }
}

package com.pocketds.hub.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhraseLookupTest {
    /** A dictionary of a few entries; every question asked of it is recorded. */
    private class Book(vararg entries: String) {
        val known = entries.toSet()
        val asked = ArrayList<List<String>>()
        suspend fun lookup(requested: String, terms: List<String>): DictionaryEntry {
            asked += terms
            val hit = terms.firstOrNull { it in known }
            return DictionaryEntry(requested, hit.orEmpty(), if (hit == null) emptyList() else listOf(DictionaryDefinition("noun", "the meaning of $hit")))
        }
    }

    private fun answer(book: Book, selection: String) = runBlocking { PhraseLookup.answer(selection, book::lookup) }

    @Test fun theWordsOfASelectionHaveNoPunctuationRoundThem() {
        assertEquals(listOf("take", "off"), PhraseLookup.words("take off"))
        assertEquals(listOf("Don’t", "go"), PhraseLookup.words("“Don’t go,”"))
        assertEquals(listOf("well-known", "bird"), PhraseLookup.words(" — well-known bird. "))
        assertEquals(listOf("שלום", "עולם"), PhraseLookup.words("שלום, עולם"))
        assertEquals(emptyList<String>(), PhraseLookup.words("… ”"))
    }

    @Test fun oneWordIsLookedUpByItsBaseFormsAndOpensTheCardAtOnce() {
        val book = Book("bell")
        val got = answer(book, "bells")
        assertTrue(got.found)
        assertEquals("bell", got.shown)
        assertFalse(got.isPhrase)
        assertTrue(got.opensCard)
        assertNull(got.note)
        assertEquals(listOf(listOf("bells", "bell")), book.asked)
    }

    @Test fun oneWordWithNoEntryStillOpensTheCardAndSaysSo() {
        val got = answer(Book(), "Maren")
        assertFalse(got.found)
        assertTrue(got.opensCard)
        assertEquals("No entry for “Maren”.", got.note)
    }

    @Test fun aPhraseIsTriedAsOneEntryFirstWithTheFirstWordsBaseForms() {
        val book = Book("pull off")
        val got = answer(book, "pulled off")
        assertTrue(got.found)
        assertEquals("pull off", got.shown)
        assertTrue(got.isPhrase)
        assertTrue("a phrase the dictionary knows opens the card", got.opensCard)
        assertNull(got.note)
        assertEquals(1, book.asked.size)
        assertEquals("pulled off", book.asked[0][0])
        assertTrue("pull off" in book.asked[0])
    }

    @Test fun thePhraseKeepsItsLaterWordsAsWrittenAndLowerCased() {
        val book = Book("in spite of")
        assertEquals("in spite of", answer(book, "In spite of").shown)
        assertEquals(listOf("in spite of"), PhraseLookup.phraseTerms(listOf("In", "spite", "of")))
        assertTrue("take off" in PhraseLookup.phraseTerms(listOf("takes", "off")))
        assertEquals(emptyList<String>(), PhraseLookup.phraseTerms(listOf("lantern")))
    }

    @Test fun aPhraseWithNoEntryShowsTheFirstWordThatHasOneAndNeverSilently() {
        val book = Book("harbor", "bell")
        val got = answer(book, "harbor bells rang")
        assertTrue(got.found)
        assertEquals("harbor", got.shown)
        assertEquals("harbor bells rang", got.missed)
        assertEquals("No entry for “harbor bells rang”. Showing “harbor”.", got.note)
        assertFalse("the bar offers Look up instead of opening the card", got.opensCard)
        // Not "the" or "rang": the first word that has an entry.
        val skipped = answer(Book("bell"), "the bells rang")
        assertEquals("bell", skipped.shown)
    }

    @Test fun aPhraseNoWordOfWhichIsInTheDictionaryIsSaidToHaveNoEntry() {
        val got = answer(Book(), "Maren Keld")
        assertFalse(got.found)
        assertFalse(got.opensCard)
        assertEquals("No entry for “Maren Keld”.", got.note)
        assertEquals("Maren Keld", got.shown)
    }

    @Test fun nothingButPunctuationIsNoEntryEither() {
        val got = answer(Book(), "…")
        assertFalse(got.found)
        assertNotNull(got.note)
    }
}

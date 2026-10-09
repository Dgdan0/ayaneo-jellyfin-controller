package com.pocketds.hub.reader

/**
 * What a selection is looked up as (#62). One word is looked up by its base forms ([DictionaryTerms]). A phrase ("take off",
 * "in spite of", "pulled off") is tried first as one entry, the first word in its base forms and the rest as written; when
 * the dictionary has no such entry the answer says so and shows the first word that has one, instead of quietly showing a
 * single word the person did not ask about, which is what a reader that falls back silently does.
 *
 * The dictionary is passed in as a function, so a JVM test stands in for the database.
 */
object PhraseLookup {
    /** What the card says: [requested] is what was selected, [entry] the dictionary's answer (null when it has none), [missed] set when it is not an answer to [requested]. */
    data class Answer(
        val requested: String,
        val entry: DictionaryEntry?,
        /** The word or phrase the definitions are of. */
        val shown: String,
        /** The phrase the dictionary has no entry for: "No entry for “…”." */
        val missed: String?,
        val isPhrase: Boolean
    ) {
        val found: Boolean get() = entry?.definitions?.isNotEmpty() == true
        /** A phrase the dictionary knows, or any single word: the card opens at once. */
        val opensCard: Boolean get() = !isPhrase || (found && missed == null)
        /** The line under the heading when what is shown is not what was selected. */
        val note: String? get() = missed?.let { "No entry for “$it”." + if (found) " Showing “$shown”." else "" }
    }

    /** A phrase longer than this is not looked up as one entry: the dictionary has none, and the bar spares the question. */
    const val MAX_PHRASE_WORDS = 6

    /** The words of a selection, without the punctuation round them: letters, digits, and the apostrophes and hyphens inside a word. */
    fun words(selection: String): List<String> = WORD.findAll(selection).map { it.value.trim('\'', '’', '-') }.filter { it.isNotEmpty() }.toList()

    /** The entries to try for the phrase as one: its first word in each base form, then the rest, all lower case. */
    fun phraseTerms(words: List<String>): List<String> {
        if (words.size < 2) return emptyList()
        val rest = words.drop(1).joinToString(" ") { it.lowercase() }
        return DictionaryTerms.candidates(words[0]).map { "$it $rest" }
    }

    suspend fun answer(selection: String, lookup: suspend (requested: String, terms: List<String>) -> DictionaryEntry): Answer {
        val words = words(selection)
        val text = words.joinToString(" ")
        if (words.isEmpty()) return Answer(selection.trim(), null, selection.trim(), selection.trim().ifEmpty { null }, isPhrase = false)
        if (words.size == 1) {
            val entry = lookup(words[0], DictionaryTerms.candidates(words[0]))
            return if (entry.definitions.isNotEmpty()) Answer(words[0], entry, entry.headword, null, isPhrase = false)
            else Answer(words[0], null, words[0], words[0], isPhrase = false)
        }
        val whole = lookup(text, phraseTerms(words))
        if (whole.definitions.isNotEmpty()) return Answer(text, whole, whole.headword, null, isPhrase = true)
        for (word in words) {
            val entry = lookup(word, DictionaryTerms.candidates(word))
            if (entry.definitions.isNotEmpty()) return Answer(text, entry, entry.headword, text, isPhrase = true)
        }
        return Answer(text, null, text, text, isPhrase = true)
    }

    private val WORD = Regex("[\\p{L}\\p{M}\\p{N}][\\p{L}\\p{M}\\p{N}'’\\-]*")
}

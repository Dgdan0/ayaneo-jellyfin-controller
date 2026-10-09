package com.pocketds.hub.reader

import java.text.Normalizer
import java.util.Locale

/** A definition pauses narration only when it was playing before selection. */
class NarrationSelectionGate {
    private var selected = false
    private var resumeOnDismiss = false

    fun begin(playing: Boolean): Boolean {
        if (selected) return false
        selected = true
        resumeOnDismiss = playing
        return playing
    }

    fun playFromSelection() {
        resumeOnDismiss = false
    }

    fun dismiss(): Boolean {
        val resume = selected && resumeOnDismiss
        selected = false
        resumeOnDismiss = false
        return resume
    }

    fun cancel() {
        selected = false
        resumeOnDismiss = false
    }
}

object ReadAlongSelectionTarget {
    /** Ancestors are ordered from the selected word outward to its paragraph. */
    fun find(timeline: ReadAlongTimeline, href: String, ancestors: List<String>): ReadAlongPosition? =
        ancestors.firstNotNullOfOrNull { fragment -> timeline.find(href, fragment) }
}

object DictionaryTerms {
    fun candidates(raw: String): List<String> {
        val word = Normalizer.normalize(raw, Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .trim { !it.isLetter() && it != '\'' && it != '’' }
            .replace('’', '\'')
        if (word.none(Char::isLetter) || word.length > 80) return emptyList()
        return buildList {
            add(word)
            when {
                word.endsWith("ies") && word.length > 4 -> add(word.dropLast(3) + "y")
                word.endsWith("ing") && word.length > 5 -> {
                    val root = word.dropLast(3)
                    add(root)
                    if (root.length >= 2 && root.last() == root[root.lastIndex - 1]) add(root.dropLast(1))
                    else add(root + "e")
                }
                word.endsWith("ed") && word.length > 4 -> {
                    val root = word.dropLast(2)
                    add(root)
                    if (root.length >= 2 && root.last() == root[root.lastIndex - 1]) add(root.dropLast(1))
                    add(word.dropLast(1))
                }
                word.endsWith("es") && word.length > 4 -> { add(word.dropLast(2)); add(word.dropLast(1)) }
                word.endsWith("s") && word.length > 3 -> add(word.dropLast(1))
            }
        }.distinct()
    }
}

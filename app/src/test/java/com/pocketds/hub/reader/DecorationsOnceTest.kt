package com.pocketds.hub.reader

import org.junit.Assert.*
import org.junit.Test

class DecorationsOnceTest {
    @Test fun theScriptNamesEachGroupWithTheIdsTheReaderHoldsAndTheGroupsKeptByIdOnly() {
        val script = DecorationsOnce.script(
            mapOf(ReaderMarks.GROUP to listOf("an_" + "a".repeat(32), "an_" + "b".repeat(32)), ReaderMarks.NOTES_GROUP to listOf("note:an_" + "a".repeat(32))),
            duplicatesOnly = listOf(ReaderMarks.HEARD_GROUP, ReadAlongGlow.GROUP))
        assertTrue(script, script.contains("'annotations':['an_${"a".repeat(32)}','an_${"b".repeat(32)}']"))
        assertTrue(script, script.contains("'annotation-notes':['note:an_${"a".repeat(32)}']"))
        assertTrue(script, script.contains("['heard','readalong']"))
        // Readium's own group, and its items: each one is taken off the page and out of the list, as the group's remove does.
        assertTrue(script.contains("readium.getDecorations(name)"))
        assertTrue(script.contains("item.container.remove()"))
        // The newest copy of an id is the one kept: the list is read from its end.
        assertTrue(script.contains("for (var i = g.items.length - 1; i >= 0; i--)"))
        // And the group's add replaces an id it has from then on, once a page.
        assertTrue(script.contains("g.__pocketOnce = true"))
        assertTrue(script.contains("remove(d.id)"))
    }

    @Test fun anIdIsQuotedSoItCannotEndTheScript() {
        val script = DecorationsOnce.script(mapOf("annotations" to listOf("a'b</script>")))
        assertTrue(script, script.contains("'a\\'b\\u003c/script>'"))
        assertFalse(script.contains("a'b"))
    }

    @Test fun aGroupWithNothingInItTakesEveryItemAway() {
        val script = DecorationsOnce.script(mapOf("annotations" to emptyList()))
        assertTrue(script, script.contains("'annotations':[]"))
    }
}

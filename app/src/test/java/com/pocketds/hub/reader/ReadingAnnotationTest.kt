package com.pocketds.hub.reader

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReadingAnnotationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun theFourColoursAreTheHubsAndAnUnknownOneIsYellow() {
        assertEquals(listOf("yellow", "blue", "pink", "green"), HighlightColor.entries.map { it.id })
        assertEquals(HighlightColor.PINK, HighlightColor.of("pink"))
        assertNull(HighlightColor.of("purple"))
        assertEquals(HighlightColor.YELLOW, HighlightColor.orDefault("purple"))
        assertEquals(HighlightColor.YELLOW, HighlightColor.orDefault(null))
    }

    @Test fun anAnnotationReadsTheHubsJsonAndKeepsWhatItDoesNotUnderstand() {
        val text = """{"id":"an_0123456789abcdef0123456789abcdef","color":"blue","note":"who lit it?","document":"OEBPS/chapter-1.xhtml",
            "quote":{"before":"the old light, like a ","highlight":"bird","after":". She had laughed"},
            "locator":{"href":"OEBPS/chapter-1.xhtml","locations":{"progression":0.4}},
            "createdAt":10,"updatedAt":20,"syncedAt":30,"somethingNew":true}"""
        val annotation = json.decodeFromString<ReadingAnnotation>(text)
        assertEquals("bird", annotation.quote.highlight)
        assertEquals(HighlightColor.BLUE, annotation.highlightColor)
        assertTrue(annotation.hasNote)
        assertEquals(30L, annotation.syncedAt)
        assertFalse(annotation.deleted)
        assertEquals(annotation, json.decodeFromString<ReadingAnnotation>(json.encodeToString(ReadingAnnotation.serializer(), annotation)))
    }

    @Test fun aQuoteKeepsTheWordsAroundTheSelectionAndNoMoreThanAFewOfThem() {
        val before = "x".repeat(300) + " She did not stop to look at them. In spite of the cold, she pulled off her "
        val quote = AnnotationQuotes.of(before, "  gloves \n and set ", " her palm against the lighthouse door. The iron was warm. Somewhere above her the lantern burned, still, and brightly")
        assertEquals("gloves and set", quote.highlight)
        assertTrue(quote.before.length <= AnnotationQuotes.CONTEXT)
        assertTrue(quote.after.length <= AnnotationQuotes.CONTEXT)
        assertTrue("the context ends at the selection: ${quote.before}", quote.before.endsWith("pulled off her"))
        assertTrue(quote.after.startsWith("her palm against"))
        // It starts at a word, not in the middle of one.
        assertTrue(quote.before.first() == 'o' || quote.before.first().isLetter())
        assertFalse(quote.before.startsWith("xx"))
    }

    @Test fun contextShorterThanTheLimitIsKeptWhole() {
        val quote = AnnotationQuotes.of("the old light, like a ", "bird", ".” She had laughed")
        assertEquals("the old light, like a", quote.before)
        assertEquals(".” She had laughed", quote.after)
        assertEquals(AnnotationQuote(), AnnotationQuotes.of(null, "", null).copy())
    }

    @Test fun aPassageIsFoundWhateverTheQuotesAndSpacingOfTheEdition() {
        val text = "“They said it would take off on its own,”\n   her brother had told her, “the old light, like a bird.”"
        val quote = AnnotationQuote("it would", "take off on its OWN,” her brother", "had told her")
        val found = AnnotationFinder.find(text, quote)!!
        assertNotNull(found)
        assertTrue(found.start in 0 until found.end)
        assertEquals(1, found.places)
        assertEquals(AnnotationFinder.normalize("take off on its own,” her brother").length, found.end - found.start)
    }

    @Test fun curlyAndStraightApostrophesAndTheEllipsisAreTheSamePassage() {
        assertNotNull(AnnotationFinder.find("Don’t go… now", AnnotationQuote(highlight = "don't go... now")))
        assertNotNull(AnnotationFinder.find("Don't go... now", AnnotationQuote(highlight = "Don’t go… now")))
    }

    @Test fun aPassageThatIsNotInTheTextIsNotFound() {
        assertNull(AnnotationFinder.find("She did not stop to look at them.", AnnotationQuote(highlight = "she did stop")))
        assertNull(AnnotationFinder.find("", AnnotationQuote(highlight = "anything")))
        assertNull(AnnotationFinder.find("some text", AnnotationQuote(highlight = "   ")))
    }

    @Test fun whenTwoPlacesSayTheSameWordsTheOneWithTheRightNeighboursWins() {
        val text = "He saw the bird fly. Later, the old light, like a bird. She laughed at the bird too."
        val second = AnnotationFinder.find(text, AnnotationQuote("the old light, like a ", "bird", ". She laughed"))!!
        val first = AnnotationFinder.find(text, AnnotationQuote("He saw the ", "bird", " fly."))!!
        val third = AnnotationFinder.find(text, AnnotationQuote("laughed at the ", "bird", " too."))!!
        assertEquals(3, second.places)
        assertTrue(first.start < second.start && second.start < third.start)
        assertEquals(AnnotationFinder.normalize(text).indexOf("bird fly"), first.start)
    }

    @Test fun withNoContextToChooseByTheHintOrElseTheFirstPlaceDecides() {
        val text = "bird. " + "x ".repeat(50) + "bird. " + "y ".repeat(50) + "bird."
        val any = AnnotationFinder.find(text, AnnotationQuote(highlight = "bird"))!!
        assertEquals(0, any.start)
        val near = AnnotationFinder.find(text, AnnotationQuote(highlight = "bird"), hint = 0.5)!!
        assertTrue("nearest the middle: ${near.share}", near.share in 0.3..0.7)
        val end = AnnotationFinder.find(text, AnnotationQuote(highlight = "bird"), hint = 1.0)!!
        assertTrue(end.share > 0.9)
    }

    @Test fun howFarThroughTheDocumentAPassageIsGivesItsPageNumberToTheList() {
        val text = "a".repeat(500) + " the lantern was burning " + "b".repeat(500)
        val found = AnnotationFinder.find(text, AnnotationQuote(highlight = "the lantern was burning"))!!
        assertEquals(0.5, found.share, 0.02)
    }
}

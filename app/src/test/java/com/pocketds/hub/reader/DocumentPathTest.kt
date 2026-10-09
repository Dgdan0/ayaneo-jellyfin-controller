package com.pocketds.hub.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one spelling of a content document's path (#61): Mistborn's documents are called
 * `Brandon Sanderson - [Mistborn 01] - The Final Empire_split_010.htm`, which the narration's SMIL writes with its raw
 * spaces and brackets and Readium hands back percent-encoded, and the two never matched.
 */
class DocumentPathTest {
    private val mistborn = "Brandon Sanderson - [Mistborn 01] - The Final Empire_split_010.htm"
    private val encoded = "Brandon%20Sanderson%20-%20%5BMistborn%2001%5D%20-%20The%20Final%20Empire_split_010.htm"

    @Test fun `Readium's encoded spelling and the book's raw one are the same document`() {
        assertEquals(mistborn, DocumentPath.of(encoded))
        assertEquals(mistborn, DocumentPath.of(mistborn))
        assertEquals(DocumentPath.of(encoded), DocumentPath.of(mistborn))
        // Within a folder, with a fragment and a leading slash, as a locator or a link may say it.
        assertEquals("OEBPS/Text/$mistborn", DocumentPath.of("/OEBPS/Text/$encoded#html61-s0"))
    }

    @Test fun `a fragment is not part of the document, an escaped hash is part of its name`() {
        assertEquals("one.xhtml", DocumentPath.of("one.xhtml#s12"))
        assertEquals("Chapter #1.xhtml", DocumentPath.of("Chapter%20%231.xhtml#s1"))
        assertEquals("one.xhtml", DocumentPath.of("one.xhtml#"))
    }

    @Test fun `a letter written as one character or as two is one letter`() {
        val composed = "Café - Zoë.xhtml"
        val decomposed = "Café - Zoë.xhtml"
        assertNotEquals(composed, decomposed)
        assertEquals(composed, DocumentPath.name(decomposed))
        assertEquals(composed, DocumentPath.of("Caf%C3%A9%20-%20Zo%C3%AB.xhtml"))
        assertEquals(composed, DocumentPath.of("Cafe%CC%81%20-%20Zoe%CC%88.xhtml"))
        assertEquals(composed, DocumentPath.of(decomposed))
    }

    @Test fun `a percent that is not an escape stays, and so does a run that is not UTF-8`() {
        assertEquals("100%.xhtml", DocumentPath.of("100%.xhtml"))
        assertEquals("a%zzb.xhtml", DocumentPath.of("a%zzb.xhtml"))
        assertEquals("tail%", DocumentPath.of("tail%"))
        assertEquals("tail%4", DocumentPath.of("tail%4"))
        // Latin-1 bytes, not UTF-8: left as written rather than turned into replacement characters.
        assertEquals("caf%E9.xhtml", DocumentPath.of("caf%E9.xhtml"))
        assertEquals("café %E9.xhtml", DocumentPath.of("caf%C3%A9%20%E9.xhtml"))
        // A four byte character (an emoji), and lower case hex.
        assertEquals("one 📖.xhtml", DocumentPath.of("one%20%F0%9F%93%96.xhtml"))
        assertEquals("café.xhtml", DocumentPath.of("caf%c3%a9.xhtml"))
    }

    @Test fun `encoding is Readium's own spelling of a decoded path and decodes back to it`() {
        // What Readium's Url.fromDecodedPath (Android's Uri.encode, leaving the path's own $&+,/:=@) makes of it.
        assertEquals(encoded, DocumentPath.encode(mistborn))
        assertEquals("OEBPS/Text/a%20b.xhtml", DocumentPath.encode("OEBPS/Text/a b.xhtml"))
        assertEquals("Caf%C3%A9.xhtml", DocumentPath.encode("Café.xhtml"))
        assertEquals("a%23b%25c.xhtml", DocumentPath.encode("a#b%c.xhtml"))
        assertEquals("a+b&c,d:e=f@g\$h.xhtml", DocumentPath.encode("a+b&c,d:e=f@g\$h.xhtml"))
        assertEquals("it's_(ok)!~*.xhtml", DocumentPath.encode("it's_(ok)!~*.xhtml"))
        listOf(mistborn, "OEBPS/Text/Café [2].xhtml", "a#b%c d.xhtml", "100%.xhtml", "x%20y.xhtml", "日本語/章 1.xhtml", "a+b.xhtml").forEach {
            assertEquals(it, DocumentPath.of(DocumentPath.encode(it)))
        }
    }

    @Test fun `a reference resolves against the document holding it, whatever is in the file name`() {
        val base = "OEBPS/MediaOverlays/$mistborn".replace(".htm", ".smil")
        // As Storyteller writes it: raw spaces and brackets.
        assertEquals("OEBPS/$mistborn" to "html61-s0", DocumentPath.resolve(base, "../$mistborn#html61-s0"))
        // As a producer that percent-encodes writes it.
        assertEquals("OEBPS/$mistborn" to "html61-s0", DocumentPath.resolve(base, "../$encoded#html61-s0"))
        // Half and half.
        assertEquals("OEBPS/Audio/Track [01] é.mp3" to "", DocumentPath.resolve(base, "../Audio/Track [01] %C3%A9.mp3"))
        assertEquals("Audio/a.mp3" to "", DocumentPath.resolve("", "Audio/a.mp3"))
        assertEquals("Audio/a.mp3" to "", DocumentPath.resolve("", "./Audio/./a.mp3"))
        assertEquals("OEBPS/package.opf" to "", DocumentPath.resolve("", "OEBPS//package.opf"))
        assertEquals("OEBPS/sibling.xhtml" to "", DocumentPath.resolve("OEBPS/one.xhtml", "sibling.xhtml"))
        assertEquals("sibling.xhtml" to "", DocumentPath.resolve("one.xhtml", "sibling.xhtml"))
        // The fragment is decoded: it is the id as the page spells it.
        assertEquals("a.xhtml" to "café 1", DocumentPath.resolve("", "a.xhtml#caf%C3%A9%201"))
        // A reference to nothing but a fragment is the document itself.
        assertEquals("OEBPS/one.xhtml" to "s3", DocumentPath.resolve("OEBPS/one.xhtml", "#s3"))
        // The result is in the one spelling.
        assertEquals("Café.xhtml" to "", DocumentPath.resolve("", "Café.xhtml"))
    }

    @Test fun `only a path inside the package resolves`() {
        listOf(
            "", "   ", "https://evil/audio.mp3", "file:///secret.mp3", "mailto:a@b.c", "//host/a.mp3", "/abs/a.mp3",
            "../../outside.mp3", "..", "a/../../../b.mp3", "back\\slash.mp3", "a.mp3?x=1", "dir/", "%2E%2E/%2E%2E/b.mp3", "a%5Cb.mp3"
        ).forEach { assertNull("'$it'", DocumentPath.resolve("OEBPS/x.smil", it)) }
        // A question mark in a fragment is no query; an escaped one in a name is part of the name.
        assertEquals("OEBPS/a.xhtml" to "why?", DocumentPath.resolve("OEBPS/x.smil", "a.xhtml#why?"))
        assertEquals("OEBPS/what?.xhtml" to "", DocumentPath.resolve("OEBPS/x.smil", "what%3F.xhtml"))
        // A colon after the first slash is a name, not a scheme.
        assertEquals("OEBPS/dir/a:b.xhtml" to "", DocumentPath.resolve("OEBPS/x.smil", "dir/a:b.xhtml"))
    }
}

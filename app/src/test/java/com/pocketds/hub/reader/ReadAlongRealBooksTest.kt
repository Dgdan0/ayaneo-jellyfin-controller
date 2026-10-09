package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The two real Mistborn read-alongs (#61), whose content documents are named `Brandon Sanderson - [Mistborn 01] - The
 * Final Empire_split_010.htm`: every `<par>` of their overlays resolves, and every spine document, as Readium spells its
 * href, finds the sentences the overlay narrates in it. Read-only, straight from Storyteller's folder, never copied or
 * committed; each test is skipped where the file is not there (any machine but the media PC).
 */
class ReadAlongRealBooksTest {
    private val assets = File("D:\\Apps\\PocketDS\\Reading\\storyteller\\assets")
    private val normal = File(assets, "Mistborn- The Final Empire\\aligned\\Mistborn- The Final Empire.epub")
    private val dramatized = File(assets, "The Final Empire (Dramatized) [IfMSfqos]\\aligned\\The Final Empire (Dramatized).epub")

    @Test fun `the Final Empire read along resolves every par and every spine document finds its sentences`() = check(normal)

    @Test fun `the dramatized Final Empire read along resolves every par and every spine document finds its sentences`() = check(dramatized)

    private fun elements(root: Element, name: String): List<Element> =
        root.getElementsByTagNameNS("*", name).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun dom(zip: ZipFile, path: String, entries: Map<String, String>): Element {
        val entry = zip.getEntry(entries.getValue(path))
        return zip.getInputStream(entry).use {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it).documentElement
        }
    }

    private fun check(file: File) {
        assumeTrue("The real read-along is not on this machine: ${file.path}", file.isFile)
        val timeline = ReadAlongPackage.read(file)
        ZipFile(file).use { zip ->
            // The zip's names in the one spelling, to the entry's own name.
            val entries = LinkedHashMap<String, String>()
            for (entry in zip.entries()) entries.putIfAbsent(DocumentPath.name(entry.name), entry.name)

            val opfPath = elements(dom(zip, "META-INF/container.xml", entries), "rootfile").first().getAttribute("full-path")
            val packagePath = DocumentPath.resolve("", opfPath)!!.first
            val opf = dom(zip, packagePath, entries)
            val manifest = elements(opf, "item").associateBy { it.getAttribute("id") }
            val spine = elements(opf, "itemref").map { manifest.getValue(it.getAttribute("idref")) }

            var pars = 0
            var narrated = 0
            // What each document should have: its fragments, from the overlay, the clips with a length only.
            val expected = LinkedHashMap<String, LinkedHashSet<String>>()
            for (item in spine) {
                val overlay = item.getAttribute("media-overlay").takeIf { it.isNotBlank() } ?: continue
                val document = DocumentPath.resolve(packagePath, item.getAttribute("href"))!!.first
                val smilPath = DocumentPath.resolve(packagePath, manifest.getValue(overlay).getAttribute("href"))!!.first
                for (par in elements(dom(zip, smilPath, entries), "par")) {
                    pars++
                    val text = elements(par, "text").firstOrNull()
                    val audio = elements(par, "audio").firstOrNull()
                    if (text == null || audio == null) continue
                    // Every par resolves: its text is a document of the package with an id, its audio a file of it.
                    val (target, fragment) = DocumentPath.resolve(smilPath, text.getAttribute("src")) ?: error("par does not resolve: ${text.getAttribute("src")}")
                    assertTrue("a fragment: ${text.getAttribute("src")}", fragment.isNotBlank())
                    assertTrue("the document is in the zip: $target", target in entries)
                    assertEquals("the document the item names", document, target)
                    val audioPath = DocumentPath.resolve(smilPath, audio.getAttribute("src"))?.first ?: error("audio does not resolve: ${audio.getAttribute("src")}")
                    assertTrue("the audio is in the zip: $audioPath", audioPath in entries)
                    val begin = ReadAlongPackage.clock(audio.getAttribute("clipBegin").ifBlank { "0s" })
                    if (ReadAlongPackage.clock(audio.getAttribute("clipEnd")) <= begin) continue
                    narrated++
                    expected.getOrPut(document) { LinkedHashSet() }.add(fragment)
                }
            }
            assertTrue("a whole book of narration, not a few sentences: $pars pars", pars > 10_000)
            // Every par that has a clip of some length is a sentence of the timeline, and no other is.
            assertEquals("sentences of the timeline", narrated, timeline.tracks.sumOf { it.segments.size })

            // Every spine document, as Readium spells its href (percent-encoded, with a fragment when a link has one).
            for (item in spine) {
                val document = DocumentPath.resolve(packagePath, item.getAttribute("href"))!!.first
                val readium = DocumentPath.encode(document)
                val seen = timeline.fragments(DocumentPath.of(readium))
                val want = expected[document].orEmpty()
                assertEquals("the sentences of $readium", want.toList(), seen)
                assertEquals(want.isNotEmpty(), timeline.narrates(DocumentPath.of("$readium#${want.firstOrNull() ?: "x"}")))
                // The old comparison, the page's encoded name against the narration's, found nothing in a name with a space.
                if (want.isNotEmpty() && readium != document) assertFalse(readium, timeline.narrates(readium))
            }
            assertTrue("the documents are the book's: ${expected.size}", expected.size > 20)
            println("${file.name}: $pars pars, $narrated sentences in ${expected.size} documents, ${timeline.tracks.size} tracks")
            // Spaces and brackets are what these names have.
            assertTrue(expected.keys.count { '[' in it && ' ' in it } > 20)
        }
    }
}

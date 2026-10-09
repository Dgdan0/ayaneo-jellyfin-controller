package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Every real wordsync pack's edition as the Pocket gets it (#66), read from disk: the hub's sweep
 * (`hub/internal/reading/readalong_sweep_test.go`, POCKETDS_READALONG_SWEEP_OUT) writes each book's word copy and sentence
 * copy, and this reads them as the reader does. Set POCKETDS_WORD_EDITIONS to that folder; nothing is fetched from a hub.
 *
 * For each book: every `<par>` resolves through [DocumentPath] to a document of the archive, each word's sentence is the
 * `<seq>` round it (its id is the sentence's and `-wN`), every word and sentence id is an element of its document, the word
 * edition narrates the same sentences as the sentence edition, and a place saved by the sentence is found in both.
 */
class ReadAlongRealPacksTest {
    private val id = Regex("""\sid="([^"]+)"""")

    @Test fun everyRealPackReadsAsTheReaderReadsIt() {
        val folder = System.getenv("POCKETDS_WORD_EDITIONS")?.let(::File)
        assumeTrue("Set POCKETDS_WORD_EDITIONS to the hub sweep's output", folder?.isDirectory == true)
        val books = folder!!.listFiles { file -> file.name.endsWith(".word.epub") }.orEmpty().sortedBy { it.name }
        assumeTrue("No word editions in $folder", books.isNotEmpty())
        val report = StringBuilder()
        for (book in books) {
            val title = book.name.removeSuffix(".word.epub")
            val started = System.nanoTime()
            val words = ReadAlongPackage.read(book, requireAudio = false)
            val parseMs = (System.nanoTime() - started) / 1_000_000
            val sentences = ReadAlongPackage.read(File(folder, "$title.sentence.epub"), requireAudio = false)
            val segments = words.tracks.flatMap { it.segments }
            ZipFile(book).use { zip ->
                val documents = zip.entries().asSequence().associateBy { DocumentPath.name(it.name) }
                val ids = HashMap<String, Set<String>>()
                fun idsOf(href: String) = ids.getOrPut(href) {
                    val entry = documents[href] ?: error("$title: $href is not in the archive")
                    zip.getInputStream(entry).bufferedReader().use { reader -> id.findAll(reader.readText()).map { it.groupValues[1] }.toSet() }
                }
                var wordCount = 0
                for (segment in segments) {
                    assertTrue("$title: ${segment.textHref} resolves", segment.textHref in documents)
                    assertTrue("$title: ${segment.audioHref} is an audio piece", Regex(""".*/?\d{5}-\d{5}\.\w+""").matches(segment.audioHref))
                    val known = idsOf(segment.textHref)
                    assertTrue("$title: ${segment.fragment} is an element of ${segment.textHref}", segment.fragment in known)
                    assertTrue("$title: ${segment.sentenceFragment} is an element of ${segment.textHref}", segment.sentenceFragment in known)
                    if (segment.isWord) {
                        wordCount++
                        assertTrue("$title: ${segment.fragment} is a word of ${segment.sentenceFragment}",
                            segment.fragment.startsWith(segment.sentenceFragment + "-w") && segment.fragment.substringAfterLast("-w").all(Char::isDigit))
                    }
                    assertTrue("$title: ${segment.fragment} runs forward", segment.endMs > segment.beginMs)
                }
                // Every stretch runs forward, so one media item a stretch plays it straight through.
                words.tracks.forEach { track ->
                    assertTrue("$title: a stretch runs forward", track.segments.zipWithNext().all { (a, b) -> b.beginMs >= a.beginMs })
                }
                // The sentences of the sentence edition, document by document, in the same order; a sentence the pack wrote no
                // timed word for (an empty <seq>) is counted, not refused: the reader shows nothing while it is said.
                val hrefs = segments.map { it.textHref }.distinct()
                // A sentence the word set times and the sentence set leaves unspoken (A Parade of Horribles has some) is counted
                // too: the place saved there is still a sentence of the book.
                var wordless = 0
                var wordsOnly = 0
                hrefs.forEach { href ->
                    val theirs = sentences.fragments(href)
                    val ours = words.fragments(href)
                    val both = theirs.toSet().intersect(ours.toSet())
                    assertEquals("$title: the sentences of $href, in order", theirs.filter { it in both }, ours.filter { it in both })
                    wordless += theirs.count { it !in both }
                    wordsOnly += ours.count { it !in both }
                }
                assertTrue("$title: $wordless sentences with no timed word, $wordsOnly with words and no sentence clip", (wordless + wordsOnly) * 200 < segments.size)
                // A place saved by the sentence is found in both, and resumes at the start of that sentence's first word.
                var found = 0
                hrefs.forEach { href ->
                    words.fragments(href).forEach { sentence ->
                        assertNotNull("$title: $href#$sentence", words.find(href, sentence))
                        if (sentence in sentences.fragments(href)) assertNotNull("$title: $href#$sentence (sentence edition)", sentences.find(href, sentence))
                        found++
                    }
                }
                val wordTracks = words.tracks.size
                val line = "$title: ${segments.size} segments ($wordCount words), $found sentences ($wordless with no timed word, $wordsOnly timed by word only), " +
                    "${words.tracks.size} stretches (the sentence edition ${sentences.tracks.size}), parsed in $parseMs ms"
                report.appendLine(line)
                println(line)
                assertTrue("$title is a word edition", words.wordLevel && wordCount > found)
                assertTrue("$title: stretches", wordTracks >= 1)
            }
        }
        println(report)
    }
}

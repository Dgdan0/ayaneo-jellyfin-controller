package com.pocketds.hub.reader

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookArchiveTest {
    @Test fun `audio parts use numeric order and do not trust zip paths`() {
        val root = createTempDir(prefix = "audiobook-test-")
        try {
            val archive = File(root, "book.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf("disc/part10.mp3", "../part2.mp3", "disc/part1.mp3", "cover.jpg").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(byteArrayOf(1, 2, 3))
                    zip.closeEntry()
                }
            }
            val parts = AudiobookArchive.extract(archive, File(root, "audio"))
            assertEquals(listOf("part1.mp3", "part10.mp3"), parts.map { it.title })
            assertTrue(parts.all { it.file!!.canonicalPath.startsWith(File(root, "audio").canonicalPath) })
            assertTrue("Taken from the ZIP, not streamed", parts.none { it.streamed })
            // The sizes in the order the parts were played, from the directory alone (#19).
            assertEquals(listOf(3L, 3L), AudiobookArchive.partSizes(archive))
        } finally { root.deleteRecursively() }
    }

    @Test fun `the parts' sizes follow the order they were played in`() {
        val root = createTempDir(prefix = "audiobook-sizes-")
        try {
            val archive = File(root, "book.zip")
            // Dark Matter's names (#19): the unsuffixed file sorts last here.
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf("Dark Matter.mp3" to 8, "Dark Matter (1).mp3" to 1, "Dark Matter (2).mp3" to 2).forEach { (name, size) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(ByteArray(size)); zip.closeEntry()
                }
            }
            assertEquals(listOf(1L, 2L, 8L), AudiobookArchive.partSizes(archive))
            assertEquals(emptyList<Long>(), AudiobookArchive.partSizes(File(root, "missing.zip")))
        } finally { root.deleteRecursively() }
    }

    @Test fun `a part is shown without its audio extension`() {
        assertEquals("01 Opening", AudiobookArchive.partLabel("01 Opening.wav"))
        assertEquals("Chapter 1. Intro", AudiobookArchive.partLabel("Chapter 1. Intro.MP3"))
        // Only an audio extension goes: a name with a dot of its own keeps it.
        assertEquals("Vol. 2", AudiobookArchive.partLabel("Vol. 2"))
        assertEquals(".mp3", AudiobookArchive.partLabel(".mp3"))
    }

    @Test fun `empty or non audio archives cannot open as a book`() {
        val root = createTempDir(prefix = "audiobook-empty-")
        try {
            val archive = File(root, "empty.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("cover.jpg")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
            assertEquals(false, AudiobookArchive.hasPlayableAudio(archive))
        } finally { root.deleteRecursively() }
    }
}

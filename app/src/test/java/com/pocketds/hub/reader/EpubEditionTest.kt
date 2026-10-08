package com.pocketds.hub.reader

import com.pocketds.hub.net.EpubRevalidation
import com.pocketds.hub.net.EpubTransferHttpException
import com.pocketds.hub.net.FailureKind
import com.pocketds.hub.net.HubFailures
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.net.ReadingEpubDownload
import com.pocketds.hub.net.ResumableEpubTransfer
import com.pocketds.hub.reader.EpubEdition.How
import com.pocketds.hub.reader.EpubEdition.Opened
import com.pocketds.hub.reader.EpubEdition.Stage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A book kept on the device is asked about when it opens (#41), against a local server
 * standing in for the hub: the real cache, the real transfer, and the rules that join them.
 */
class EpubEditionTest {
    private val work = "rw_book"
    private val source = "12"

    private fun epub(label: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container>$label</container>".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    private class Rig(val server: MockWebServer, val cache: EpubPackageCache, val root: File) {
        val stages = mutableListOf<Stage>()
        val seen = mutableListOf<RecordedRequest>()
    }

    /** What `HubClient.downloadReadingEpub` does with the transfer, against the local server. */
    private fun fetcher(rig: Rig, attempts: Int = 2): suspend (File, EpubRevalidation?) -> HubResult<ReadingEpubDownload> = { destination, check ->
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
        val request = Request.Builder().url(rig.server.url("/book.epub")).build()
        try {
            HubResult.Ok(ResumableEpubTransfer.downloadWithRetry(client, request, destination, attempts = attempts, retryDelayMs = 0, revalidation = check))
        } catch (e: CancellationException) {
            throw e
        } catch (e: EpubTransferHttpException) {
            HubResult.Failed(HubFailures.classify(null, e.status))
        } catch (e: Exception) {
            HubResult.Failed(HubFailures.classify(e.javaClass.name, null))
        }
    }

    private fun withRig(block: suspend (Rig) -> Unit) = runBlocking {
        val server = MockWebServer().also { it.start() }
        val root = createTempDir(prefix = "epub-edition-")
        val rig = Rig(server, EpubPackageCache(File(root, "reading-epub")), root)
        try { block(rig) } finally { runCatching { server.shutdown() }; root.deleteRecursively() }
    }

    /** A copy the way a download leaves it: promoted, with the tag the hub sent. */
    private fun Rig.keep(bytes: ByteArray, tag: String): File {
        cache.temporaryFile(work, source).writeBytes(bytes)
        return cache.promote(work, source, tag)
    }

    private suspend fun Rig.open(
        forceDownload: Boolean = false,
        revalidate: Boolean = true,
        answerWithinMs: Long = EpubRevalidation.ANSWER_MS,
        attempts: Int = 2
    ): Opened = EpubEdition(cache, work, source, answerWithinMs)
        .open(forceDownload, revalidate, fetcher(this, attempts)) { stages += it }

    private fun Rig.ready(opened: Opened): Opened.Ready = opened as Opened.Ready

    @Test fun `a book that is not kept is downloaded with its tag`() = withRig { rig ->
        val bytes = epub("edition-a")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-a\"").setBody(Buffer().write(bytes)))

        val opened = rig.ready(rig.open())

        assertEquals(How.DOWNLOADED, opened.how)
        assertEquals(listOf(Stage.DOWNLOADING), rig.stages)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertEquals(CopyState.Tagged("\"tag-a\""), rig.cache.copyState(work, source))
        assertNull(rig.server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun `a kept book the hub says is current opens untouched`() = withRig { rig ->
        val bytes = epub("edition-a")
        val file = rig.keep(bytes, "\"tag-a\"")
        file.setLastModified(1_000_000_000_000L)
        rig.server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"tag-a\""))

        val opened = rig.ready(rig.open())

        assertEquals(How.CONFIRMED, opened.how)
        assertEquals(emptyList<Stage>(), rig.stages)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertEquals(1_000_000_000_000L, opened.file.lastModified())
        assertEquals("\"tag-a\"", rig.server.takeRequest().getHeader("If-None-Match"))
        assertEquals(1, rig.server.requestCount)
        assertFalse(rig.cache.temporaryFile(work, source).exists())
    }

    @Test fun `a kept book is replaced when the hub has another edition`() = withRig { rig ->
        rig.keep(epub("edition-a"), "\"tag-a\"")
        val fresh = epub("edition-b")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-b\"").setBody(Buffer().write(fresh)))

        val opened = rig.ready(rig.open())

        assertEquals(How.UPDATED, opened.how)
        assertEquals(listOf(Stage.UPDATING), rig.stages)
        assertArrayEquals(fresh, opened.file.readBytes())
        assertEquals(CopyState.Tagged("\"tag-b\""), rig.cache.copyState(work, source))
        assertEquals("\"tag-a\"", rig.server.takeRequest().getHeader("If-None-Match"))

        // And the next opening asks with the new tag.
        rig.server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(How.CONFIRMED, rig.ready(rig.open()).how)
        assertEquals("\"tag-b\"", rig.server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun `with no network the kept book opens as it always did`() = withRig { rig ->
        val bytes = epub("edition-a")
        rig.keep(bytes, "\"tag-a\"")
        rig.server.shutdown()

        val opened = rig.ready(rig.open())

        assertEquals(How.KEPT, opened.how)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertEquals(CopyState.Tagged("\"tag-a\""), rig.cache.copyState(work, source))
        assertEquals(emptyList<Stage>(), rig.stages)
    }

    @Test fun `a hub that is slow to answer does not hold the book`() = withRig { rig ->
        val bytes = epub("edition-a")
        rig.keep(bytes, "\"tag-a\"")
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()

        val opened = rig.ready(rig.open(answerWithinMs = 300))

        assertEquals(How.KEPT, opened.how)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000}ms", System.nanoTime() - started < 3_000_000_000L)
        assertEquals(1, rig.server.requestCount)
    }

    @Test fun `a hub error opens the kept book and is not retried`() = withRig { rig ->
        val bytes = epub("edition-a")
        rig.keep(bytes, "\"tag-a\"")
        for (status in listOf(401, 404, 429, 500, 503)) {
            rig.server.enqueue(MockResponse().setResponseCode(status))
            val opened = rig.ready(rig.open(attempts = 3))
            assertEquals("status $status", How.KEPT, opened.how)
            assertArrayEquals(bytes, opened.file.readBytes())
        }
        assertEquals("one question each", 5, rig.server.requestCount)
        assertEquals(CopyState.Tagged("\"tag-a\""), rig.cache.copyState(work, source))
    }

    @Test fun `a copy from before the tag was kept is fetched once`() = withRig { rig ->
        rig.keep(epub("old-sizes"), "")
        rig.cache.etagFile(work, source).delete()
        val fresh = epub("reading-copy")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-b\"").setBody(Buffer().write(fresh)))

        val opened = rig.ready(rig.open())

        assertEquals(How.UPDATED, opened.how)
        assertEquals(listOf(Stage.UPDATING), rig.stages)
        assertArrayEquals(fresh, opened.file.readBytes())
        assertNull("no tag to match yet", rig.server.takeRequest().getHeader("If-None-Match"))

        rig.server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(How.CONFIRMED, rig.ready(rig.open()).how)
        assertEquals(2, rig.server.requestCount)
    }

    @Test fun `a copy from before is kept against a hub that tags nothing, and remembered as asked`() = withRig { rig ->
        val bytes = epub("storyteller")
        rig.keep(bytes, "")
        rig.cache.etagFile(work, source).delete()
        rig.server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epub("storyteller"))))

        val opened = rig.ready(rig.open())

        assertEquals(How.CONFIRMED, opened.how)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertEquals(CopyState.Unverifiable, rig.cache.copyState(work, source))

        // The hub tags its edition from some day on, and the book is fetched then, once.
        val fresh = epub("reading-copy")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-b\"").setBody(Buffer().write(fresh)))
        val later = rig.ready(rig.open())
        assertEquals(How.UPDATED, later.how)
        assertArrayEquals(fresh, later.file.readBytes())
    }

    @Test fun `a hub that ignores the condition does not cost the book`() = withRig { rig ->
        val bytes = epub("edition-a")
        rig.keep(bytes, "\"tag-a\"")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-a\"").setBody(Buffer().write(epub("edition-a"))))

        val opened = rig.ready(rig.open())

        assertEquals(How.CONFIRMED, opened.how)
        assertEquals(emptyList<Stage>(), rig.stages)
        assertArrayEquals(bytes, opened.file.readBytes())
    }

    @Test fun `a newer edition cut off part-way leaves the kept book open and resumes next time`() = withRig { rig ->
        val old = epub("edition-a")
        rig.keep(old, "\"tag-a\"")
        val fresh = epub("edition-b")
        val calls = AtomicInteger()
        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                rig.seen += request
                val range = request.getHeader("Range")
                return if (range == null) {
                    // The first answer is cut off, and so is the retry's if it came.
                    calls.incrementAndGet()
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-b\"")
                        .setBody(Buffer().write(fresh)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    MockResponse().setResponseCode(206).setHeader("ETag", "\"tag-b\"")
                        .setHeader("Content-Range", "bytes $start-${fresh.lastIndex}/${fresh.size}")
                        .setBody(Buffer().write(fresh, start, fresh.size - start))
                }
            }
        }

        // One attempt: the cut is final for this opening.
        val first = rig.ready(rig.open(attempts = 1))

        assertEquals(How.KEPT, first.how)
        assertEquals(listOf(Stage.UPDATING), rig.stages)
        assertArrayEquals(old, first.file.readBytes())
        assertEquals(CopyState.Tagged("\"tag-a\""), rig.cache.copyState(work, source))
        assertTrue(rig.cache.temporaryFile(work, source).length() in 1 until fresh.size.toLong())

        // The next opening asks again and carries on from the bytes already here.
        val second = rig.ready(rig.open())
        assertEquals(How.UPDATED, second.how)
        assertArrayEquals(fresh, second.file.readBytes())
        assertEquals(CopyState.Tagged("\"tag-b\""), rig.cache.copyState(work, source))
        assertEquals("\"tag-a\"", rig.seen[0].getHeader("If-None-Match"))
        assertEquals("\"tag-b\"", rig.seen[1].getHeader("If-Range"))
        assertEquals(1, calls.get())
    }

    @Test fun `the whole read-along edition is opened as it is, without a question`() = withRig { rig ->
        val bytes = epub("with-audio")
        rig.keep(bytes, "\"tag-a\"")

        val opened = rig.ready(rig.open(revalidate = false))

        assertEquals(How.KEPT, opened.how)
        assertArrayEquals(bytes, opened.file.readBytes())
        assertEquals(0, rig.server.requestCount)
    }

    @Test fun `downloading again drops the copy and its tag first`() = withRig { rig ->
        rig.keep(epub("edition-a"), "\"tag-a\"")
        val fresh = epub("edition-b")
        rig.server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-b\"").setBody(Buffer().write(fresh)))

        val opened = rig.ready(rig.open(forceDownload = true))

        assertEquals(How.DOWNLOADED, opened.how)
        assertArrayEquals(fresh, opened.file.readBytes())
        assertNull("nothing kept, nothing to match", rig.server.takeRequest().getHeader("If-None-Match"))
        assertEquals(CopyState.Tagged("\"tag-b\""), rig.cache.copyState(work, source))
    }

    @Test fun `a book that is not kept and cannot be fetched is a failure`() = withRig { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(409))

        val opened = rig.open()

        assertEquals(FailureKind.BAD_RESPONSE, (opened as Opened.Failed).failure.kind)
        assertEquals(CopyState.Missing, rig.cache.copyState(work, source))
    }

    @Test fun `a fresh download that is cut off resumes by range, as before`() = withRig { rig ->
        val fresh = epub("edition-a")
        rig.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                rig.seen += request
                val range = request.getHeader("Range") ?: return MockResponse().setResponseCode(200).setHeader("ETag", "\"tag-a\"")
                    .setBody(Buffer().write(fresh)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                return MockResponse().setResponseCode(206).setHeader("ETag", "\"tag-a\"")
                    .setHeader("Content-Range", "bytes $start-${fresh.lastIndex}/${fresh.size}")
                    .setBody(Buffer().write(fresh, start, fresh.size - start))
            }
        }

        val opened = rig.ready(rig.open(attempts = 3))

        assertEquals(How.DOWNLOADED, opened.how)
        assertArrayEquals(fresh, opened.file.readBytes())
        assertEquals(2, rig.seen.size)
        assertEquals("\"tag-a\"", rig.seen[1].getHeader("If-Range"))
        assertEquals(CopyState.Tagged("\"tag-a\""), rig.cache.copyState(work, source))
    }
}

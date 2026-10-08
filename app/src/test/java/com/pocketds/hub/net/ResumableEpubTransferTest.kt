package com.pocketds.hub.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import com.pocketds.hub.reader.CopyState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ResumableEpubTransferTest {
    private fun epub(label: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container>$label</container>".toByteArray()); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun audiobookZipAcceptsAudioWithoutAnEpubManifest() = runBlocking {
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("01.mp3")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
            }
        }.toByteArray()
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(bytes)))
        server.start()
        val root = createTempDir(prefix = "audio-archive-")
        try {
            val destination = File(root, "book.part")
            val result = ResumableEpubTransfer.downloadWithRetry(
                OkHttpClient(), Request.Builder().url(server.url("/book.zip")).build(),
                destination, attempts = 1, requireEpubManifest = false
            )
            assertEquals(bytes.size.toLong(), result.bytes)
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun interruptedStreamKeepsBytesAndResumesWithValidatedRange() = runBlocking {
        val bytes = epub("same-edition")
        val calls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (calls.getAndIncrement() == 0) {
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-a\"")
                        .setBody(Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    val start = request.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
                        ?: error("Missing resume range")
                    assertEquals("\"edition-a\"", request.getHeader("If-Range"))
                    MockResponse().setResponseCode(206).setHeader("ETag", "\"edition-a\"")
                        .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                        .setBody(Buffer().write(bytes, start, bytes.size - start))
                }
            }
        }
        server.start()
        val root = createTempDir(prefix = "epub-resume-")
        try {
            val part = File(root, "book.part")
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val request = Request.Builder().url(server.url("/aligned.epub")).build()
            assertTrue(runCatching { ResumableEpubTransfer.download(client, request, part) }.isFailure)
            assertTrue(part.length() in 1 until bytes.size.toLong())
            val downloaded = ResumableEpubTransfer.download(client, request, part)
            assertEquals(bytes.size.toLong(), downloaded.bytes)
            assertArrayEquals(bytes, part.readBytes())
            assertEquals(2, calls.get())
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    @Test fun changedEditionReplacesPartialWhenIfRangeReturnsFullBody() = runBlocking {
        val old = epub("old-edition")
        val fresh = epub("new-edition")
        val calls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return if (calls.getAndIncrement() == 0) {
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"old\"")
                        .setBody(Buffer().write(old)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                } else {
                    assertNotNull(request.getHeader("Range"))
                    assertEquals("\"old\"", request.getHeader("If-Range"))
                    MockResponse().setResponseCode(200).setHeader("ETag", "\"new\"")
                        .setBody(Buffer().write(fresh))
                }
            }
        }
        server.start()
        val root = createTempDir(prefix = "epub-replace-")
        try {
            val part = File(root, "book.part")
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val request = Request.Builder().url(server.url("/aligned.epub")).build()
            assertTrue(runCatching { ResumableEpubTransfer.download(client, request, part) }.isFailure)
            ResumableEpubTransfer.download(client, request, part)
            assertArrayEquals(fresh, part.readBytes())
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    // A book already kept here is asked about with the tag kept beside it (#41).

    private fun withBook(block: suspend (server: MockWebServer, part: File, client: OkHttpClient, request: Request) -> Unit) = runBlocking {
        val server = MockWebServer().also { it.start() }
        val root = createTempDir(prefix = "epub-revalidate-")
        try {
            block(
                server, File(root, "book.part"),
                OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                Request.Builder().url(server.url("/book.epub")).build()
            )
        } finally { server.shutdown(); root.deleteRecursively() }
    }

    private val kept = CopyState.Tagged("\"edition-a\"")

    @Test fun unchangedBookAnswers304AndWritesNothing() = withBook { server, part, client, request ->
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"edition-a\""))

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part, revalidation = EpubRevalidation(kept))

        assertTrue(result.keptCopy)
        assertEquals(0L, result.bytes)
        assertFalse(part.exists())
        val sent = server.takeRequest()
        assertEquals("\"edition-a\"", sent.getHeader("If-None-Match"))
        assertNull(sent.getHeader("Range"))
        assertEquals(1, server.requestCount)
    }

    @Test fun anotherEditionIsStreamedAndAnnouncedAsItBegins() = withBook { server, part, client, request ->
        val fresh = epub("edition-b")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-b\"")
            .setHeader("X-Reading-Content-Hash", "sha256:b").setBody(Buffer().write(fresh)))
        var announced = 0

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part,
            revalidation = EpubRevalidation(kept, onReplace = { announced++ }))

        assertFalse(result.keptCopy)
        assertEquals(1, announced)
        assertEquals("\"edition-b\"", result.etag)
        assertEquals("sha256:b", result.contentHash)
        assertArrayEquals(fresh, part.readBytes())
        assertEquals("\"edition-a\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun serverThatIgnoresTheConditionDoesNotCostTheBook() = withBook { server, part, client, request ->
        // The hub's pass-through of Storyteller's file ignores If-None-Match and answers 200 with the tag it
        // always had: the body is left unread and the copy stands.
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-a\"").setBody(Buffer().write(epub("edition-a"))))
        var announced = 0

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part,
            revalidation = EpubRevalidation(kept, onReplace = { announced++ }))

        assertTrue(result.keptCopy)
        assertEquals(0, announced)
        assertFalse(part.exists())
        assertEquals(1, server.requestCount)
    }

    @Test fun hubThatTagsNothingLeavesTheCopyBe() = withBook { server, part, client, request ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(epub("edition-a"))))

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part,
            revalidation = EpubRevalidation(CopyState.Unrecorded))

        assertTrue(result.keptCopy)
        assertEquals("", result.etag)
        assertFalse(part.exists())
    }

    @Test fun copyWithNoTagKeptIsFetchedOnceTheHubTagsIt() = withBook { server, part, client, request ->
        val fresh = epub("edition-b")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-b\"").setBody(Buffer().write(fresh)))

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part,
            revalidation = EpubRevalidation(CopyState.Unrecorded))

        assertFalse(result.keptCopy)
        assertArrayEquals(fresh, part.readBytes())
        // There is no tag to match, so no condition goes out.
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun aDownloadWithNoQuestionSendsNoCondition() = withBook { server, part, client, request ->
        val fresh = epub("edition-b")
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-b\"").setBody(Buffer().write(fresh)))

        ResumableEpubTransfer.downloadWithRetry(client, request, part)

        assertNull(server.takeRequest().getHeader("If-None-Match"))
        assertArrayEquals(fresh, part.readBytes())
    }

    @Test fun hubThatDoesNotBeginAnsweringIsNotWaitedFor() = withBook { server, part, client, request ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val started = System.nanoTime()

        val failure = runCatching {
            ResumableEpubTransfer.downloadWithRetry(client, request, part,
                attempts = 3, retryDelayMs = 0, revalidation = EpubRevalidation(kept, headerTimeoutMs = 300))
        }.exceptionOrNull()

        assertTrue("$failure", failure is java.net.SocketTimeoutException)
        assertTrue("took ${(System.nanoTime() - started) / 1_000_000}ms", (System.nanoTime() - started) < 3_000_000_000L)
        assertEquals("the question is asked once", 1, server.requestCount)
    }

    @Test fun hubThatFailsTheQuestionIsNotAskedAgain() = withBook { server, part, client, request ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))

        val failure = runCatching {
            ResumableEpubTransfer.downloadWithRetry(client, request, part, attempts = 3, retryDelayMs = 0, revalidation = EpubRevalidation(kept))
        }.exceptionOrNull()

        assertEquals(503, (failure as EpubTransferHttpException).status)
        assertEquals(1, server.requestCount)
    }

    @Test fun aDownloadWithNoQuestionStillRetriesAServerError() = withBook { server, part, client, request ->
        val bytes = epub("edition-b")
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-b\"").setBody(Buffer().write(bytes)))

        ResumableEpubTransfer.downloadWithRetry(client, request, part, attempts = 3, retryDelayMs = 0)

        assertEquals(2, server.requestCount)
        assertArrayEquals(bytes, part.readBytes())
    }

    @Test fun anotherEditionCutOffPartWayResumesWithoutTheQuestion() = withBook { server, part, client, request ->
        val fresh = epub("edition-b")
        // The second answer depends on the Range sent, so a dispatcher computes it.
        val calls = AtomicInteger()
        val seen = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                if (calls.getAndIncrement() == 0) {
                    return MockResponse().setResponseCode(200).setHeader("ETag", "\"edition-b\"")
                        .setBody(Buffer().write(fresh)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                }
                val start = request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toInt()
                return MockResponse().setResponseCode(206).setHeader("ETag", "\"edition-b\"")
                    .setHeader("Content-Range", "bytes $start-${fresh.lastIndex}/${fresh.size}")
                    .setBody(Buffer().write(fresh, start, fresh.size - start))
            }
        }
        var announced = 0

        val result = ResumableEpubTransfer.downloadWithRetry(client, request, part, attempts = 3, retryDelayMs = 0,
            revalidation = EpubRevalidation(kept, onReplace = { announced++ }))

        assertFalse(result.keptCopy)
        assertEquals(1, announced)
        assertArrayEquals(fresh, part.readBytes())
        assertEquals(2, calls.get())
        assertEquals("\"edition-a\"", seen[0].getHeader("If-None-Match"))
        assertNull("a resumed edition is not asked about again", seen[1].getHeader("If-None-Match"))
        assertEquals("\"edition-b\"", seen[1].getHeader("If-Range"))
    }

    @Test fun transientDisconnectAutomaticallyResumesWithinRetryLimit() = runBlocking {
        val bytes = epub("automatic-retry")
        val calls = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (calls.getAndIncrement() == 0) {
                MockResponse().setResponseCode(200).setHeader("ETag", "\"same\"")
                    .setBody(Buffer().write(bytes)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                val start = request.getHeader("Range")!!.removePrefix("bytes=").substringBefore('-').toInt()
                MockResponse().setResponseCode(206).setHeader("ETag", "\"same\"")
                    .setHeader("Content-Range", "bytes $start-${bytes.lastIndex}/${bytes.size}")
                    .setBody(Buffer().write(bytes, start, bytes.size - start))
            }
        }
        server.start()
        val root = createTempDir(prefix = "epub-auto-retry-")
        try {
            val part = File(root, "book.part")
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).build()
            val result = ResumableEpubTransfer.downloadWithRetry(
                client, Request.Builder().url(server.url("/aligned.epub")).build(), part,
                attempts = 3, retryDelayMs = 0
            )
            assertEquals(bytes.size.toLong(), result.bytes)
            assertArrayEquals(bytes, part.readBytes())
            assertEquals(2, calls.get())
        } finally { server.shutdown(); root.deleteRecursively() }
    }
}

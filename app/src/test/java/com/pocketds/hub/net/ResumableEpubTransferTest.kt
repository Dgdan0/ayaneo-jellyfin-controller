package com.pocketds.hub.net

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

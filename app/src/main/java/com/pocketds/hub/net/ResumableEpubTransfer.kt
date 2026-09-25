package com.pocketds.hub.net

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.coroutineContext

class EpubTransferHttpException(val status: Int, val responseText: String) : IOException("EPUB HTTP $status")

/** Retains verified bytes across a lost remote connection; partial ZIPs are never opened. */
object ResumableEpubTransfer {
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    suspend fun downloadWithRetry(
        client: OkHttpClient,
        request: Request,
        destination: File,
        attempts: Int = 3,
        retryDelayMs: Long = 1_000,
        requireEpubManifest: Boolean = true
    ): ReadingEpubDownload {
        require(attempts in 1..5)
        repeat(attempts) { index ->
            try {
                return download(client, request, destination, requireEpubManifest)
            } catch (e: CancellationException) {
                throw e
            } catch (e: EpubTransferHttpException) {
                if (index == attempts - 1 || (e.status != 429 && e.status < 500)) throw e
            } catch (e: IOException) {
                if (index == attempts - 1) throw e
            }
            delay(retryDelayMs * (index + 1))
        }
        error("unreachable")
    }

    private data class Saved(val url: String, val etag: String, val total: Long)

    fun metadataFile(destination: File): File = File(destination.path + ".meta")

    suspend fun download(client: OkHttpClient, request: Request, destination: File, requireEpubManifest: Boolean = true): ReadingEpubDownload =
        withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val metadata = metadataFile(destination)
            var saved = load(metadata)?.takeIf {
                it.url == request.url.toString() && it.etag.startsWith('"') && it.total > 0
            }
            if (saved == null || !destination.isFile || destination.length() > saved.total) {
                destination.delete()
                metadata.delete()
                saved = null
            }
            if (saved != null && destination.length() == saved.total) {
                if (validZip(destination, requireEpubManifest)) return@withContext ReadingEpubDownload(destination.length(), saved.etag, "")
                destination.delete(); metadata.delete(); saved = null
            }
            val offset = if (saved != null) destination.length() else 0L
            val ranged = request.newBuilder().apply {
                if (offset > 0) {
                    header("Range", "bytes=$offset-")
                    header("If-Range", saved!!.etag)
                }
            }.build()
            client.newCall(ranged).await().use { response ->
                if (!response.isSuccessful) {
                    throw EpubTransferHttpException(response.code, response.body?.string().orEmpty())
                }
                val body = response.body ?: throw IOException("Empty EPUB response")
                val append: Boolean
                val expected: Long
                val current: Saved?
                when (response.code) {
                    200 -> {
                        append = false
                        expected = body.contentLength()
                        if (expected <= 0) throw IOException("Unknown EPUB size")
                        current = response.header("ETag")?.takeIf { it.startsWith('"') }?.let {
                            Saved(request.url.toString(), it, expected)
                        }
                        if (current == null) metadata.delete() else save(metadata, current)
                    }
                    206 -> {
                        if (offset <= 0 || saved == null) throw IOException("Unexpected EPUB range")
                        val match = range.matchEntire(response.header("Content-Range").orEmpty())
                            ?: throw IOException("Invalid EPUB range")
                        val start = match.groupValues[1].toLongOrNull()
                        val end = match.groupValues[2].toLongOrNull()
                        val total = match.groupValues[3].toLongOrNull()
                        if (start != offset || total != saved.total || end == null || end < offset || end >= total ||
                            response.header("ETag") != saved.etag || body.contentLength() != end - start + 1
                        ) throw IOException("EPUB edition changed or range did not match")
                        append = true
                        expected = saved.total
                        current = saved
                    }
                    else -> throw IOException("Unexpected EPUB status ${response.code}")
                }
                FileOutputStream(destination, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                    output.fd.sync()
                }
                if (destination.length() != expected) throw IOException("Incomplete EPUB transfer")
                if (!validZip(destination, requireEpubManifest)) {
                    destination.delete(); metadata.delete()
                    throw IOException("Invalid EPUB archive")
                }
                ReadingEpubDownload(destination.length(), current?.etag.orEmpty(), response.header("X-Reading-Content-Hash").orEmpty())
            }
        }

    private fun validZip(file: File, requireEpubManifest: Boolean): Boolean = runCatching {
        if (requireEpubManifest) ZipFile(file).use { it.getEntry("META-INF/container.xml") != null }
        else com.pocketds.hub.reader.AudiobookArchive.hasPlayableAudio(file)
    }.getOrDefault(false)

    private fun load(file: File): Saved? = runCatching {
        Properties().apply { file.inputStream().use(::load) }.let {
            Saved(it.getProperty("url"), it.getProperty("etag"), it.getProperty("total").toLong())
        }
    }.getOrNull()

    private fun save(file: File, value: Saved) {
        val temporary = File(file.path + ".new")
        try {
            Properties().apply {
                setProperty("url", value.url)
                setProperty("etag", value.etag)
                setProperty("total", value.total.toString())
            }.let { props -> temporary.outputStream().use { props.store(it, null) } }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}

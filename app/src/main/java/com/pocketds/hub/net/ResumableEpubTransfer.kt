package com.pocketds.hub.net

import com.pocketds.hub.reader.CopyState
import com.pocketds.hub.reader.EpubFreshness
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.coroutineContext

class EpubTransferHttpException(val status: Int, val responseText: String) : IOException("EPUB HTTP $status")

/**
 * A book already kept on this device, fetched only if the hub has another edition (#41).
 *
 * The request goes out with `If-None-Match` set to the ETag kept for the copy, when there
 * is one, and the hub's answer is judged as soon as its headers arrive
 * ([EpubFreshness.decide]): a 304, or a body carrying the tag already kept, ends the
 * transfer there with [ReadingEpubDownload.keptCopy]; anything else is the newer edition
 * and its body is streamed to the destination as any download is.
 */
class EpubRevalidation(
    val copy: CopyState,
    /**
     * How long the hub has to begin answering. The check stands between a person and
     * a book they could already open, so it is short; the body that follows a
     * newer edition is not under it.
     */
    val headerTimeoutMs: Long = ANSWER_MS,
    /** Called when a newer edition begins to arrive, from the transfer's thread. */
    val onReplace: () -> Unit = {}
) {
    companion object {
        const val ANSWER_MS = 5_000L
    }
}

/** Retains verified bytes across a lost remote connection; partial ZIPs are never opened. */
object ResumableEpubTransfer {
    private val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

    suspend fun downloadWithRetry(
        client: OkHttpClient,
        request: Request,
        destination: File,
        attempts: Int = 3,
        retryDelayMs: Long = 1_000,
        requireEpubManifest: Boolean = true,
        revalidation: EpubRevalidation? = null
    ): ReadingEpubDownload {
        require(attempts in 1..5)
        // The question is asked once. A hub that cannot answer it is not asked again (the
        // book opens from the copy kept here), but a newer edition that is cut off part-way
        // is resumed like any download.
        var asking = revalidation != null
        val question = revalidation?.let { original ->
            EpubRevalidation(original.copy, original.headerTimeoutMs) { asking = false; original.onReplace() }
        }
        repeat(attempts) { index ->
            try {
                return download(client, request, destination, requireEpubManifest, question.takeIf { asking })
            } catch (e: CancellationException) {
                throw e
            } catch (e: EpubTransferHttpException) {
                if (asking || index == attempts - 1 || (e.status != 429 && e.status < 500)) throw e
            } catch (e: IOException) {
                if (asking || index == attempts - 1) throw e
            }
            delay(retryDelayMs * (index + 1))
        }
        error("unreachable")
    }

    private data class Saved(val url: String, val etag: String, val total: Long)

    fun metadataFile(destination: File): File = File(destination.path + ".meta")

    suspend fun download(
        client: OkHttpClient,
        request: Request,
        destination: File,
        requireEpubManifest: Boolean = true,
        revalidation: EpubRevalidation? = null
    ): ReadingEpubDownload =
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
                // A resumed newer edition carries the question too: its tag is not the one kept,
                // so the hub answers 206 as it would have without it.
                revalidation?.let { EpubFreshness.condition(it.copy) }?.let { header("If-None-Match", it) }
            }.build()
            val call = client.newCall(ranged)
            (if (revalidation == null) call.await() else call.awaitWithin(revalidation.headerTimeoutMs)).use { response ->
                if (revalidation != null && (response.code == 200 || response.code == 206 || response.code == 304)) {
                    val tag = EpubFreshness.strong(response.header("ETag"))
                    when (EpubFreshness.decide(revalidation.copy, EpubFreshness.Answer.Replied(response.code, tag))) {
                        // Closing the response unread ends the transfer here, whatever body began.
                        EpubFreshness.Action.OPEN_CACHED ->
                            return@use ReadingEpubDownload(0, tag, response.header("X-Reading-Content-Hash").orEmpty(), keptCopy = true)
                        EpubFreshness.Action.REPLACE -> revalidation.onReplace()
                        EpubFreshness.Action.DOWNLOAD -> Unit
                    }
                }
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

    /** The headers, within [millis]; the body that follows is not timed. */
    private suspend fun Call.awaitWithin(millis: Long): Response = try {
        withTimeout(millis) { await() }
    } catch (e: TimeoutCancellationException) {
        // Our timeout only: a cancelled caller carries on cancelling.
        coroutineContext.ensureActive()
        throw SocketTimeoutException("The hub did not answer in ${millis}ms")
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

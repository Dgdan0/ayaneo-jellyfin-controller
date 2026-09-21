package com.pocketds.hub.reader

import android.content.Context
import com.pocketds.hub.net.HubClient
import com.pocketds.hub.net.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Authenticated, bounded disk cache feeding the tiled image decoder. */
class ReaderPageRepository(context: Context, private val client: HubClient) {
    private val directory = File(context.cacheDir, "reader-pages").apply { mkdirs() }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun obtain(url: String): File {
        val name = ReaderPageCachePolicy.fileName(url)
        val target = File(directory, name)
        return locks.getOrPut(name) { Mutex() }.withLock {
            if (target.isFile && target.length() > 0) {
                target.setLastModified(System.currentTimeMillis())
                return@withLock target
            }
            download(url, target)
            prune(target.name)
            target
        }
    }

    private suspend fun download(url: String, target: File) = withContext(Dispatchers.IO) {
        val temporary = File(directory, target.name + ".tmp-" + System.nanoTime())
        try {
            val call = client.readerHttp.newCall(Request.Builder().url(url).build())
            call.await().use { response ->
                if (!response.isSuccessful) throw IOException("reader page HTTP ${response.code}")
                val body = response.body ?: throw IOException("reader page was empty")
                if (body.contentLength() > MAX_PAGE_BYTES) throw IOException("reader page is too large")
                var written = 0L
                temporary.outputStream().buffered().use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            written += count
                            if (written > MAX_PAGE_BYTES) throw IOException("reader page is too large")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (temporary.length() == 0L) throw IOException("reader page was empty")
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(System.currentTimeMillis())
        } finally {
            temporary.delete()
        }
    }

    private fun prune(protectedName: String) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".page") }.orEmpty()
        val entries = files.map { ReaderPageCacheEntry(it.name, it.length(), it.lastModified()) }
        val removals = ReaderPageCachePolicy.evictions(entries, MAX_CACHE_BYTES, protectedName).toSet()
        files.filter { it.name in removals }.forEach(File::delete)
    }

    private companion object {
        const val MAX_PAGE_BYTES = 128L * 1024L * 1024L
        const val MAX_CACHE_BYTES = 1024L * 1024L * 1024L
    }
}

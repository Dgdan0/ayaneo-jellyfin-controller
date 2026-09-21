package com.pocketds.hub.net

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Small bounded disk cache for successful JSON screen responses.
 *
 * The key is represented only by its SHA-256 filename, so the Hub token and
 * selected user used to isolate an entry are never written in clear text.
 * Files live under Android's cache directory and may be removed by the OS at
 * any time; every read therefore treats absence or corruption as a cache miss.
 */
class PersistentResponseCache(
    private val root: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Int = DEFAULT_MAX_ENTRY_BYTES,
    private val now: () -> Long = System::currentTimeMillis
) {
    data class Entry(val body: String, val ageMillis: Long)

    @Synchronized
    fun read(key: String, maxAgeMillis: Long): Entry? {
        if (key.isEmpty() || maxAgeMillis < 0) return null
        val file = fileFor(key)
        if (!file.isFile) return null
        val raw = try {
            file.readText(StandardCharsets.UTF_8)
        } catch (_: Exception) {
            file.delete()
            return null
        }
        val firstBreak = raw.indexOf('\n')
        val secondBreak = if (firstBreak >= 0) raw.indexOf('\n', firstBreak + 1) else -1
        if (firstBreak < 0 || secondBreak < 0 || raw.substring(0, firstBreak) != MAGIC) {
            file.delete()
            return null
        }
        val storedAt = raw.substring(firstBreak + 1, secondBreak).toLongOrNull()
        if (storedAt == null) {
            file.delete()
            return null
        }
        val age = (now() - storedAt).coerceAtLeast(0L)
        if (age > maxAgeMillis) {
            file.delete()
            return null
        }
        file.setLastModified(now())
        return Entry(raw.substring(secondBreak + 1), age)
    }

    @Synchronized
    fun put(key: String, body: String) {
        if (key.isEmpty()) return
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes.isEmpty() || bodyBytes.size > maxEntryBytes) return
        if (!root.exists() && !root.mkdirs()) return
        val target = fileFor(key)
        val temporary = File(root, target.name + ".tmp")
        try {
            temporary.writeText("$MAGIC\n${now()}\n$body", StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target.setLastModified(now())
            prune()
        } catch (_: Exception) {
            temporary.delete()
        }
    }

    @Synchronized
    fun remove(key: String) {
        if (key.isNotEmpty()) fileFor(key).delete()
    }

    private fun prune() {
        val files = root.listFiles { file -> file.isFile && file.name.endsWith(EXTENSION) }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .toMutableList()
        var bytes = files.sumOf(File::length)
        while (files.size > maxEntries || bytes > maxBytes) {
            val oldest = files.removeFirstOrNull() ?: break
            val length = oldest.length()
            if (oldest.delete()) bytes -= length
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(root, digest + EXTENSION)
    }

    private companion object {
        const val MAGIC = "PDS-DISCOVER-1"
        const val EXTENSION = ".cache"
        const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
        const val DEFAULT_MAX_ENTRIES = 1_024
        const val DEFAULT_MAX_ENTRY_BYTES = 4 * 1024 * 1024
    }
}

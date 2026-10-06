package com.pocketds.hub.reader

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ReadingCheckpointKey(val scope: String, val workId: String, val sourceItemId: String, val kind: String) {
    val fileName: String get() = digest(listOf(scope, workId, sourceItemId, kind).joinToString("\u0000"))
    companion object {
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class ReadingLocation(val locator: JsonObject? = null, val pageIndex: Int? = null) {
    init { require((locator != null) xor (pageIndex != null)); require(pageIndex == null || pageIndex >= 0) }
    fun label(): String {
        pageIndex?.let { return "Page ${it + 1}" }
        val locations = locator?.get("locations") as? JsonObject
        val percent = (locations?.get("totalProgression") as? JsonPrimitive)?.doubleOrNull
        val chapter = (locator?.get("title") as? JsonPrimitive)?.contentOrNull
            ?: (locator?.get("href") as? JsonPrimitive)?.contentOrNull.orEmpty()
        return listOfNotNull(chapter.takeIf(String::isNotBlank), percent?.let { com.pocketds.hub.state.Fmt.readingPercentLabel(it) }).joinToString(" · ")
    }
}

@Serializable
data class ReadingCheckpoint(
    val key: ReadingCheckpointKey,
    val local: ReadingLocation? = null,
    val base: ReadingLocation? = null,
    val baseKnown: Boolean = false,
    val remote: ReadingLocation? = null,
    val revision: Long = 0,
    val updatedAt: Long = 0,
    val pending: Boolean = false,
    val conflicted: Boolean = false,
    val savedAlternatives: List<ReadingLocation> = emptyList()
)

sealed interface RemoteReadingPosition {
    data class Available(val location: ReadingLocation?) : RemoteReadingPosition
    data object Unavailable : RemoteReadingPosition
}

data class ReadingResume(val location: ReadingLocation?, val conflict: Boolean = false, val unavailable: Boolean = false)

/** One fsynced, atomically replaced record per publication. The checkpoint is also its durable outbox entry. */
class ReadingCheckpointStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized fun read(key: ReadingCheckpointKey): ReadingCheckpoint? {
        val file = File(root, key.fileName + ".json")
        if (!file.exists()) return null
        // A corrupt checkpoint is an error, never permission to open at the beginning and overwrite it.
        return json.decodeFromString<ReadingCheckpoint>(file.readText()).also { require(it.key == key) }
    }

    @Synchronized fun pending(scope: String): List<ReadingCheckpoint> = root.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { runCatching { json.decodeFromString<ReadingCheckpoint>(it.readText()) }.getOrNull() }
        .filter { it.key.scope == scope && it.pending }
        .sortedBy { it.updatedAt }

    @Synchronized fun save(key: ReadingCheckpointKey, location: ReadingLocation, now: Long): ReadingCheckpoint {
        val previous = read(key) ?: ReadingCheckpoint(key)
        if (same(key, previous.local, location)) return previous
        return write(previous.copy(local = location, revision = previous.revision + 1, updatedAt = now, pending = true))
    }

    /**
     * A place kept before this store knew the book (#19: an audiobook's old
     * device-only place), written once and only when there is no checkpoint.
     * It is kept as made while the server had no place: it goes out as it is
     * when the server still has none, and the person chooses when it has one.
     */
    @Synchronized fun seed(key: ReadingCheckpointKey, location: ReadingLocation, now: Long): Boolean {
        if (read(key) != null) return false
        write(ReadingCheckpoint(key, local = location, base = null, baseKnown = true, revision = 1, updatedAt = now, pending = true))
        return true
    }

    @Synchronized fun reconcile(key: ReadingCheckpointKey, server: RemoteReadingPosition): ReadingResume {
        val previous = read(key)
        if (server is RemoteReadingPosition.Unavailable) {
            return ReadingResume(previous?.local, conflict = previous?.conflicted == true, unavailable = previous?.local == null)
        }
        val remote = (server as RemoteReadingPosition.Available).location
        if (previous == null || !previous.pending) {
            write((previous ?: ReadingCheckpoint(key)).copy(local = remote, base = remote, baseKnown = true,
                remote = remote, pending = false, conflicted = false))
            return ReadingResume(remote)
        }
        if (same(key, previous.local, remote)) {
            acknowledge(key, previous.revision, remote)
            return ReadingResume(remote)
        }
        val conflict = !previous.baseKnown || !same(key, previous.base, remote)
        write(previous.copy(remote = remote, conflicted = conflict))
        return ReadingResume(previous.local, conflict)
    }

    @Synchronized fun acknowledge(key: ReadingCheckpointKey, revision: Long, sent: ReadingLocation?) {
        val latest = read(key) ?: return
        if (revision > latest.revision || (revision < latest.revision && !latest.pending)) return
        write(latest.copy(base = sent, baseKnown = true, remote = sent,
            pending = latest.revision != revision, conflicted = false))
    }

    @Synchronized fun chooseLocal(key: ReadingCheckpointKey, now: Long = System.currentTimeMillis()): ReadingCheckpoint {
        val current = requireNotNull(read(key))
        return write(current.copy(base = current.remote, baseKnown = true, conflicted = false,
            revision = current.revision + 1, updatedAt = now,
            pending = !same(key, current.local, current.remote), savedAlternatives = alternatives(current)))
    }

    @Synchronized fun chooseRemote(key: ReadingCheckpointKey): ReadingCheckpoint {
        val current = requireNotNull(read(key))
        return write(current.copy(local = current.remote, base = current.remote, baseKnown = true,
            revision = current.revision + 1, pending = false, conflicted = false, savedAlternatives = alternatives(current)))
    }

    /**
     * Whether two locations of [key]'s book are the same place. An audiobook's place is its
     * track and moment: this device keeps how far through the book it is beside them (#30),
     * which the hub never reads back, so the whole location would take the hub's own reading
     * of the place just sent for another device's. Every other kind compares whole.
     */
    private fun same(key: ReadingCheckpointKey, one: ReadingLocation?, other: ReadingLocation?): Boolean =
        if (key.kind == AudioPlace.KIND) AudioPlace.samePlace(one, other) else one == other

    private fun alternatives(value: ReadingCheckpoint) =
        (value.savedAlternatives + listOfNotNull(value.local, value.remote)).distinct().takeLast(20)

    private fun write(value: ReadingCheckpoint): ReadingCheckpoint {
        check(root.isDirectory || root.mkdirs()) { "Reading checkpoint storage is unavailable" }
        val target = File(root, value.key.fileName + ".json")
        val temporary = File(root, value.key.fileName + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(value).toByteArray(Charsets.UTF_8)); output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return value
    }
}

package com.pocketds.hub.reader

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One fsynced, atomically replaced file per profile and book (#62): the book's highlights as this device knows them, with the
 * outbox in it, so an edit made offline survives the app being closed and goes out when the hub next answers. [scope] is
 * [ReadingProgress.Session.identity]: another profile, hub or token has its own files and never sees these.
 *
 * A file that cannot be read is an error and never permission to start a new one over it, as the reading checkpoints are.
 */
class AnnotationStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Stored(val scope: String, val workId: String, val book: AnnotationBook.Snapshot)

    private fun file(scope: String, workId: String) = File(root, ReadingCheckpointKey.digest("$scope\u0000$workId") + ".json")

    @Synchronized fun load(scope: String, workId: String): AnnotationBook {
        val file = file(scope, workId)
        if (!file.exists()) return AnnotationBook()
        val stored = json.decodeFromString<Stored>(file.readText())
        require(stored.scope == scope && stored.workId == workId) { "Highlights file belongs to another book" }
        return stored.book.book()
    }

    @Synchronized fun save(scope: String, workId: String, book: AnnotationBook) {
        check(root.isDirectory || root.mkdirs()) { "Highlight storage is unavailable" }
        val target = file(scope, workId)
        val temporary = File(root, target.nameWithoutExtension + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(Stored(scope, workId, book.snapshot())).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The books under [scope] with edits waiting to be sent. */
    @Synchronized fun pendingWorks(scope: String): List<String> = root.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { runCatching { json.decodeFromString<Stored>(it.readText()) }.getOrNull() }
        .filter { it.scope == scope && it.book.pending.isNotEmpty() }
        .map { it.workId }

    /** Forgets a book's highlights on this device, the outbox with them: after a start over the person asked for (#60) these stay, so this is for tests and a profile's removal. */
    @Synchronized fun drop(scope: String, workId: String): Boolean = file(scope, workId).delete()
}

package com.pocketds.hub.reader

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

@Serializable
data class EpubBookmark(val anchor: String, val locator: JsonObject, val label: String)

/** Local bookmarks belong to the same account/profile and edition as reading checkpoints. */
class EpubBookmarkStore(private val root: File) {
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized fun list(key: ReadingCheckpointKey): List<EpubBookmark> {
        val file = file(key)
        return if (file.exists()) json.decodeFromString<List<EpubBookmark>>(file.readText()) else emptyList()
    }

    @Synchronized fun contains(key: ReadingCheckpointKey, locator: JsonObject): Boolean =
        list(key).any { it.anchor == anchor(locator) }

    /** Returns true when added, false when removed. */
    @Synchronized fun toggle(key: ReadingCheckpointKey, locator: JsonObject): Boolean {
        val entries = list(key)
        val anchor = anchor(locator)
        val present = entries.any { it.anchor == anchor }
        val label = ReadingLocation(locator = locator).label().ifBlank { "Book location" }
        write(key, if (present) entries.filterNot { it.anchor == anchor }
            else entries + EpubBookmark(anchor, locator, label))
        return !present
    }

    @Synchronized fun remove(key: ReadingCheckpointKey, anchor: String): Boolean {
        val entries = list(key)
        if (entries.none { it.anchor == anchor }) return false
        write(key, entries.filterNot { it.anchor == anchor })
        return true
    }

    private fun anchor(locator: JsonObject): String {
        val href = locator["href"]?.jsonPrimitive?.contentOrNull ?: error("Locator has no href")
        val locations = locator["locations"] as? JsonObject
        val fragment = locations?.get("fragments")?.toString()?.takeUnless { it == "[]" }
        val progression = locations?.get("progression")?.jsonPrimitive?.doubleOrNull
        // Chapter-relative progression survives changes to the book's overall pagination.
        return "$href#${fragment ?: progression?.let { (it * 10000).toInt().toString() } ?: "start"}"
    }

    private fun file(key: ReadingCheckpointKey) = File(root, key.fileName + ".bookmarks.json")

    private fun write(key: ReadingCheckpointKey, entries: List<EpubBookmark>) {
        check(root.isDirectory || root.mkdirs()) { "Bookmark storage is unavailable" }
        val target = file(key)
        val temporary = File(root, target.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.write(json.encodeToString(entries).toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

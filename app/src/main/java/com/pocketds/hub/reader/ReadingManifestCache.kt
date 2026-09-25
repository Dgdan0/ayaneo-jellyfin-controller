package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Re-open already cached pages during an outage. This does not pin/download a complete publication. */
class ReadingManifestCache(private val directory:File) {
    private val json=Json { ignoreUnknownKeys=true }
    fun read(key:ReadingCheckpointKey):ReadingPublicationManifest? = runCatching {
        json.decodeFromString<ReadingPublicationManifest>(File(directory,key.fileName+".json").readText())
            .takeIf { it.workId==key.workId && it.sourceItemId==key.sourceItemId }
    }.getOrNull()
    fun save(key:ReadingCheckpointKey,manifest:ReadingPublicationManifest) {
        require(manifest.workId==key.workId && manifest.sourceItemId==key.sourceItemId)
        check(directory.isDirectory || directory.mkdirs())
        val target=File(directory,key.fileName+".json")
        val temporary=File(directory,key.fileName+".tmp")
        temporary.writeText(json.encodeToString(manifest))
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
    }
}

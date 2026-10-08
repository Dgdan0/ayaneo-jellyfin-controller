package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Re-open already cached pages during an outage. This does not pin/download a complete publication. */
class ReadingManifestCache(private val directory:File) {
    companion object {
        /** The reader's cache of what it opened, and what Start over (#60) forgets of it. */
        fun at(context:android.content.Context)=ReadingManifestCache(File(context.cacheDir,"reading-manifests"))
    }
    private val json=Json { ignoreUnknownKeys=true }
    fun read(key:ReadingCheckpointKey):ReadingPublicationManifest? = runCatching {
        json.decodeFromString<ReadingPublicationManifest>(File(directory,key.fileName+".json").readText())
            .takeIf { it.workId==key.workId && it.sourceItemId==key.sourceItemId }
    }.getOrNull()
    /**
     * A cached copy keeps its pages and forgets where you were in them, for every issue of [workId] in every
     * account (#60): it is read when the hub cannot be, and would open the old place. Returns how many it changed.
     */
    fun dropPlace(workId:String):Int {
        var changed=0
        for (file in directory.listFiles().orEmpty()) {
            if (file.extension!="json") continue
            val manifest=runCatching { json.decodeFromString<ReadingPublicationManifest>(file.readText()) }.getOrNull() ?: continue
            if (manifest.workId!=workId || manifest.currentPage==0) continue
            val temporary=File(directory,file.nameWithoutExtension+".tmp")
            temporary.writeText(json.encodeToString(manifest.copy(currentPage=0)))
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING)
            changed++
        }
        return changed
    }
    fun save(key:ReadingCheckpointKey,manifest:ReadingPublicationManifest) {
        require(manifest.workId==key.workId && manifest.sourceItemId==key.sourceItemId)
        check(directory.isDirectory || directory.mkdirs())
        val target=File(directory,key.fileName+".json")
        val temporary=File(directory,key.fileName+".tmp")
        temporary.writeText(json.encodeToString(manifest))
        Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
    }
}

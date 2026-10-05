package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import java.io.File
import kotlinx.serialization.json.Json

/** Lists only this work's current-profile caches; checkpoints and bookmarks live elsewhere. */
object ReaderOfflineFiles {
    fun files(cache: File, scope: String, work: String, sources: List<String>, pageUrl: (String,Int)->String): List<File> {
        require(scope.matches(Regex("[a-f0-9]{64}")))
        require(work.matches(Regex("[A-Za-z0-9_-]+")))
        val result=mutableListOf<File>()
        val ids=sources.toMutableSet()
        val json=Json {ignoreUnknownKeys=true}
        File(cache,"reading-manifests").listFiles().orEmpty().filter {it.extension=="json"}.forEach {file->
            val manifest=runCatching {json.decodeFromString<ReadingPublicationManifest>(file.readText())}.getOrNull() ?: return@forEach
            val key=ReadingCheckpointKey(scope,work,manifest.sourceItemId,"pages")
            if(manifest.workId!=work || file.name!=key.fileName+".json")return@forEach
            ids+=manifest.sourceItemId;result+=file
            for(page in 0 until manifest.pageCount.coerceIn(0,100000)) {
                val name=ReaderPageCachePolicy.fileName(scope+"\u0000"+pageUrl(manifest.sourceItemId,page))
                File(cache,"reader-pages/$name").takeIf(File::isFile)?.let(result::add)
            }
        }
        for(folder in listOf(File(cache,"reading-epub/$scope"),File(cache,"reading-epub/$scope/aligned"),File(cache,"reading-epub/$scope/aligned-slim"))) {
            // Complete packages, partial transfers, and extracted aligned audio share this prefix; the slim
            // read-along edition (#19) has its own folder, its narration in the players' cache (AudioStreams).
            folder.listFiles().orEmpty().filter {it.name.startsWith(work+"_")}.forEach {entry->
                if(entry.isDirectory) result+=entry.walkTopDown().filter(File::isFile).toList() else result+=entry
            }
        }
        ids.forEach {id->
            val folder=File(cache,"reading-audio/$scope/${ReadingCheckpointKey.digest(work+id)}")
            if(folder.isDirectory)result+=folder.walkTopDown().filter(File::isFile).toList()
        }
        return result.distinctBy {it.absolutePath}.filter {file->
            // Never follow a path out of the app cache.
            file.isFile && file.canonicalPath.startsWith(cache.canonicalPath+File.separator)
        }
    }
    fun remove(files: List<File>): Boolean = files.map { !it.exists() || it.delete() }.all {it}
}

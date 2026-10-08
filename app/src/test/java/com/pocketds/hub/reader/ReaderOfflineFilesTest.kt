package com.pocketds.hub.reader

import com.pocketds.hub.model.ReadingPublicationManifest
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReaderOfflineFilesTest {
    @Test fun removesOnlySelectedWorkAndProfileWithoutTouchingProgress() {
        val root=kotlin.io.path.createTempDirectory().toFile()
        try {
            val scope="a".repeat(64);val other="b".repeat(64);val work="work-one"
            fun file(path:String)=File(root,path).apply {parentFile.mkdirs();writeText("fixture")}
            val book=file("reading-epub/$scope/${work}_edition.epub")
            val aligned=file("reading-epub/$scope/aligned/${work}_edition.epub")
            // The read-along edition without its audio (#19).
            val slim=file("reading-epub/$scope/aligned-slim/${work}_edition.epub")
            // The ETag kept beside each copy (#41) goes with it.
            fun tagOf(folder:String,id:String)=file("reading-epub/$folder/"+EpubPackageCache(File(root,"reading-epub/$folder")).etagFile(id,"edition").name)
            val tag=tagOf(scope,work)
            val slimTag=tagOf("$scope/aligned-slim",work)
            val otherTag=tagOf(scope,"work-two")
            val audio=file("reading-audio/$scope/${ReadingCheckpointKey.digest(work+"edition")}/parts/track.m4b")
            val keep=listOf(otherTag,file("reading-epub/$scope/work-two_edition.epub"),file("reading-epub/$other/${work}_edition.epub"),file("reading-checkpoints/progress.json"))
            val key=ReadingCheckpointKey(scope,work,"issue","pages")
            val manifest=file("reading-manifests/${key.fileName}.json").apply {writeText(Json.encodeToString(ReadingPublicationManifest(workId=work,sourceItemId="issue",pageCount=1)))}
            val page=file("reader-pages/${ReaderPageCachePolicy.fileName(scope+"\u0000https://hub/issue/0")}")
            val files=ReaderOfflineFiles.files(root,scope,work,listOf("edition")) {id,p->"https://hub/$id/$p"}
            assertEquals(setOf(book,aligned,slim,tag,slimTag,audio,manifest,page),files.toSet())
            assertTrue(ReaderOfflineFiles.remove(files));assertTrue(files.none(File::exists));assertTrue(keep.all(File::exists))
        } finally {root.deleteRecursively()}
    }
}

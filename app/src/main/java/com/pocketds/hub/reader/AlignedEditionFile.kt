package com.pocketds.hub.reader

import android.content.Context
import java.io.File

/**
 * The read-along edition without its audio (#19: the hub's slim edition), for a screen that has no reader open but needs the words of the
 * narration (#62): the audiobook, finding the sentence of some words, or the words of a sentence. It is the copy Read along keeps, so one
 * that was used is here already, and one that was not is fetched once.
 */
object AlignedEditionFile {
    suspend fun slim(context: Context, session: ReadingProgress.Session, workId: String, sourceItemId: String): File? {
        val cache = EpubPackageCache(File(context.cacheDir, "reading-epub/${session.identity}/aligned-slim"))
        val opened = EpubEdition(cache, workId, sourceItemId).open(
            forceDownload = false, revalidate = true,
            fetch = { destination, check -> session.api.downloadReadingEpub(workId, sourceItemId, destination, readAlong = true, omitAudio = true, check = check) },
            onStage = {}
        )
        return (opened as? EpubEdition.Opened.Ready)?.file
    }
}

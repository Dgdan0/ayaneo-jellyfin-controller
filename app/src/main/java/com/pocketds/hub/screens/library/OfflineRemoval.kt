package com.pocketds.hub.screens.library

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.offline.*
import com.pocketds.hub.reader.*
import com.pocketds.hub.ui.ChoiceOverlay
import kotlinx.coroutines.*

internal fun removeOfflineVideo(host: ScreenHost, overlay: ChoiceOverlay, item: LibraryItem) {
    val rows=OfflineRepository.get(host.viewContext).batches().flatMap {it.jobs}.filter {row->
        val saved=row.manifest.item
        saved.id==item.id || (item.type=="series" && saved.seriesId==item.id) || (item.type=="season" && saved.seasonId==item.id)
    }
    if(rows.isEmpty()){host.notify("No offline copy is saved on this device");return}
    overlay.show("Remove offline copy?","${item.title} · ${rows.size} saved file(s). Server media and watch progress stay available.",listOf(
        ChoiceOverlay.Choice("cancel","Keep offline copy"),ChoiceOverlay.Choice("remove","Remove from this device",danger=true)
    ),onCancel=host::refreshHints) {key->
        if(key=="remove"){rows.forEach {OfflineDownloadService.remove(host.viewContext,it.id)};host.notify("Removing offline copy · server media kept")}
        host.refreshHints()
    };host.refreshHints()
}

internal fun removeOfflineReading(host: ScreenHost, overlay: ChoiceOverlay, work: ReadingWork, scope: CoroutineScope) {
    scope.launch {
        val session=ReadingProgress.get(host.viewContext).session()
        val ids=work.editions.map {it.sourceItemId}+work.sections.flatMap {it.items}.map {it.sourceItemId}
        val files=withContext(Dispatchers.IO) {ReaderOfflineFiles.files(host.viewContext.cacheDir,session.identity,work.id,ids) {id,page->session.api.readingPublicationPageUrl(work.id,id,page)}}
        // A streamed audiobook's tracks (#19) are kept by the players' cache, not as files of their own.
        val streamed=withContext(Dispatchers.IO) {runCatching {AudioStreams.cachedBytes(host.viewContext,ids)}.getOrDefault(0L)}
        if(files.isEmpty() && streamed<=0L){host.notify("No offline book files are saved on this device");return@launch}
        overlay.show("Remove offline copy?","${work.title}. Removes downloaded text, audio and cached comic pages for this title. Server files, bookmarks and reading progress are kept.",listOf(
            ChoiceOverlay.Choice("cancel","Keep offline copy"),ChoiceOverlay.Choice("remove","Remove from this device",danger=true)
        ),onCancel=host::refreshHints) {key->
            if(key=="remove") scope.launch {
                val ok=withContext(Dispatchers.IO){ReaderOfflineFiles.remove(files) && AudioStreams.remove(host.viewContext,ids)}
                host.notify(if(ok) "Offline copy removed · reading progress kept" else "Some files could not be removed. Try again after closing the reader.")
            }
            host.refreshHints()
        };host.refreshHints()
    }
}

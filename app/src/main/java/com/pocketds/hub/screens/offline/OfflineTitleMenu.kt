package com.pocketds.hub.screens.offline

import com.pocketds.hub.nav.ScreenHost
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.offline.OfflineDownload
import com.pocketds.hub.screens.library.SubtitleScreen
import com.pocketds.hub.ui.ChoiceOverlay

/** The same two distinct tasks as the online title's overflow menu. */
internal fun showOfflineTitleMenu(
    host: ScreenHost, api: HubApi, overlay: ChoiceOverlay,
    row: OfflineDownload, ringVisible: () -> Boolean
) {
    val item = row.manifest.item
    overlay.show("More actions", item.title, listOf(
        ChoiceOverlay.Choice("tracks", "Audio & subtitles", "Choose saved tracks before playback"),
        ChoiceOverlay.Choice("fetch", "Find / update subtitles", "Search providers and update this downloaded copy"),
        ChoiceOverlay.Choice("offline-remove", "Remove offline copy", "Only this device; server media and progress are kept"),
        ChoiceOverlay.Choice("server-remove", "Delete from server…", "Requires a connection; review files before confirming", danger = true)
    ), onCancel = host::refreshHints) { action ->
        when (action) {
            "tracks" -> host.openPlaybackOptions(item.id)
            "fetch" -> host.push(SubtitleScreen(api, item.id, item.title, ringVisible))
            "offline-remove" -> com.pocketds.hub.screens.library.removeOfflineVideo(host,overlay,item)
            "server-remove" -> host.push(com.pocketds.hub.screens.library.MediaRemovalScreen(api,"video",item.id,ringVisible,closeDetails=false))
        }
        host.refreshHints()
    }
    host.refreshHints()
}

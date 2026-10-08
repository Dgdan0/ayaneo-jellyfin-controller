package com.pocketds.hub.offline

/**
 * An episode card's download corner and its pad menu (#48), pure: what the small
 * badge shows for a download's state, what a tap on it does, and which rows
 * Ⓨ opens on a card.
 */
object EpisodeDownloadMarks {

    enum class Mark { ARROW, RING, WAITING, TICK }

    /** [progress] is 0..1 and only the ring draws it. */
    data class Badge(val mark: Mark, val progress: Float = 0f)

    /** The badge for an episode with this download, or none (null). */
    fun badge(state: OfflineState?, progress: Float): Badge = when (state) {
        null, OfflineState.FAILED -> Badge(Mark.ARROW)
        OfflineState.DOWNLOADING -> Badge(Mark.RING, progress.coerceIn(0f, 1f))
        OfflineState.QUEUED, OfflineState.WAITING, OfflineState.PAUSED -> Badge(Mark.WAITING)
        OfflineState.COMPLETE -> Badge(Mark.TICK)
    }

    /** What a tap on the badge, or Ⓐ on the card's Download row, does. */
    enum class Tap { DOWNLOAD, RETRY, STOP, MENU }

    fun tap(state: OfflineState?): Tap = when (state) {
        null -> Tap.DOWNLOAD
        OfflineState.FAILED -> Tap.RETRY
        OfflineState.COMPLETE -> Tap.MENU
        else -> Tap.STOP
    }

    /** The accessibility words for the badge: what it is and what a tap does. */
    fun description(state: OfflineState?, progress: Float): String = when (state) {
        null -> "Download"
        OfflineState.FAILED -> "Download failed, tap to try again"
        OfflineState.COMPLETE -> "On this device"
        OfflineState.DOWNLOADING -> "Downloading ${(progress.coerceIn(0f, 1f) * 100).toInt()} percent, tap to stop"
        else -> "Waiting to download, tap to stop"
    }

    /** What a card's menu offers (#48): the row ids, in order, and the words of the download row. */
    data class MenuRow(val id: String, val label: String)

    const val PLAY = "play"
    const val DOWNLOAD = "download"
    const val STOP = "stop"
    const val REMOVE = "remove"
    const val SELECT = "select"
    const val RELEASES = "releases"

    /**
     * Ⓨ on a card: Play (Resume when it has a place), Download, Stop or Remove download by what the card holds, Select
     * episodes, and Find releases where the series has them. A download that failed is offered again.
     */
    fun menu(state: OfflineState?, resumes: Boolean, available: Boolean, canFindReleases: Boolean): List<MenuRow> = buildList {
        add(MenuRow(PLAY, if (resumes) "Resume" else "Play"))
        when (state) {
            null -> if (available) add(MenuRow(DOWNLOAD, "Download"))
            OfflineState.FAILED -> add(MenuRow(DOWNLOAD, "Try the download again"))
            OfflineState.COMPLETE -> add(MenuRow(REMOVE, "Remove download"))
            else -> add(MenuRow(STOP, "Stop download"))
        }
        add(MenuRow(SELECT, "Select episodes"))
        if (canFindReleases) add(MenuRow(RELEASES, "Find releases"))
    }
}

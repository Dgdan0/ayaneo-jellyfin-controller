package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable data class ServerReadingList(val id: Int, val title: String, val summary: String = "", val itemCount: Int = 0, val promoted: Boolean = false)
@Serializable data class ServerReadingListsResponse(val lists: List<ServerReadingList> = emptyList())
@Serializable data class ServerReadingListEntry(val id: Int, val order: Int, val workId: String, val sourceItemId: String, val title: String, val seriesTitle: String, val volume: String = "", val kind: String = "comic", val artwork: String = "", val pageCount: Int = 0, val progress: ReadingProgress? = null)
@Serializable data class ServerReadingListResponse(val list: ServerReadingList, val items: List<ServerReadingListEntry> = emptyList())

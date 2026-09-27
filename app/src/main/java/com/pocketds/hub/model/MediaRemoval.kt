package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable data class MediaRemovalRequest(val kind: String, val id: String)
@Serializable data class MediaRemovalConfirmation(val ticket: String, val confirm: Boolean = true)
@Serializable data class MediaRemovalPreview(val ticket: String = "", val title: String = "", val description: String = "", val files: List<String> = emptyList(), val fileCount: Int = 0)

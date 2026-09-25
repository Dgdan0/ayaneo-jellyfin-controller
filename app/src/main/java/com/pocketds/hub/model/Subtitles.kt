package com.pocketds.hub.model

import kotlinx.serialization.Serializable

@Serializable data class SubtitleRecord(
    val id: String, val language: String = "", val provider: String = "",
    val score: String = "", val date: String = "", val description: String = "",
    val installed: Boolean = false, val embedded: Boolean = false,
    val forced: Boolean = false, val hi: Boolean = false
)
@Serializable data class SubtitleState(
    val records: List<SubtitleRecord> = emptyList(), val canDownload: Boolean = false,
    val warning: String = ""
)
@Serializable data class SubtitleCandidate(
    val ticket: String, val language: String = "", val provider: String = "",
    val score: Double = 0.0, val release: String = "",
    val matches: List<String> = emptyList(), val mismatches: List<String> = emptyList(),
    val forced: Boolean = false, val hi: Boolean = false
)
@Serializable data class SubtitleSearch(val candidates: List<SubtitleCandidate> = emptyList())
@Serializable data class SubtitleDownload(val ticket: String)

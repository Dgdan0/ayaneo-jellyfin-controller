package com.pocketds.hub.playback

import android.content.Context
import com.pocketds.hub.model.PlaybackPrepareResponse
import com.pocketds.hub.model.PlaybackTrack
import com.pocketds.hub.settings.Prefs

/** Stream indices change between files, so only language and subtitle mode are persisted. */
object PlaybackPreferences {
    data class Selection(
        val audioLanguage: String = "",
        val subtitlesEnabled: Boolean? = null,
        val subtitleLanguage: String = "",
        val subtitleOffsetMillis: Long = 0L
    )

    fun get(context: Context, userId: String, seriesOrItemId: String): Selection {
        val key = key(userId, seriesOrItemId)
        val prefs = Prefs.of(context)
        val mode = prefs.getString("${key}_subtitle_mode", "").orEmpty()
        return Selection(
            audioLanguage = prefs.getString("${key}_audio_language", "").orEmpty(),
            subtitlesEnabled = when (mode) { "on" -> true; "off" -> false; else -> null },
            subtitleLanguage = prefs.getString("${key}_subtitle_language", "").orEmpty(),
            subtitleOffsetMillis = prefs.getLong("${key}_subtitle_offset_millis", 0L)
                .coerceIn(-MAX_SUBTITLE_OFFSET_MILLIS, MAX_SUBTITLE_OFFSET_MILLIS)
        )
    }

    fun remember(
        context: Context,
        userId: String,
        plan: PlaybackPrepareResponse,
        selectedAudio: PlaybackTrack?,
        selectedSubtitle: PlaybackTrack?
    ) {
        val scope = plan.item.seriesId.ifEmpty { plan.item.id }
        val key = key(userId, scope)
        Prefs.of(context).edit()
            .putString("${key}_audio_language", selectedAudio?.language.orEmpty())
            .putString("${key}_subtitle_mode", if (selectedSubtitle == null) "off" else "on")
            .putString("${key}_subtitle_language", selectedSubtitle?.language.orEmpty())
            .apply()
    }

    /**
     * Timing corrections belong to this device and Jellyfin profile. Episodes
     * in one series share a correction; a movie uses its own item id.
     */
    fun rememberSubtitleOffset(
        context: Context,
        userId: String,
        plan: PlaybackPrepareResponse,
        offsetMillis: Long
    ) {
        val scope = plan.item.seriesId.ifEmpty { plan.item.id }
        Prefs.of(context).edit()
            .putLong(
                "${key(userId, scope)}_subtitle_offset_millis",
                offsetMillis.coerceIn(-MAX_SUBTITLE_OFFSET_MILLIS, MAX_SUBTITLE_OFFSET_MILLIS)
            )
            .apply()
    }

    fun preferredAudio(plan: PlaybackPrepareResponse, selection: Selection): PlaybackTrack? =
        selection.audioLanguage.takeIf { it.isNotEmpty() }?.let { language ->
            plan.audioTracks.firstOrNull { it.language.equals(language, ignoreCase = true) }
        }

    fun preferredSubtitle(plan: PlaybackPrepareResponse, selection: Selection): PlaybackTrack? =
        if (selection.subtitlesEnabled != true) null else
            plan.subtitleTracks.firstOrNull {
                selection.subtitleLanguage.isNotEmpty() &&
                    it.language.equals(selection.subtitleLanguage, ignoreCase = true)
            } ?: plan.subtitleTracks.firstOrNull { it.default }

    private fun key(userId: String, scope: String): String =
        "playback_${userId.hashCode().toUInt().toString(16)}_${scope.hashCode().toUInt().toString(16)}"

    private const val MAX_SUBTITLE_OFFSET_MILLIS = 60_000L
}

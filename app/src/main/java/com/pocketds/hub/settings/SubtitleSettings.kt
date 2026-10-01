package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.playback.SubtitleLook
import com.pocketds.hub.playback.SubtitleSize
import com.pocketds.hub.playback.SubtitleStyle

/** How subtitles look in every video. Settings › Subtitles; the player's CC sheet writes here too. */
object SubtitleSettings {
    private const val KEY_STYLE = "subtitle_style"
    private const val KEY_SIZE = "subtitle_size"
    private const val KEY_LIFT = "subtitle_lift_with_controls"

    fun look(context: Context): SubtitleLook = Prefs.of(context).let { prefs ->
        SubtitleLook(
            style = SubtitleStyle.entries.firstOrNull { it.name == prefs.getString(KEY_STYLE, null) } ?: SubtitleLook().style,
            size = SubtitleSize.entries.firstOrNull { it.name == prefs.getString(KEY_SIZE, null) } ?: SubtitleLook().size,
            liftWithControls = prefs.getBoolean(KEY_LIFT, SubtitleLook().liftWithControls)
        )
    }

    fun save(context: Context, look: SubtitleLook) {
        Prefs.of(context).edit()
            .putString(KEY_STYLE, look.style.name)
            .putString(KEY_SIZE, look.size.name)
            .putBoolean(KEY_LIFT, look.liftWithControls)
            .apply()
    }
}

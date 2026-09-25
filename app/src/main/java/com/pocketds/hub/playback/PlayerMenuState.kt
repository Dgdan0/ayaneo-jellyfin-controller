package com.pocketds.hub.playback

import java.util.Locale
import kotlin.math.abs

internal class PlayerMenuState {
    var trackTab: String = "subtitles"
        private set

    fun selectTrackTab(tab: String) {
        if (tab == "audio" || tab == "subtitles") trackTab = tab
    }
}

internal object SubtitleTimingPolicy {
    private const val STEP_MILLIS = 100L
    fun range(wide: Boolean): Long = if (wide) 60_000L else 5_000L
    fun clamp(value: Long, wide: Boolean): Long = value.coerceIn(-range(wide), range(wide))
    fun steps(wide: Boolean): Int = (range(wide) * 2 / STEP_MILLIS).toInt()
    fun progressToOffset(progress: Int, wide: Boolean): Long =
        (progress.coerceIn(0, steps(wide)) - steps(wide) / 2) * STEP_MILLIS
    fun offsetToProgress(value: Long, wide: Boolean): Int =
        ((clamp(value, wide) + range(wide)) / STEP_MILLIS).toInt()
    fun label(value: Long): String = when {
        value == 0L -> "0.0 s · In sync"
        value < 0L -> "−${decimal(abs(value))} s · Earlier"
        else -> "+${decimal(value)} s · Later"
    }
    private fun decimal(value: Long) = String.format(Locale.US, "%.1f", value / 1_000.0)
}

package com.pocketds.hub.settings

import android.content.Context

data class NotificationLimits(
    val sonarr: Int = DEFAULT_SONARR,
    val radarr: Int = DEFAULT_RADARR,
    val bazarr: Int = DEFAULT_BAZARR
) {
    companion object {
        const val DEFAULT_SONARR = 60
        const val DEFAULT_RADARR = 20
        const val DEFAULT_BAZARR = 40
    }
}

object NotificationSettings {
    val choices = listOf(20, 40, 60, 100)

    private const val KEY_SONARR_LIMIT = "notification_limit_sonarr"
    private const val KEY_RADARR_LIMIT = "notification_limit_radarr"
    private const val KEY_BAZARR_LIMIT = "notification_limit_bazarr"

    fun limits(context: Context): NotificationLimits = NotificationLimits(
        sonarr = value(context, KEY_SONARR_LIMIT, NotificationLimits.DEFAULT_SONARR),
        radarr = value(context, KEY_RADARR_LIMIT, NotificationLimits.DEFAULT_RADARR),
        bazarr = value(context, KEY_BAZARR_LIMIT, NotificationLimits.DEFAULT_BAZARR)
    )

    fun setLimit(context: Context, service: String, limit: Int) {
        require(limit in choices) { "notification limit must be one of $choices" }
        val key = when (service) {
            "sonarr" -> KEY_SONARR_LIMIT
            "radarr" -> KEY_RADARR_LIMIT
            "bazarr" -> KEY_BAZARR_LIMIT
            else -> error("unknown notification service: $service")
        }
        Prefs.of(context).edit().putInt(key, limit).apply()
    }

    private fun value(context: Context, key: String, default: Int): Int =
        Prefs.of(context).getInt(key, default).takeIf(choices::contains) ?: default
}

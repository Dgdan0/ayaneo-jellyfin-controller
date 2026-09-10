package com.pocketds.hub.settings

import android.content.Context
import android.os.Environment
import java.io.File

data class OfflineStorageLocation(
    val key: String,
    val label: String,
    val root: File,
    val removable: Boolean,
    val availableBytes: Long,
    val totalBytes: Long
)

object OfflineSettings {
    private const val FILE = "offline_settings"
    private const val WIFI_ONLY = "wifi_only"
    private const val CHARGING_ONLY = "charging_only"
    private const val MIN_FREE_MB = "min_free_mb"
    private const val MAX_RETRIES = "max_retries"
    private const val STORAGE_ROOT = "storage_root"

    fun wifiOnly(context: Context) = prefs(context).getBoolean(WIFI_ONLY, true)
    fun setWifiOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean(WIFI_ONLY, value).apply()

    fun chargingOnly(context: Context) = prefs(context).getBoolean(CHARGING_ONLY, false)
    fun setChargingOnly(context: Context, value: Boolean) = prefs(context).edit().putBoolean(CHARGING_ONLY, value).apply()

    fun minimumFreeMb(context: Context) = prefs(context).getInt(MIN_FREE_MB, 512).coerceIn(128, 4096)
    fun setMinimumFreeMb(context: Context, value: Int) = prefs(context).edit().putInt(MIN_FREE_MB, value).apply()

    fun maxRetries(context: Context) = prefs(context).getInt(MAX_RETRIES, 5).coerceIn(0, 20)
    fun setMaxRetries(context: Context, value: Int) = prefs(context).edit().putInt(MAX_RETRIES, value).apply()

    /**
     * App-private roots on every currently mounted Android storage volume.
     * Existing jobs retain their absolute paths when this preference changes;
     * the selected location applies only to newly queued files.
     */
    fun storageLocations(context: Context): List<OfflineStorageLocation> =
        context.getExternalFilesDirs(null).filterNotNull().mapIndexed { index, directory ->
            val root = File(directory, "offline")
            val removable = runCatching { Environment.isExternalStorageRemovable(directory) }.getOrDefault(index > 0)
            OfflineStorageLocation(
                key = directory.absolutePath,
                label = when {
                    removable -> "SD card"
                    index == 0 -> "Internal storage"
                    else -> "Storage ${index + 1}"
                },
                root = root,
                removable = removable,
                availableBytes = directory.usableSpace,
                totalBytes = directory.totalSpace
            )
        }

    fun selectedStorage(context: Context): OfflineStorageLocation? {
        val locations = storageLocations(context)
        val selected = prefs(context).getString(STORAGE_ROOT, "").orEmpty()
        return if (selected.isEmpty()) locations.firstOrNull()
        else locations.firstOrNull { it.key == selected }
    }

    fun selectedStorageKey(context: Context): String =
        prefs(context).getString(STORAGE_ROOT, "").orEmpty()

    fun setSelectedStorage(context: Context, key: String) =
        prefs(context).edit().putString(STORAGE_ROOT, key).apply()

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

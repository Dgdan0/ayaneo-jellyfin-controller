package com.pocketds.hub.settings

import android.content.Context

/**
 * Keys the app no longer reads, taken out of the settings file at launch
 * (#20): the look (Glass or Classic) and Classic's light, dark or system
 * theme went with the Classic look, as did the library the Classic Library
 * page opened on. Each is removed the first time; after that there is
 * nothing to find.
 */
object RetiredSettings {
    val KEYS = listOf("look", "theme_mode", "library_last_view")

    /** Which of [KEYS] are among [stored]: what [clear] removes. Pure, so it is tested. */
    fun among(stored: Set<String>): List<String> = KEYS.filter { it in stored }

    fun clear(context: Context) {
        val prefs = Prefs.of(context)
        val gone = among(prefs.all.keys)
        if (gone.isEmpty()) return
        prefs.edit().apply { gone.forEach { remove(it) } }.apply()
    }
}

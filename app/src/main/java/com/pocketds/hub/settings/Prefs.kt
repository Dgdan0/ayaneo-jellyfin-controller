package com.pocketds.hub.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * The single preferences file, following the sibling project's convention of
 * one file with a small `object` per concern rather than a settings framework.
 *
 * The name deliberately differs from the keyboard project's "pocketds_settings"
 * so the two apps can be installed side by side without either reading the
 * other's keys.
 */
object Prefs {
    private const val PREFS_NAME = "pocketds_hub_settings"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

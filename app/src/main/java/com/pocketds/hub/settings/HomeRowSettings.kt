package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.screens.home.HomeRows

/** Settings › Home: the order of Home's rows and which are hidden. */
object HomeRowSettings {
    private const val KEY_ORDER = "home_row_order"
    private const val KEY_HIDDEN = "home_rows_hidden"

    fun order(context: Context): List<String> =
        Prefs.of(context).getString(KEY_ORDER, null)?.split(',')?.filter(String::isNotBlank)
            ?.let { HomeRows.complete(it) } ?: HomeRows.DEFAULT_ORDER

    fun hidden(context: Context): Set<String> =
        Prefs.of(context).getString(KEY_HIDDEN, null)?.split(',')?.filter(String::isNotBlank)?.toSet() ?: emptySet()

    fun save(context: Context, order: List<String>, hidden: Set<String>) {
        Prefs.of(context).edit()
            .putString(KEY_ORDER, order.joinToString(","))
            .putString(KEY_HIDDEN, hidden.joinToString(","))
            .apply()
    }
}

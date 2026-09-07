package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.net.HubEndpoints

/**
 * Where the hub is, and how to prove we are allowed to talk to it.
 *
 * Plain SharedPreferences, following the sibling project. Deliberately not
 * EncryptedSharedPreferences: that adds about a megabyte of Tink and depends on
 * the hardware keystore, and this is vendor firmware with a history of
 * nonstandard behaviour -- an AEADBadTagException on a keystore edge case would
 * lock the user out of their own token with no recovery path.
 *
 * Against that: the device is a personal handheld you physically hold, the token
 * is scoped to a home-lab hub and can be revoked by label from the hub side, and
 * app-private storage already requires root to read. If the calculation ever
 * changes, this file is the only thing to change.
 */
object HubSettings {

    private const val KEY_URL = "hub_url"
    private const val KEY_TOKEN = "hub_token"

    fun baseUrl(context: Context): String =
        Prefs.of(context).getString(KEY_URL, "").orEmpty()

    fun token(context: Context): String =
        Prefs.of(context).getString(KEY_TOKEN, "").orEmpty()

    val isConfigured: (Context) -> Boolean = { baseUrl(it).isNotEmpty() && token(it).isNotEmpty() }

    fun save(context: Context, url: String, token: String) {
        Prefs.of(context).edit()
            .putString(KEY_URL, HubEndpoints.normaliseBase(url))
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    /** Clears both. Offered in Settings so a lost device can be cut off locally. */
    fun forget(context: Context) {
        Prefs.of(context).edit().remove(KEY_URL).remove(KEY_TOKEN).apply()
    }
}

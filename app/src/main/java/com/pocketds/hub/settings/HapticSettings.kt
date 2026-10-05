package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.ui.HapticStrength

object HapticSettings {
    private const val KEY_STRENGTH = "haptic_strength"

    fun strength(context: Context): HapticStrength =
        HapticStrength.fromStored(Prefs.of(context).getString(KEY_STRENGTH, null))
}

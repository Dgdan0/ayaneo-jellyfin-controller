package com.pocketds.hub.ui

import android.content.res.Configuration
import android.widget.ImageView
import com.pocketds.hub.R

/** Reload a real logo's light/night asset without restarting the Activity or tinting the logo. */
object ServiceLogo {
    private const val RESOURCE_TAG = -0x7fffffc2

    /** A service's own logo; the app's mark for one without (the hub itself). */
    fun resource(service: String): Int = when (service.lowercase()) {
        "jellyfin" -> R.drawable.logo_jellyfin
        "jellyseerr" -> R.drawable.logo_jellyseerr
        "prowlarr" -> R.drawable.logo_prowlarr
        "sonarr" -> R.drawable.logo_sonarr
        "radarr" -> R.drawable.logo_radarr
        "readarr" -> R.drawable.logo_readarr
        "bazarr" -> R.drawable.logo_bazarr
        "cleanuparr" -> R.drawable.logo_cleanuparr
        "qbittorrent" -> R.drawable.logo_qbittorrent
        "kavita" -> R.drawable.logo_kavita
        "storyteller" -> R.drawable.logo_storyteller
        "bookkeeprr" -> R.drawable.logo_bookkeeprr
        else -> R.drawable.ic_launcher_foreground
    }

    fun bind(view: ImageView, resource: Int) {
        view.setTag(RESOURCE_TAG, resource)
        refresh(view)
    }

    fun refresh(view: ImageView) {
        val resource = view.getTag(RESOURCE_TAG) as? Int ?: return
        val configuration = Configuration(view.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (Theme.isDark(view.context)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = view.context.createConfigurationContext(configuration)
        view.setImageDrawable(themed.getDrawable(resource))
    }
}

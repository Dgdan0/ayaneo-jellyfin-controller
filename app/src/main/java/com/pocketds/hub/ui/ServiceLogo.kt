package com.pocketds.hub.ui

import android.widget.ImageView
import com.pocketds.hub.R

/**
 * A service's own logo, untinted. There is one set, drawn for the dark page:
 * the light-theme variants went with the Classic look (#20).
 */
object ServiceLogo {
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
        view.setImageResource(resource)
    }
}

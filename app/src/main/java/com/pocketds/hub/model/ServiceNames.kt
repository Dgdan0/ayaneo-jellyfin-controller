package com.pocketds.hub.model

/**
 * How each hub service id is spelled on screen: "qBittorrent", "BookKeeprr",
 * never "Qbittorrent". One map for Manage, status lines and anything else that
 * names a service.
 */
object ServiceNames {

    private val names = mapOf(
        "jellyfin" to "Jellyfin",
        "jellyseerr" to "Jellyseerr",
        "prowlarr" to "Prowlarr",
        "sonarr" to "Sonarr",
        "radarr" to "Radarr",
        "readarr" to "Readarr",
        "qbittorrent" to "qBittorrent",
        "bazarr" to "Bazarr",
        "cleanuparr" to "Cleanuparr",
        "bookkeeprr" to "BookKeeprr",
        "kavita" to "Kavita",
        "storyteller" to "Storyteller"
    )

    fun display(id: String): String = names[id.lowercase()] ?: id.replaceFirstChar { it.uppercase() }
}

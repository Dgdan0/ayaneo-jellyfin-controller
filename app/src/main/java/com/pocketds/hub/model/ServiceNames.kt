package com.pocketds.hub.model

/**
 * How each hub service id is spelled on screen: "qBittorrent", "BookKeeprr",
 * never "Qbittorrent", and the order services are listed in. One map for
 * Manage, the Activity dashboard, status lines and anything else that names a
 * service.
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

    /** Where a service sits in a list: playback first, then requests, the *arrs, the client, books. Unknown ones last. */
    fun rank(id: String): Int = names.keys.indexOf(id.lowercase()).takeIf { it >= 0 } ?: Int.MAX_VALUE
}

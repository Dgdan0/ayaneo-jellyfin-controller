package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ActivityItem

/** Join known provider identities, never similar release names. */
fun transferMatchesMedia(item: ActivityItem, key: String): Boolean {
    val parts=key.split(':')
    if(parts.size!=3) return false
    val id=parts[2].toIntOrNull()?.takeIf {it>0} ?: return false
    val arr=item.arr ?: return false
    return when {
        parts[0]=="tmdb" && parts[1]=="movie" -> arr.service=="radarr" && arr.tmdbId==id
        parts[0]=="tmdb" && parts[1]=="series" -> arr.service=="sonarr" && arr.tmdbId==id
        parts[0]=="tvdb" && parts[1]=="series" -> arr.service=="sonarr" && arr.tvdbId==id
        else -> false
    }
}

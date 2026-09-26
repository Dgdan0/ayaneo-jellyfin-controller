package com.pocketds.hub.screens.downloads

import com.pocketds.hub.model.ActivityItem
import com.pocketds.hub.model.ArrRef
import org.junit.Assert.*
import org.junit.Test

class TransferTargetTest {
    @Test fun matchesProviderAndMediaTypeWithoutGuessingFromTitles() {
        val movie=ActivityItem(title="Same title",arr=ArrRef(service="radarr",tmdbId=42))
        val series=movie.copy(arr=ArrRef(service="sonarr",tmdbId=42,tvdbId=99))
        assertTrue(transferMatchesMedia(movie,"tmdb:movie:42"))
        assertFalse(transferMatchesMedia(movie,"tmdb:series:42"))
        assertTrue(transferMatchesMedia(series,"tmdb:series:42"))
        assertTrue(transferMatchesMedia(series,"tvdb:series:99"))
        assertFalse(transferMatchesMedia(series,"tvdb:series:42"))
        assertFalse(transferMatchesMedia(movie.copy(arr=null),"tmdb:movie:42"))
        for(key in listOf("tmdb:movie:0","movie:42","tmdb:movie:bad","tmdb:movie:43")) assertFalse(transferMatchesMedia(movie,key))
    }
}

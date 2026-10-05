package com.pocketds.hub.ui

/** A library's artwork is chosen for the day, so a root drawn yesterday is read again. */
object LibraryArtworkRefresh {
    fun needed(lastLoadedDay: String, currentDay: String): Boolean = lastLoadedDay != currentDay
}

package com.pocketds.hub.screens.library

/** Invalidates retained shelves after an explicit server mutation. Main-thread only. */
internal object MediaLibraryChanges {
    var revision: Long = 0
        private set
    fun changed() { revision++ }
}

package com.pocketds.hub.reader

/** Keeps narration checkpoints intact while Readium delivers its initial page callbacks. */
class ReadAlongSession {
    private var preparing = false
    private var retained: ReadAlongPosition? = null

    fun beginOpen() { preparing = true }
    fun ready(resume: ReadAlongPosition?) { retained = resume; preparing = false }
    fun endOpenIfPending() { preparing = false }
    fun canSavePage(narrationAvailable: Boolean = true): Boolean =
        !preparing && (narrationAvailable || retained == null)
    fun pointForSave(playing: ReadAlongPosition?): ReadAlongPosition? = playing ?: retained
    fun record(point: ReadAlongPosition) { retained = point }
    fun switchToText() { retained = null }
}

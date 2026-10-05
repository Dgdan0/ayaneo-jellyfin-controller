package com.pocketds.hub.reader

/** What the app can be heard playing (#16, A1). */
enum class AudioSource { VIDEO, NARRATION, AUDIOBOOK }

/**
 * One sound at a time (#16, A1): video, a book's narration and an audiobook
 * never play over each other. Each player reports when it starts and stops;
 * a start returns the others that were playing, for the caller to pause.
 * Android's audio focus would usually pause them too, but not always at once
 * and not for players inside one app that share an attribute set, and the
 * mini player has to know what is sounding. Pure, so a JVM test pins it.
 */
class AudioArbiter {
    private val playing = LinkedHashSet<AudioSource>()

    /** What is sounding now, the latest last. */
    val sounding: Set<AudioSource> get() = playing.toSet()

    /** [source] starts: everything else that was playing must pause, and no longer counts as playing. */
    fun start(source: AudioSource): Set<AudioSource> {
        val others = playing.filterTo(LinkedHashSet()) { it != source }
        playing.clear()
        playing += source
        return others
    }

    fun stop(source: AudioSource) {
        playing -= source
    }
}

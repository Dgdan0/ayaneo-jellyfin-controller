package com.pocketds.hub.state

enum class ContentMode(val stored: String, val label: String) {
    MEDIA("media", "Media"),
    BOOKS("books", "Books");

    companion object {
        fun fromStored(raw: String?): ContentMode =
            entries.firstOrNull { it.stored == raw?.trim()?.lowercase() } ?: MEDIA
    }
}

/** Small per-mode memory used by screens that share one global content switch. */
class ContentModeMemory<T> {
    private val values = mutableMapOf<ContentMode, T>()

    fun remember(mode: ContentMode, value: T) {
        values[mode] = value
    }

    fun recall(mode: ContentMode): T? = values[mode]

    fun clear(mode: ContentMode) {
        values.remove(mode)
    }
}

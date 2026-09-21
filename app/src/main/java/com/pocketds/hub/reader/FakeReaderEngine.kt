package com.pocketds.hub.reader

import kotlin.math.roundToInt

data class ReaderPreview(
    val pageIndex: Int,
    val locator: ReaderLocator,
    val title: String
)

data class ReaderRenderModel(
    val profile: ReaderProfile,
    val title: String,
    val chapter: String,
    val pageIndex: Int,
    val pageCount: Int,
    val firstColumn: String,
    val secondColumn: String,
    val highlightedToken: String = "",
    val audioProgress: Double = 0.0
)

/**
 * Deterministic fixture engine used to accept the shared shell before a real
 * publication parser is allowed into the stack.
 */
class FakeReaderEngine(
    val profile: ReaderProfile,
    val publicationId: String,
    val title: String,
    val pageCount: Int = when (profile) {
        ReaderProfile.COMIC, ReaderProfile.MANGA -> 18
        ReaderProfile.BOOK -> 24
        ReaderProfile.READ_ALONG -> 12
    },
    startProgression: Double = 0.0
) {
    init {
        require(pageCount > 0) { "a fixture needs at least one page" }
    }

    var currentIndex: Int = indexFor(startProgression)
        private set

    fun next(): Boolean = move(1)

    fun previous(): Boolean = move(-1)

    fun move(delta: Int): Boolean {
        val next = (currentIndex + delta).coerceIn(0, pageCount - 1)
        if (next == currentIndex) return false
        currentIndex = next
        return true
    }

    fun preview(progression: Double): ReaderPreview {
        val index = indexFor(progression)
        return ReaderPreview(index, locator(index), "Preview · ${label(index)}")
    }

    fun seek(locator: ReaderLocator): Boolean {
        if (locator.publicationId != publicationId) return false
        val next = indexFor(locator.progression)
        if (next == currentIndex) return false
        currentIndex = next
        return true
    }

    fun locator(): ReaderLocator = locator(currentIndex)

    fun renderModel(pageIndex: Int = currentIndex): ReaderRenderModel {
        val index = pageIndex.coerceIn(0, pageCount - 1)
        val chapterNumber = index / 4 + 1
        val chapter = when (profile) {
            ReaderProfile.COMIC -> "Issue preview"
            ReaderProfile.MANGA -> "Chapter $chapterNumber"
            ReaderProfile.BOOK, ReaderProfile.READ_ALONG -> "Chapter $chapterNumber"
        }
        val text = paragraphs[(index + chapterNumber) % paragraphs.size]
        val split = text.length / 2
        val splitAt = text.indexOf(' ', split).takeIf { it > 0 } ?: split
        return ReaderRenderModel(
            profile = profile,
            title = title,
            chapter = chapter,
            pageIndex = index,
            pageCount = pageCount,
            firstColumn = text.substring(0, splitAt).trim(),
            secondColumn = text.substring(splitAt).trim(),
            highlightedToken = if (profile == ReaderProfile.READ_ALONG) {
                text.split(' ').filter { it.length > 4 }[(index * 3) % text.split(' ').filter { it.length > 4 }.size]
            } else "",
            audioProgress = locator(index).progression
        )
    }

    private fun locator(index: Int): ReaderLocator = ReaderLocator(
        publicationId = publicationId,
        chapterId = "chapter-${index / 4 + 1}",
        progression = if (pageCount == 1) 1.0 else index.toDouble() / (pageCount - 1),
        label = label(index),
        completed = index == pageCount - 1
    )

    private fun label(index: Int): String = "Page ${index + 1} of $pageCount"

    private fun indexFor(progression: Double): Int =
        (progression.coerceIn(0.0, 1.0) * (pageCount - 1)).roundToInt()
            .coerceIn(0, pageCount - 1)

    private companion object {
        val paragraphs = listOf(
            "The Pocket DS settled into the quiet room while the page filled the display. " +
                "Nothing competed with the words except a small position marker near the edge. " +
                "A single press moved forward, and the controls stayed out of sight until they were needed.",
            "A reader should remember the exact place without making the reader think about saving it. " +
                "When another device has moved much farther, both positions should be shown clearly. " +
                "The choice belongs to the person holding the book, not to whichever clock wrote last.",
            "Comics need room for their artwork, books need careful typography, and narration needs time. " +
                "They can still share one reliable shell: the same way out, the same bookmark, the same " +
                "preview rules, and the same promise that browsing ahead will not lose the real position.",
            "Below it, six quiet gears turned the sleeping island toward morning. The sentence remained " +
                "anchored while the narrator crossed each word in turn, ready to continue as text or sound."
        )
    }
}

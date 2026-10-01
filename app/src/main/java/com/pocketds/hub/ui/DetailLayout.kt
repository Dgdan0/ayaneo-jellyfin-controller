package com.pocketds.hub.ui

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.SeriesPlayTargetResponse
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.ceil

/** Shared geometry is in dp; text is allowed to grow instead of being clipped by fixed rows. */
object DetailLayout {
    const val POSTER_FOCUS_SCALE = 1.035f
    const val FOCUS_RING_DP = 2
    /** A full-width backdrop behind the words, for anything with landscape art and the room for it. */
    fun useHero(type: String, hasLandscapeArt: Boolean, widthDp: Int, fontScale: Float) =
        type in setOf("movie", "episode", "series") && hasLandscapeArt && widthDp >= 600 && fontScale <= 1.2f
    fun focusClearance(sizeDp: Int) = ceil(sizeDp * (POSTER_FOCUS_SCALE - 1) / 2 + FOCUS_RING_DP + 2).toInt()
    fun posterCardHeight(imageDp: Int, fontScale: Float) = imageDp + ceil(40 * fontScale).toInt() + 12
    fun shelfHeight(cardHeightDp: Int) = cardHeightDp + 2 * focusClearance(cardHeightDp)
    fun restoreFocus(previous: String?, available: List<String>): String? =
        previous?.takeIf { it in available } ?: available.firstOrNull()
}

data class DetailActions(val visible: List<String>, val overflow: List<String>) {
    companion object {
        fun forType(type: String) = when (type) {
            // Watched, favourite and downloaded sit beside Play as icons that show
            // their state; everything else is under More.
            "movie", "episode" -> DetailActions(listOf("play", "watched", "favorite", "download", "more"), listOf("restart", "options"))
            "series" -> DetailActions(listOf("play", "watched", "favorite", "download", "more"), emptyList())
            else -> DetailActions(emptyList(), emptyList())
        }
    }
}

/** Last known online metadata lets Offline use the same details without inventing series metadata. */
@Serializable
data class DetailSnapshot(
    val item: LibraryItem? = null,
    val target: SeriesPlayTargetResponse? = null,
    val targetRecordedAt: Long = 0L
)

object DetailSnapshotKey {
    fun of(baseUrl: String, userId: String, itemId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(baseUrl.trimEnd('/'), userId, itemId).joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

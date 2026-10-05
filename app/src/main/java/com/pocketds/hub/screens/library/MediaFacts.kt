package com.pocketds.hub.screens.library

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryMediaVersion
import com.pocketds.hub.state.Fmt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What a movie, series or episode page says under its tabs: who is in it, and
 * the facts (studio, director, the file) as short labelled values.
 */
object MediaFacts {
    data class Fact(val label: String, val value: String)
    data class CastMember(val id: String, val name: String, val role: String, val image: String, val tmdbId: Int = 0)

    private val actors = setOf("actor", "gueststar")

    fun cast(item: LibraryItem, limit: Int = 20): List<CastMember> = item.people
        .filter { it.type.lowercase(Locale.ROOT) in actors }
        .distinctBy { it.id.ifBlank { it.name } }
        .take(limit)
        .map { CastMember(it.id, it.name, it.role, it.image, it.tmdbId) }

    fun facts(item: LibraryItem): List<Fact> = buildList {
        fun people(vararg types: String) = item.people.filter { person -> types.any { it.equals(person.type, ignoreCase = true) } }
            .map { it.name }.distinct()
        people("Director").takeIf { it.isNotEmpty() }?.let { add(Fact("Directed by", it.joinToString(", "))) }
        people("Writer", "Screenwriter").takeIf { it.isNotEmpty() }?.let { add(Fact("Written by", it.take(4).joinToString(", "))) }
        if (item.studios.isNotEmpty()) add(Fact(if (item.studios.size == 1) "Studio" else "Studios", item.studios.take(3).joinToString(", ")))
        if (item.genres.isNotEmpty()) add(Fact("Genres", item.genres.joinToString(", ")))
        released(item.premiereDate)?.let { add(Fact(if (item.type == "series") "First aired" else "Released", it)) }
        listOfNotNull(
            item.officialRating.takeIf(String::isNotBlank),
            item.rating.takeIf { it > 0 }?.let { String.format(Locale.US, "★ %.1f", it) },
            item.criticRating.takeIf { it > 0 }?.let { String.format(Locale.US, "Critics %.0f%%", it) }
        ).takeIf { it.isNotEmpty() }?.let { add(Fact("Rated", it.joinToString(" · "))) }
        if (item.originalTitle.isNotBlank() && !item.originalTitle.equals(item.title, ignoreCase = true)) {
            add(Fact("Original title", item.originalTitle))
        }
        item.mediaVersions.forEachIndexed { index, version ->
            add(Fact(if (item.mediaVersions.size > 1) "Version ${index + 1}" else "File", file(version)))
        }
    }

    /** "MKV · 4.2 GB · 6.4 Mbps", then the picture, the first audio track and how many of each. */
    fun file(version: LibraryMediaVersion): String {
        val first = listOf(version.container.uppercase(Locale.ROOT), if (version.sizeBytes > 0) Fmt.bytes(version.sizeBytes) else "",
            Fmt.mbps(version.bitrate.toLong())).filter(String::isNotBlank).joinToString(" · ")
        val video = version.tracks.firstOrNull { it.type == "video" }?.let { track ->
            track.title.ifBlank { listOf(if (track.height > 0) "${track.height}p" else "", track.codec.uppercase(Locale.ROOT)).filter(String::isNotBlank).joinToString(" ") }
        }.orEmpty()
        val audio = version.tracks.filter { it.type == "audio" }
        val subtitles = version.tracks.count { it.type == "subtitle" }
        val second = listOf(
            video,
            when (audio.size) { 0 -> ""; 1 -> "1 audio track"; else -> "${audio.size} audio tracks" },
            when (subtitles) { 0 -> ""; 1 -> "1 subtitle"; else -> "$subtitles subtitles" }
        ).filter(String::isNotBlank).joinToString(" · ")
        return listOf(version.name.takeIf { it.isNotBlank() && version.name.length <= 48 }.orEmpty(), first, second)
            .filter(String::isNotBlank).joinToString("\n")
    }

    /** "24 Sep 2026" from Jellyfin's ISO timestamp; null when absent or unreadable. */
    fun released(premiereDate: String): String? = runCatching {
        LocalDate.parse(premiereDate.take(10)).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
    }.getOrNull()
}

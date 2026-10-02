package com.pocketds.hub.model

import com.pocketds.hub.ui.EpisodeLabel
import java.util.Locale

/**
 * A torrent's release name as words: "dark.matter.2024.s02e06.1080p.web.h264-
 * cakes[EZTVx.to].mkv" becomes "Dark Matter (2024) · S2E6 · 1080p".
 *
 * A finished transfer has left Sonarr's and Radarr's queues, so the hub has
 * no title for it, and All transfers listed scene names with dots in them.
 * Anything without an episode, season or year to anchor on is returned as it
 * came: a wrong guess is worse than the raw name.
 */
object ReleaseNames {
    private val EXTENSION = Regex("""\.(mkv|mp4|avi|m4v|ts|webm)$""", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("""\[[^\]]*]""")
    private val EPISODE = Regex("""(?i)^s(\d{1,2})e(\d{1,3})(?:-?e\d{1,3})*$""")
    private val SEASON = Regex("""(?i)^s(\d{1,2})$""")
    private val YEAR = Regex("""^\(?(19\d{2}|20\d{2})\)?$""")
    private val QUALITY = Regex("""(?i)^(\d{3,4}p)$""")

    fun readable(name: String): String {
        val words = name.trim()
            .replace(EXTENSION, "")
            .replace(BRACKETED, " ")
            .replace('.', ' ').replace('_', ' ')
            .split(' ').filter(String::isNotBlank)
        if (words.isEmpty()) return name
        val anchor = words.indexOfFirst { EPISODE.matches(it) || SEASON.matches(it) }.takeIf { it > 0 }
            // The last year, so "2001 A Space Odyssey 1968" keeps its title.
            ?: words.indexOfLast { YEAR.matches(it) }.takeIf { it > 0 }?.let { it + 1 }
            ?: return name
        val titleWords = words.take(anchor).toMutableList()
        // "Dark Matter 2024" names a remake by its year: "Dark Matter (2024)".
        val year = titleWords.lastOrNull()?.let(YEAR::matchEntire)?.groupValues?.get(1)
            ?.takeIf { titleWords.size > 1 }?.also { titleWords.removeAt(titleWords.lastIndex) }
        val title = titleWords.joinToString(" ") { word ->
            if (word.any(Char::isUpperCase)) word else word.replaceFirstChar { it.titlecase(Locale.ROOT) }
        } + (year?.let { " ($it)" } ?: "")
        val marker = words.getOrNull(anchor).orEmpty()
        val place = EPISODE.matchEntire(marker)?.let { EpisodeLabel.code(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
            ?: SEASON.matchEntire(marker)?.let { EpisodeLabel.season(it.groupValues[1].toInt()) }
        val quality = words.drop(anchor).firstNotNullOfOrNull { QUALITY.matchEntire(it)?.groupValues?.get(1)?.lowercase(Locale.ROOT) }
        return listOfNotNull(title, place, quality).joinToString(" · ")
    }
}

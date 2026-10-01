package com.pocketds.hub

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps one behaviour in one place.
 *
 * Each rule below is a copy that was removed because the copies had drifted
 * apart -- different numbering, a missing backoff, an untinted bar, a token
 * memory per client. The owner named in each message is where a change goes
 * instead. See "Shared building blocks" in CLAUDE.md.
 */
class ConsolidationGuardTest {

    private data class Rule(val pattern: Regex, val owner: String, val allowedIn: Set<String> = emptySet())

    private val rules = listOf(
        Rule(Regex("""HubClient\(\s*(this|context|applicationContext|this@\w+)\s*\)"""),
            "HubClient.shared(context): one client, one token memory, one HTTP cache",
            setOf("net/HubClient.kt")),
        Rule(Regex("""\?\.imageLoader\b"""),
            "Artwork.loader(api, context) / Artwork.bind", setOf("ui/Artwork.kt")),
        Rule(Regex("""ImageLoader\((context|host\.viewContext|card\.context|row\.context|view\.context)\)"""),
            "Artwork.loader(api, context)", setOf("ui/Artwork.kt")),
        Rule(Regex("""if \(\w[\w.]* == 0\) "Specials""""),
            "EpisodeLabel.season(number)", setOf("ui/EpisodeCardView.kt")),
        Rule(Regex(""""S\$\{?[\w.]+\}?E\$\{?"""),
            "EpisodeLabel.of / EpisodeLabel.code", setOf("ui/EpisodeCardView.kt", "model/Playback.kt")),
        Rule(Regex("""progressBarStyleHorizontal"""),
            "ProgressLine.create (or a stateful download row's own tint)",
            setOf("ui/ProgressLine.kt", "screens/downloads/DownloadRowView.kt",
                "screens/downloads/ReadingDownloadRowView.kt", "screens/settings/AppearanceScreen.kt",
                "screens/manage/ServerMonitorScreen.kt", "playback/SubtitleOffsetOverlay.kt",
                "reader/EpubReaderScreen.kt", "ui/SidePanelView.kt")),
        Rule(Regex(""""Selected" else"""),
            "ChoiceOverlay.pickValue, or Choice(selected = …): the panel draws the check mark",
            // The panel itself, saying "Selected" to a screen reader.
            setOf("ui/SidePanelView.kt")),
        Rule(Regex("""delay\(\w*POLL\w*\)"""),
            "state/Poller with a PollCadence: backoff on failure, stop when hidden"),
    )

    @Test
    fun `no screen brings back a copy of a shared behaviour`() {
        val root = File("src/main/java/com/pocketds/hub").takeIf { it.isDirectory }
            ?: File("app/src/main/java/com/pocketds/hub")
        assertTrue("source tree not found from ${File(".").absolutePath}", root.isDirectory)
        val violations = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            file.readLines().withIndex().flatMap { (index, line) ->
                rules.filter { relative !in it.allowedIn && it.pattern.containsMatchIn(line) }
                    .map { "$relative:${index + 1} -> use ${it.owner}\n    ${line.trim()}" }
            }
        }.toList()
        assertTrue("Copies of shared behaviour:\n" + violations.joinToString("\n"), violations.isEmpty())
    }
}

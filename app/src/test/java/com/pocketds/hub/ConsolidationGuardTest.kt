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
        Rule(Regex("""\.enqueue\(\s*(ImageRequest|request\b)|^\s*ImageRequest\.Builder\("""),
            "Artwork.bind: it clears a blank path and cancels the view's previous request",
            // Seek and page previews keep their Disposable to cancel a stale scrub;
            // the detail header's backdrop falls back to the poster when it fails.
            setOf("ui/Artwork.kt", "playback/PlayerScreen.kt", "reader/PagedImageReaderScreen.kt",
                "ui/DetailComponents.kt")),
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
        Rule(Regex("""FocusDecorator\.refresh\("""),
            "FocusDecorator.listen(view, ringVisible) { view, focused -> … }: it keeps the ring itself",
            // The overview box skips its own ring while its text scrolls.
            setOf("ui/FocusDecorator.kt", "ui/DetailComponents.kt")),
        Rule(Regex("""(?<![\w.])(?:android\.widget\.)?(?:Horizontal)?ScrollView\("""),
            "FocusScrollView / FocusHorizontalScrollView: a bare one is an invisible focus stop",
            // The overview box takes focus on purpose so its text scrolls; the
            // hint bar is a HorizontalScrollView subclass.
            setOf("ui/FocusScroll.kt", "ui/DetailComponents.kt", "nav/HintBarView.kt")),
        Rule(Regex("""\.played\)\s*0\.0|\.played\)\s*add\("Watched"\)|played -> "✓""""),
            "ResumeRules.showsWatched / watchLabel: a saved position wins over watched (a rewatch)"),
        Rule(Regex("""delay\(\w*POLL\w*\)"""),
            "state/Poller with a PollCadence: backoff on failure, stop when hidden"),
        Rule(Regex("""IntentFilter\(\s*OfflineRepository\.ACTION_CHANGED"""),
            "offline/OfflineChanges: start in onShow, stop in onHide; no registered flag to keep",
            setOf("offline/OfflineChanges.kt")),
        Rule(Regex("""(setTextColor\(|AppIconDrawable\([^,()]+,\s*)(this@\w+\.)?colors\.background\b"""),
            "PocketColors.inverseText for words and icons on a white pill: Glass's page colour paints nothing"),
        Rule(Regex("""StatusMessage\("(No |Nothing |This \w+ is empty)"""),
            "StatusText.notice(…): why a list is empty must still show on Glass, where a line shows only with news"),
        Rule(Regex("""GlassPage\.follow\([^)]*\)\s*\{\s*page\s*->\s*\w+\.retint\("""),
            "GlassPanelDrawable.attach(view, radius): the panel as the background, following the page",
            // The owner itself, a button's two faces, a capsule that draws its own glass,
            // a search field's focused and quiet faces, and the status chip drawn round words.
            setOf("ui/glass/GlassPanelDrawable.kt", "ui/glass/GlassButtonBackground.kt", "ui/BlobSegmentedView.kt",
                "ui/glass/GlassSearchField.kt", "ui/StatusLine.kt")),
        Rule(Regex("""0xE61C965C|0xF2CD8414"""),
            "DashboardParts.chip / releaseChip: one state chip for Upcoming, Activity and the transfers",
            setOf("ui/DashboardParts.kt")),
        Rule(Regex("""0x(B8|D1|A3)FFFFFF"""),
            "GlassColors.EYEBROW / FACTS / QUIET: the prototype's words on the page, by how loud they are",
            setOf("ui/glass/GlassColors.kt")),
        Rule(Regex("""0x47FFFFFF"""),
            "GlassProgressBar: how far in, a white bar on its track inside a picture",
            setOf("ui/glass/GlassProgressBar.kt")),
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

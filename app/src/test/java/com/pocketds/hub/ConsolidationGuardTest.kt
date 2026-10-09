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
            // Seek and page previews keep their Disposable to cancel a stale scrub.
            // (The comic reader's scrubber now shows the hub's thumbnail through Artwork.bind, #16.)
            setOf("ui/Artwork.kt", "playback/PlayerScreen.kt")),
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
        Rule(Regex(""""/v1/img/jf/"""),
            "HubEndpoints.jellyfinImage(id, type): one string per picture, so one entry in the colour caches",
            setOf("net/HubEndpoints.kt")),
        Rule(Regex("""\b(views|libraries|readingLibraries|libraryViews)\b[^\n]*\.(sorted|sortedBy|sortedWith|sortedByDescending|sortBy|sortWith|sortDescending)\b"""),
            "the hub's order (#15): libraries come arranged, or A to Z; move one with LibraryOrder.move and save it with LibraryOrderEditor"),
        Rule(Regex("""(percentage|progression)\b[^\n]*\*\s*100\b"""),
            "Fmt.readingPercent / readingPercentLabel: one rounding for how far through a book, so its facts and its Resume button agree",
            setOf("state/Fmt.kt")),
        Rule(Regex("""ringed\([^\n]*OVAL[^\n]*Color\.WHITE"""),
            "OverlayButtons.playFace: Play's white disc, the player's and the read-along dock's (#16)",
            setOf("ui/OverlayButtons.kt")),
        Rule(Regex("""PorterDuff\.Mode\.MULTIPLY"""),
            "ComfortLayerView with ScreenComfort: one warmth and one dim over a reader (#16, X3)",
            setOf("ui/ComfortLayerView.kt")),
        Rule(Regex("""\bLookSettings\b|\bThemeSettings\b|\bLook\.(GLASS|CLASSIC)\b|Theme\.(isGlass|onGlass|isDark|base)\(|\bglass\s*:\s*Boolean\b|"theme_mode""""),
            "one look (#20): the app is Glass alone; draw the glass face, with no look to ask, store or branch on",
            // The stored look and theme, removed once at launch.
            setOf("settings/RetiredSettings.kt")),
        Rule(Regex("""PlaybackService(\.|::)pause\b"""),
            "AudioHandoff: one sound at a time; each player reports started and stopped, and the others pause (#16, A1)",
            // The video player's own screen pausing its video, and the arbiter's hands.
            setOf("playback/PlayerScreen.kt", "reader/AudioHandoff.kt")),
        Rule(Regex("""isFocusedByDefault|setFocusedByDefault|restoreDefaultFocus|hasDefaultFocus|R\.id\.focus_place"""),
            "FocusPlace (#23): the place Back returns to is the host's, kept as Android's default focus and let go when it " +
                "leaves the window; a page that draws itself again carries it with FocusPlace.across (its views tagged) or marks " +
                "the new view with FocusPlace.mark, and asks for it first in requestInitialFocus with FocusPlace.focus",
            setOf("ui/FocusPlace.kt")),
        Rule(Regex("""PlaybackSelectBody\("""),
            "PlaybackRules.selection(plan, …): a change to what plays names the version playing, since Jellyfin applies an " +
                "audio or subtitle stream index only with its media source (#24)",
            setOf("model/Playback.kt", "playback/PlaybackRules.kt")),
        Rule(Regex("""/audio/(tracks|position)|/audio"|[?&]rev=|audio=omit"""),
            "HubEndpoints.readingAudioManifest / readingAudioTrack / readingAudioPosition and readingEpubFile(omitAudio = true), " +
                "through HubApi.readingAudioTrackUrl: a track URL carries the manifest's revision, and only the hub's builder knows the routes (#19)",
            setOf("net/HubEndpoints.kt")),
        Rule(Regex("""completeFile\([^)]*\)\.delete\(\)"""),
            "EpubPackageCache.remove(workId, sourceItemId): the ETag kept beside a book goes with its copy, or the next " +
                "opening is vouched for by a tag that belongs to other bytes (#41)",
            setOf("reader/EpubPackageCache.kt")),
        Rule(Regex("""\} left in (chapter|book)""""),
            "TimeLeft.label / chapterLabel / bookLabel: the menu's line and a page's corner say the time left in one set of words, " +
                "from the one pace (#42)",
            setOf("reader/ReadingPace.kt")),
        Rule(Regex("""maxOfOrNull \{ it\.pageCount \}|\(\w+(\.\w+)? \* pages\)\.toInt\(\)"""),
            "ReadingBookFacts.pages / ReadingBookFacts.page: a book's page count and its page, from how far through, in one place, so " +
                "its page, Resume and the reader's corner say the same page (#42)",
            setOf("screens/library/ReadingBookFacts.kt")),
        Rule(Regex("""FontFamily\.(SERIF|SANS_SERIF|MONOSPACE|CURSIVE)\b"""),
            "EpubFonts / EpubFontDeclarations.family: the reader's typefaces are the menu's three and the book's own; a generic " +
                "serif is Times, whose hairline strokes fade on a screen (#47)"),
        Rule(Regex("""pageMargins\s*=\s*value\.pageMargins"""),
            "PageGeometry.READIUM_MARGIN_FACTOR: Readium is told one margin and the preset chooses the page's side inset, or its " +
                "gutter moves and the gap between two columns is twice the margin again (#47)"),
        Rule(Regex("""0xff?fcf0d9|0xff?afafaf|0xff?c8c8c2""", RegexOption.IGNORE_CASE),
            "EpubPagePalette: the page colours of every theme, in one place (#47)",
            setOf("reader/EpubPagePalette.kt")),
        Rule(Regex("""is24HourFormat"""),
            "PageInfoView with PageInfo.clock: the time on a page's corner follows the device's 12 or 24 hour setting in one place (#42)",
            setOf("reader/PageInfoView.kt")),
        Rule(Regex("""get(Boolean|Float|String)\("(publisherStyles|textAlignment|lineHeight|hyphenation)",\s*+(?!defaults\.)"""),
            "EpubReaderPreferences()'s own defaults in EpubAppearanceStore.decode: one place for what a device with nothing " +
                "stored reads in (#42, Part 3)"),
        Rule(Regex("""getElementById\([^\n]*getBoundingClientRect|getBoundingClientRect[^\n]*getElementById"""),
            "ReadAlongPageProbe.script, read by ReadAlongPageSync: what a page shows of the narration is one script and one piece of " +
                "maths, so the voice turning the page, a page turned by hand and Play from the page all agree on where a page begins " +
                "and ends, in a sentence too (#49)",
            setOf("reader/ReadAlongPageProbe.kt")),
        Rule(Regex("""\bOfflineSelectionScreen\b"""),
            "the series page itself (#48): the corners of its episodes, the Download panel (SeriesDownloadPanel), select mode and " +
                "the storage bar are SeriesDownloads', and what each choice adds is SeriesDownloadChoices'; there is no page of its own to pick episodes on"),
        Rule(Regex("""OfflinePrepareBody\("""),
            "OfflineQueueing.queue: one way onto the persistent queue (a grant per item, the batch named once, the service started), " +
                "for a film, a quick tap, a season, a choice, select mode and Keep ready (#48)",
            setOf("offline/OfflineQueueing.kt", "model/Offline.kt")),
        Rule(Regex("""\bnarrationPill\b"""),
            "the dock alone: nothing floats over the page while the voice reads (#49); the corners show and the menu brings the dock"),
        Rule(Regex("""\bisComplete\([^)]*\)\s*\)\s*return\s+\w+\.completeFile"""),
            "EpubEdition.open: a book that is kept is asked about when it opens (If-None-Match against the ETag kept), " +
                "so a copy from before the hub rewrote font sizes is replaced rather than reused for ever (#41)"),
        Rule(Regex("""setCompoundDrawables\(AppIconDrawable\([^\n]*dp\(20\)"""),
            "OverlayButtons.iconDisc / setDiscIcon: a disc's face replaces a view's padding, so an icon is centred by padding set after " +
                "the face; the book reader's own, set before it, drew every icon 8dp left of centre (#53)",
            setOf("ui/OverlayButtons.kt")),
        Rule(Regex("""squareCover\s*=\s*[^\n]*ReadingType\.AUDIOBOOK|ReadingType\.AUDIOBOOK\)\s*(1f|CARD_DP)"""),
            "ReadingBookFacts.coverShape / formatMark, and ui/FormatMark for the mark: what a book's cover says of its formats (tall, " +
                "square, or tall with a small round mark) is decided once, for the grid, Home's rows, a series' row and an author's (#54)"),
        Rule(Regex("""\"on #\$"""),
            "SeriesFan.caption: \"6 books · on #6\" is made in one place, for Books Home's series and the library's Series view (#54)",
            setOf("screens/library/SeriesFan.kt")),
        Rule(Regex("""ThemeGradientDrawable\.oval\([^\n]*accent\)[^\n]*(?:CHECK|check)"""),
            "ui/FinishedTick: the accent's circle with its check on a cover, for a book in a series' row and a finished series' fan (#54)"),
        Rule(Regex("""\b(narrates|fragments|locate|find|sentenceAt)\([^\n]*\.href\??\.toString\(\)|\.href\??\.toString\(\)\??\.let\(\s*[\w.]+::(narrates|fragments|locate|find)"""),
            "Locator.document in EpubReaderScreen / DocumentPath.of(href): a page's file and the narration's are compared in one " +
                "spelling (decoded, NFC, no fragment); Readium's href is percent-encoded and the SMIL's is not, and a book with a " +
                "space or a bracket in a file name (Mistborn's) found no narration on any page (#61)"),
        Rule(Regex("""\bnavigator\??\.go\("""),
            "EpubReaderScreen.goTo (with AnchorJump): Readium's go(locator) to a place in another file lands on the file's top or its " +
                "end, not the place; a jump to one is made in two steps, the file and then the place (#59)"),
        Rule(Regex("""(?<![\w.])(java\.net\.)?URI\("""),
            "DocumentPath.resolve: a path inside a book is any string a zip entry can be named; java.net.URI refuses a space, " +
                "[ and ], and a book with such names (Mistborn's) could not be read along (#61)",
            setOf("playback/CastTransferPolicy.kt", "screens/downloads/ActivityDashboard.kt", "settings/AppearancePolicy.kt")),
        Rule(Regex("""Style\.Highlight\(colors\.accent"""),
            "ReadAlongGlow.wash: the sentence's tint is the accent let into the page, opaque and behind the words; the bare accent, " +
                "translucent and over them, washed the words out and spilled on the sentences round it (#52)"),
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

    /**
     * Kept on purpose although nothing in the app reaches it yet, each with the
     * plan that keeps it. Anything else nothing reaches is deleted with its tests.
     */
    private val keptDormant = mapOf(
        "SpreadPlanner" to "READER_IMPROVEMENTS.md: facing pages, for a screen wide enough to show two",
        "EpubPackageCachePolicy" to "READER_IMPROVEMENTS.md X5: the budget for the EPUB cache",
    )

    /**
     * A class or object that only its own tests reach (#22). ContentModeToggleView
     * outlived Classic (#20) that way, and old reader policies, a sort sheet's
     * state and the A1 placeholder grid had gone the same way before it: tested,
     * so they looked alive, while nothing in the app used them. A name counts as
     * reached when anything in main other than its declaration names it, the
     * manifest and resources included (`this@Name` inside itself does not count).
     */
    @Test
    fun `nothing in the app is reached only by its tests`() {
        val main = File("src/main").takeIf { it.isDirectory } ?: File("app/src/main")
        assertTrue("source tree not found from ${File(".").absolutePath}", main.isDirectory)
        val sources = main.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }.toList()
        val uses = HashMap<String, Int>()
        val word = Regex("""(?<!this@)\b[A-Za-z_]\w*""")
        sources.forEach { file -> word.findAll(file.readText()).forEach { uses.merge(it.value, 1, Int::plus) } }
        // Top level only: a nested or private class is its file's business, and the compiler flags an unused private one.
        val declaration = Regex("""^(?:(?:public|internal|data|sealed|enum|abstract|open|inline|value|annotation|fun)\s+)*(?:class|object|interface)\s+([A-Za-z_]\w*)""",
            RegexOption.MULTILINE)
        val unreached = sources.filter { it.extension == "kt" }.flatMap { file ->
            declaration.findAll(file.readText()).map { it.groupValues[1] }
                .filter { (uses[it] ?: 0) <= 1 && it !in keptDormant }
                .map { "${file.relativeTo(main).invariantSeparatorsPath}: $it" }
                .toList()
        }
        assertTrue("Reached only by tests, or by nothing: delete each with its tests, or keep it in keptDormant with " +
            "the plan that needs it\n" + unreached.joinToString("\n"), unreached.isEmpty())
    }
}

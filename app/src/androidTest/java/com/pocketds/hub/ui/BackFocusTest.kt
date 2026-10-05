package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.model.CalendarItem
import com.pocketds.hub.model.CalendarResponse
import com.pocketds.hub.model.CastMember
import com.pocketds.hub.model.HealthResponse
import com.pocketds.hub.model.LibraryEpisodesResponse
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibraryItemsResponse
import com.pocketds.hub.model.LibraryPerson
import com.pocketds.hub.model.LibrarySeasonsResponse
import com.pocketds.hub.model.MediaDetail
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.ReadingAuthor
import com.pocketds.hub.model.ReadingAuthorRef
import com.pocketds.hub.model.ReadingAuthorsResponse
import com.pocketds.hub.model.ReadingLibrariesResponse
import com.pocketds.hub.model.ReadingLibrary
import com.pocketds.hub.model.ReadingLibraryItemsResponse
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.SeriesPlayTargetResponse
import com.pocketds.hub.model.ServiceHealth
import com.pocketds.hub.nav.Screen
import com.pocketds.hub.net.HubApi
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.library.LibraryDetailScreen
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Back returns focus where you left it, page by page (#23): go in, go to a
 * second page, come Back, and focus is on what you left. Through [PageHarness],
 * the host's own way of changing pages, on fixtures only: nothing reaches a hub,
 * nothing plays, no book is opened and nothing is written.
 */
@RunWith(AndroidJUnit4::class)
class BackFocusTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    /** Opens [page], then for each target: focus it, go elsewhere, come back, and it has focus again. */
    private fun backReturnsTo(page: () -> Screen, vararg targets: Pair<String, (View) -> View?>) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(page()) }
            ins.waitForIdleSync()
            for ((name, find) in targets) {
                ins.runOnMainSync {
                    val target = checkNotNull(find(harness.stage)) { "$name is not on the page" }
                    assertTrue("$name takes focus", target.requestFocus())
                    harness.push(PageHarness.Elsewhere())
                }
                ins.waitForIdleSync()
                ins.runOnMainSync { assertTrue(harness.back()) }
                ins.waitForIdleSync()
                // Past what a page does a moment after it comes back: a title page reads its
                // title again after 450 ms, and drawing its cast again took the focus.
                Thread.sleep(SETTLE_MS)
                ins.waitForIdleSync()
                ins.runOnMainSync {
                    val target = checkNotNull(find(harness.stage)) { "$name is gone after Back" }
                    val focused = harness.stage.findFocus()
                    assertTrue("Back lands on $name, not on ${focused?.contentDescription ?: focused?.javaClass?.simpleName}", target.isFocused)
                }
            }
            assertTrue("nothing was played or opened: ${harness.asked}", harness.asked.isEmpty())
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
        }
    }

    private companion object {
        /** Longer than any page waits before reading itself again on coming back. */
        const val SETTLE_MS = 1_200L
    }

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    /** The shown view whose description starts with [words]. */
    private fun described(words: String): (View) -> View? = { root ->
        all(root).firstOrNull { it.isShown && it.contentDescription?.toString()?.startsWith(words) == true }
    }

    /** The shown view whose description contains [words]. */
    private fun mentioning(words: String): (View) -> View? = { root ->
        all(root).firstOrNull { it.isShown && it.contentDescription?.toString()?.contains(words) == true }
    }

    /** The first shown focusable inside the view tagged [tag]. */
    private fun tagged(tag: String): (View) -> View? = { root ->
        root.findViewWithTag<View>(tag)?.let(::all)?.firstOrNull { it.isShown && it.isFocusable }
    }

    /** Runs [block] on [side], then puts the side back as it was. */
    private fun on(side: ContentMode, block: () -> Unit) {
        val context = ins.targetContext
        val before = ContentModeSettings.get(context)
        ContentModeSettings.set(context, side)
        try { block() } finally { ContentModeSettings.set(context, before) }
    }

    private fun onMedia(block: () -> Unit) = on(ContentMode.MEDIA, block)
    private fun onBooks(block: () -> Unit) = on(ContentMode.BOOKS, block)

    // ------------------------------------------------------------------ titles

    private fun titleHub(): HubApi {
        val seasons = (1..4).map { LibraryItem(id = "season-$it", type = "season", title = "Season $it", seasonNumber = it) }
        val people = (1..6).map { LibraryPerson(id = "person-$it", name = "Actor $it", role = "Role $it", type = "Actor") }
        return FixtureHub.of(
            "libraryItem" to { _ -> HubResult.Ok(LibraryItemResponse(LibraryItem(id = "series", type = "series", title = "Fixture series",
                overview = "A synopsis.", people = people))) },
            "librarySeasons" to { _ -> HubResult.Ok(LibrarySeasonsResponse("series", seasons)) },
            "seriesPlayTarget" to { _ -> HubResult.Ok(SeriesPlayTargetResponse(item = LibraryItem(id = "season-1-episode-1", type = "episode",
                title = "Episode 1", seasonNumber = 1, indexNumber = 1))) },
            "librarySimilar" to { _ -> HubResult.Ok(LibraryItemsResponse()) },
            "libraryEpisodes" to { args ->
                val season = args.getOrNull(1) as? String ?: "season-1"
                val number = season.removePrefix("season-").toIntOrNull() ?: 1
                HubResult.Ok(LibraryEpisodesResponse("series", season, items = (1..6).map { n ->
                    LibraryItem(id = "$season-episode-$n", type = "episode", title = "Episode $n", seasonNumber = number, indexNumber = n,
                        runtimeSeconds = 2_400)
                }))
            }
        )
    }

    private fun hit(key: String, title: String, type: String = "movie") = com.pocketds.hub.model.SearchHit(
        media = MediaRef(key = key, type = type, title = title), jellyfinItemId = key, availability = "available")

    /** A title: a season, an episode of it, the third of its cast. */
    @Test fun aTitlePageKeepsTheSeasonTheEpisodeAndTheCastMember() {
        var cast: View? = null
        backReturnsTo(
            { LibraryDetailScreen(titleHub(), "series", "Fixture series", "series") { true } },
            "Season 3" to { root -> described("Season 3")(root) },
            "the fourth episode" to { root -> all(root).firstOrNull { it is EpisodeCardView && it.isShown && it.contentDescription?.contains("Episode 4") == true } },
            "the third of the cast" to { root ->
                // The Cast tab follows focus: focusing it shows the cast.
                if (cast == null) described("Cast")(root)?.requestFocus()
                described("Actor 3")(root)?.also { cast = it }
            }
        )
    }

    /**
     * A title you don't have (Discover's): the third of its cast. The page reads
     * the title again on coming back, and drew the cast again with it, so the
     * card in focus went a moment after Back.
     */
    @Test fun aTitleNotInTheLibraryKeepsTheCastMember() = onMedia {
        val detail = MediaDetail(media = MediaRef(key = "movie:1", type = "movie", title = "Fixture film"), overview = "A synopsis.",
            cast = (1..6).map { CastMember(id = it, name = "Actor $it", character = "Role $it") })
        val api = FixtureHub.of("mediaDetail" to { _ -> HubResult.Ok(detail) })
        backReturnsTo({ com.pocketds.hub.screens.discover.MediaDetailScreen(api, "movie:1", "Fixture film") { true } },
            "the third of the cast" to described("Actor 3"))
    }

    // ------------------------------------------------------------------ Media pages

    /** Home: the sixth poster of Recently added, below the row Home opens on. */
    @Test fun homeKeepsTheCardInItsRow() = onMedia {
        val rows = listOf(
            com.pocketds.hub.model.DiscoverRow(id = "nextup", title = "Next up", items = (1..3).map { hit("next-$it", "Show $it", "episode") }),
            com.pocketds.hub.model.DiscoverRow(id = "latest", title = "Recently added", items = (1..8).map { hit("film-$it", "Film $it") })
        )
        val api = FixtureHub.of("home" to { _ -> HubResult.Ok(com.pocketds.hub.model.HomeResponse(rows = rows)) })
        backReturnsTo({ com.pocketds.hub.screens.home.HomeScreen(api) { true } },
            "Film 6" to described("Film 6"))
    }

    private fun libraryViews() = (1..5).map {
        com.pocketds.hub.model.LibraryView(id = "view-$it", name = "Library $it", kind = "movies", total = 20)
    }

    /** The Library: its fourth library, then the ninth poster of a library's page. */
    @Test fun theLibraryKeepsItsTileAndItsPoster() = onMedia {
        val api = FixtureHub.of(
            "library" to { _ -> HubResult.Ok(com.pocketds.hub.model.LibraryResponse(views = libraryViews())) },
            "libraryItems" to { _ -> HubResult.Ok(LibraryItemsResponse(items = (1..20).map { hit("film-$it", "Film $it") }, total = 20)) }
        )
        backReturnsTo({ com.pocketds.hub.screens.library.LibraryScreen(api) { true } },
            "Library 4" to described("Library 4"))
        backReturnsTo({ com.pocketds.hub.screens.library.LibraryFolderScreen(api, libraryViews(), "view-2") { true } },
            "Film 9" to described("Film 9"))
    }

    /** Discover: the fourth poster of its second row. */
    @Test fun discoverKeepsTheCardInItsRow() = onMedia {
        val rows = (1..3).map { row ->
            com.pocketds.hub.model.DiscoverRow(id = "row-$row", title = "Row $row", items = (1..8).map { hit("r$row-$it", "Row $row title $it") })
        }
        val api = FixtureHub.of("discover" to { _ -> HubResult.Ok(com.pocketds.hub.model.DiscoverResponse(rows = rows)) })
        backReturnsTo({ com.pocketds.hub.screens.discover.DiscoverScreen(api) { true } },
            "the fourth of the second row" to described("Row 2 title 4"))
    }

    /**
     * Activity: a release in Upcoming and a service in Services, the second and
     * third columns. Coming back reads the calendar and the services again and
     * draws their rows anew.
     */
    @Test fun activityKeepsTheRowInItsCard() = onMedia {
        val today = LocalDate.now()
        val releases = (1..4).map { n ->
            CalendarItem(id = "release-$n", service = "radarr", media = MediaRef(key = "movie:$n", type = "movie", title = "Release $n"),
                date = today.plusDays(n.toLong()).toString(), releaseType = "Digital")
        }
        val services = listOf("jellyfin", "radarr", "sonarr", "bazarr").map { ServiceHealth(name = it, state = "up", version = "1.0") }
        val api = FixtureHub.of(
            "calendar" to { _ -> HubResult.Ok(CalendarResponse(items = releases)) },
            "health" to { _ -> HubResult.Ok(HealthResponse(services = services)) }
        )
        backReturnsTo({ com.pocketds.hub.screens.downloads.ActivityScreen(api) { true } },
            "the third release" to mentioning("Release 3,"),
            "Sonarr" to described("Sonarr,"))
    }

    /** Settings: a section in the list (it opens on focus), then a switch inside it. */
    @Test fun settingsKeepsTheSectionAndTheSwitch() {
        backReturnsTo({ com.pocketds.hub.screens.settings.SettingsScreen(null) { true } },
            "the Playback section" to { root -> all(root).firstOrNull { it.isShown && it.contentDescription?.toString() == "Playback" } },
            "Skip intros automatically" to tagged("autoskip"))
    }

    // ------------------------------------------------------------------ Books

    private fun book(n: Int) = ReadingSectionItem(sourceItemId = "source-$n", workId = "book-$n", title = "Fixture book $n",
        number = "$n", kind = "book")

    private val saga = ReadingWork(id = "saga", entityType = "collection", title = "Fixture saga", bookCount = 6,
        sections = listOf(ReadingSection(id = "books", title = "Books", items = (1..6).map(::book))))

    /** A series page: its third book. The page draws itself again on coming back. */
    @Test fun aSeriesPageKeepsTheBook() = onBooks {
        val api = FixtureHub.of("readingWork" to { _ -> HubResult.Ok(saga) })
        backReturnsTo({ ReadingWorkScreen(api, "saga", "Fixture saga", ringVisible = { true }) },
            "the third book" to described("Fixture book 3,"))
    }

    /** A book's page: its author's link, then the fifth book of its series under it. */
    @Test fun aBookPageKeepsTheLinkAndTheSeriesBook() = onBooks {
        val book = ReadingWork(id = "book-2", entityType = "work", title = "Fixture book 2", series = "Fixture saga", seriesIndex = 2.0,
            seriesId = "saga", authors = listOf("Ann Writer"), authorRefs = listOf(ReadingAuthorRef("author-1", "Ann Writer")),
            overview = "A synopsis.")
        val api = FixtureHub.of("readingWork" to { args -> HubResult.Ok(if (args[0] == "saga") saga else book) })
        backReturnsTo({ ReadingWorkScreen(api, "book-2", "Fixture book 2", ringVisible = { true }) },
            "the author's link" to described("Open author Ann Writer"),
            "the fifth book of the series" to described("Fixture book 5,"))
    }

    /**
     * A comic run: the sixth issue of its strip. The page draws itself again on
     * coming back, and the strip opened at the issue being read, while focus went
     * to the last button you had passed on the way down.
     */
    @Test fun aComicRunKeepsTheIssue() = onBooks {
        val issues = (1..12).map { n -> ReadingSectionItem(sourceItemId = "issue-$n", title = "$n", number = "$n", kind = "comic", pageCount = 24) }
        val run = ReadingWork(id = "run", entityType = "work", kind = "comic", title = "Fixture comic",
            sections = listOf(ReadingSection(id = "volume-1", title = "Volume 1", items = issues)))
        val api = FixtureHub.of("readingWork" to { _ -> HubResult.Ok(run) })
        backReturnsTo({ ReadingWorkScreen(api, "run", "Fixture comic", ringVisible = { true }) },
            "the sixth issue" to described("Issue 6,"))
    }

    private fun novels() = (1..8).map { n ->
        ReadingWork(id = "novel-$n", entityType = "work", kind = "book", title = "Novel $n", authors = listOf("Ann Writer"),
            libraryId = "books", addedAt = "2026-09-${10 + n}T10:00:00Z")
    }

    /** Books Home: the sixth cover of Recently added. */
    @Test fun booksHomeKeepsTheCoverInItsRow() = onBooks {
        val library = ReadingLibrary(id = "books", source = "storyteller", kind = "book", title = "Books", capabilities = listOf("sort:added"))
        val api = FixtureHub.of(
            "readingLibraries" to { _ -> HubResult.Ok(ReadingLibrariesResponse(libraries = listOf(library))) },
            "readingLibraryItems" to { _ -> HubResult.Ok(ReadingLibraryItemsResponse(libraryId = "books", items = novels(), total = 8, totalPages = 1)) }
        )
        backReturnsTo({ com.pocketds.hub.screens.home.HomeScreen(api) { true } },
            "Novel 6" to described("Novel 6,"))
    }

    /**
     * Books Home's continue card: Details, not Resume reading beside it, after
     * Back and after the app comes back. The two share the card's book, and
     * coming back to the app landed on Resume reading, where A opens the reader.
     */
    @Test fun booksHomeKeepsDetailsOnTheContinueCard() = onBooks {
        val library = ReadingLibrary(id = "books", source = "storyteller", kind = "book", title = "Books", capabilities = listOf("sort:added"))
        val reading = novels().first().copy(progress = com.pocketds.hub.model.ReadingProgress(percentage = 0.3, updatedAt = "2026-10-01T10:00:00Z"))
        val api = FixtureHub.of(
            "readingLibraries" to { _ -> HubResult.Ok(ReadingLibrariesResponse(libraries = listOf(library))) },
            "readingLibraryItems" to { _ ->
                HubResult.Ok(ReadingLibraryItemsResponse(libraryId = "books", items = listOf(reading) + novels().drop(1), total = 8, totalPages = 1))
            }
        )
        backReturnsTo({ com.pocketds.hub.screens.home.HomeScreen(api) { true } }, "Details" to described("Details for Novel 1"))
        // The app leaving and coming back: the host hides and shows the page, and settles nothing.
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(com.pocketds.hub.screens.home.HomeScreen(api) { true }) }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                assertTrue(checkNotNull(described("Details for Novel 1")(harness.stage)).requestFocus())
                harness.top().onHide(); harness.top().onShow()
            }
            ins.waitForIdleSync()
            ins.runOnMainSync {
                val focused = harness.stage.findFocus()
                assertTrue("back in the app on Details, not on ${focused?.contentDescription}",
                    focused?.contentDescription?.toString() == "Details for Novel 1")
            }
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
        }
    }

    /** An author's page: the fourth of their books. */
    @Test fun anAuthorPageKeepsTheBook() = onBooks {
        val books = (1..6).map { n -> ReadingWork(id = "novel-$n", entityType = "work", title = "Novel $n", authors = listOf("Ann Writer")) }
        val api = FixtureHub.of("readingAuthors" to { _ ->
            HubResult.Ok(ReadingAuthorsResponse(authors = listOf(ReadingAuthor(id = "author-1", name = "Ann Writer", items = books, totalPages = 1))))
        })
        backReturnsTo({ com.pocketds.hub.screens.library.ReadingAuthorScreen(api, "books", ReadingAuthor(id = "author-1", name = "Ann Writer")) { true } },
            "the fourth book" to described("Novel 4,"))
    }

    /** A Books library: the ninth cover of its grid, which is bound again on coming back. */
    @Test fun aBooksLibraryKeepsTheCover() = onBooks {
        val works = (1..20).map { n -> ReadingWork(id = "novel-$n", entityType = "work", title = "Novel $n", authors = listOf("Ann Writer")) }
        val api = FixtureHub.of("readingLibraryItems" to { _ ->
            HubResult.Ok(ReadingLibraryItemsResponse(libraryId = "books", items = works, total = 20, totalPages = 1))
        })
        backReturnsTo({ com.pocketds.hub.screens.library.ReadingLibraryGridScreen(api, ReadingLibrary(id = "books", title = "Books")) { true } },
            "the ninth cover" to described("Novel 9,"))
    }
}

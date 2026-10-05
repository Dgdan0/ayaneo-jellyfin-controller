package com.pocketds.hub.ui

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketds.hub.input.PadAction
import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.LibraryItemResponse
import com.pocketds.hub.model.LibraryItemsResponse
import com.pocketds.hub.model.LibraryPerson
import com.pocketds.hub.model.ReadingContinue
import com.pocketds.hub.model.ReadingSection
import com.pocketds.hub.model.ReadingSectionItem
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.net.HubResult
import com.pocketds.hub.screens.discover.PersonScreen
import com.pocketds.hub.screens.library.LibraryDetailScreen
import com.pocketds.hub.screens.library.ReadingWorkScreen
import com.pocketds.hub.settings.ContentModeSettings
import com.pocketds.hub.state.ContentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #26, on fixtures through [PageHarness]: Down from a Books series' continue
 * card lands on the book being read, else the first; a Library title's cast
 * portrait opens the performer's filmography when the hub knows their TMDB id,
 * and says why when it does not.
 */
@RunWith(AndroidJUnit4::class)
class CastAndContinueTest {
    private val ins = InstrumentationRegistry.getInstrumentation()

    private fun all(view: View): List<View> = listOf(view) + (view as? ViewGroup)?.let { group ->
        (0 until group.childCount).flatMap { all(group.getChildAt(it)) }
    }.orEmpty()

    private fun described(root: View, words: String): View =
        all(root).first { it.isShown && it.contentDescription?.toString()?.startsWith(words) == true }

    /** Opens [page] in the harness, runs [check] on the main thread, and closes it all. */
    private fun withPage(page: com.pocketds.hub.nav.Screen, check: (PageHarness) -> Unit) {
        val activity = ins.startActivitySync(Intent(ins.targetContext, DetailFixtureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var harness: PageHarness
        try {
            ins.runOnMainSync { harness = PageHarness(activity); harness.push(page) }
            ins.waitForIdleSync()
            ins.runOnMainSync { check(harness) }
        } finally {
            ins.runOnMainSync { harness.close(); activity.finish() }
        }
    }

    private fun onBooks(block: () -> Unit) {
        val context = ins.targetContext
        val before = ContentModeSettings.get(context)
        ContentModeSettings.set(context, ContentMode.BOOKS)
        try { block() } finally { ContentModeSettings.set(context, before) }
    }

    // ------------------------------------------------------------------ a Books series' continue card

    private fun book(n: Int) = ReadingSectionItem(sourceItemId = "source-$n", workId = "book-$n", title = "Fixture book $n",
        number = "$n", kind = "book")

    private fun saga(readingId: String) = ReadingWork(id = "saga", entityType = "collection", kind = "book", title = "Fixture saga",
        bookCount = 6, sections = listOf(ReadingSection(id = "books", title = "Books", items = (1..6).map(::book))),
        continueAt = ReadingContinue(workId = readingId, source = "storyteller", sourceItemId = "source-reading",
            title = "Fixture book being read", number = "4", percentage = 0.4, kind = "book"))

    /** The card spans the page over the strip: Down took the book under its middle (#4 of 6 on the Pocket). */
    @Test fun downFromASeriesContinueCardIsTheBookBeingRead() = onBooks {
        // Book 2, away from the card's middle, where Android's own search would go.
        val api = FixtureHub.of("readingWork" to { _ -> HubResult.Ok(saga("book-2")) })
        withPage(ReadingWorkScreen(api, "saga", "Fixture saga", ringVisible = { true })) { harness ->
            val card = all(harness.stage).filterIsInstance<ContinuationCardView>().single()
            assertTrue(card.requestFocus())
            assertSame("Down lands on the book being read", described(harness.stage, "Fixture book 2,"), card.focusSearch(View.FOCUS_DOWN))
        }
    }

    @Test fun downFromASeriesContinueCardIsTheFirstBookWhenTheOneBeingReadIsNotInTheRow() = onBooks {
        val api = FixtureHub.of("readingWork" to { _ -> HubResult.Ok(saga("book-elsewhere")) })
        withPage(ReadingWorkScreen(api, "saga", "Fixture saga", ringVisible = { true })) { harness ->
            val card = all(harness.stage).filterIsInstance<ContinuationCardView>().single()
            assertTrue(card.requestFocus())
            assertSame("Down lands on the first book", described(harness.stage, "Fixture book 1,"), card.focusSearch(View.FOCUS_DOWN))
        }
    }

    // ------------------------------------------------------------------ a Library title's cast

    /** A film whose third performer the hub knows on TMDB, and whose fourth it does not. */
    private fun film() = FixtureHub.of(
        "libraryItem" to { _ ->
            HubResult.Ok(LibraryItemResponse(LibraryItem(id = "film", type = "movie", title = "Fixture film", overview = "A synopsis.",
                people = (1..6).map { n ->
                    LibraryPerson(id = "person-$n", name = "Actor $n", role = "Role $n", type = "Actor", tmdbId = if (n == 4) 0 else 1000 + n)
                })))
        },
        "librarySimilar" to { _ -> HubResult.Ok(LibraryItemsResponse()) }
    )

    @Test fun aLibraryCastPortraitOpensTheFilmography() {
        withPage(LibraryDetailScreen(film(), "film", "Fixture film", "movie") { true }) { harness ->
            // The Cast tab follows focus: focusing it shows the cast.
            described(harness.stage, "Cast").requestFocus()
            val third = described(harness.stage, "Actor 3")
            assertTrue(third.requestFocus())
            val page = harness.top()
            assertTrue("A says what it does: ${page.hints()}",
                page.hints().any { it.action == PadAction.Activate && it.label == "Filmography" })
            third.performClick()
            val opened = harness.top()
            assertTrue("the performer's filmography opens, not $opened", opened is PersonScreen)
            assertEquals("Actor 3", opened.title)
        }
    }

    @Test fun aLibraryCastPortraitSaysWhyWhenTheHubDoesNotKnowThePerson() {
        withPage(LibraryDetailScreen(film(), "film", "Fixture film", "movie") { true }) { harness ->
            described(harness.stage, "Cast").requestFocus()
            val page = harness.top()
            val fourth = described(harness.stage, "Actor 4")
            assertTrue(fourth.requestFocus())
            fourth.performClick()
            assertSame("nothing opens", page, harness.top())
            assertTrue("it says why: ${harness.notices}", harness.notices.any { it.contains("Actor 4") && it.contains("TMDB") })
        }
    }
}

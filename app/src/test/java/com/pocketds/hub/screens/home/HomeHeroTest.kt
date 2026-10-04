package com.pocketds.hub.screens.home

import com.pocketds.hub.model.LibraryItem
import com.pocketds.hub.model.MediaRef
import com.pocketds.hub.model.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeHeroTest {
    private val seriesId = "8e950bfabe23bbfbb1a99ecccd1b954d"
    private val episodeId = "2d47480cfb56648c4c1dbae678660479"
    private val episode = SearchHit(
        media = MediaRef(type = "episode", title = "Drake & Josh", year = 2005,
            poster = "/v1/img/jf/$seriesId/Primary?tag=p", backdrop = "/v1/img/jf/$episodeId/Primary?tag=still"),
        subtitle = "S3E4 · Mindy's Back", rating = 8.0, jellyfinItemId = episodeId, progress = 0.2
    )

    @Test
    fun `an episode shows its series as the title and its code in the eyebrow straight from the card`() {
        val hero = HomeHero.from("continue", "Continue watching", episode)
        assertEquals("CONTINUE WATCHING · S3E4", hero.eyebrow)
        assertEquals("Drake & Josh", hero.title)
        assertEquals(listOf("Mindy's Back", "2005", "★ 8.0"), hero.meta)
        assertEquals("Resume", hero.playLabel)
        // The series' backdrop at once, from the series poster's id: no still first.
        assertEquals("/v1/img/jf/$seriesId/Backdrop", hero.backdrop)
    }

    @Test
    fun `an episode without numbers keeps its title out of the eyebrow`() {
        val hero = HomeHero.from("nextup", "Next up", episode.copy(subtitle = "בלאגן ועד אילת - פרק 6 "))
        assertEquals("NEXT UP", hero.eyebrow)
        assertEquals(listOf("בלאגן ועד אילת - פרק 6", "2005", "★ 8.0"), hero.meta)
    }

    @Test
    fun `details add the certification, runtime left and the series backdrop`() {
        val detail = LibraryItem(id = episodeId, type = "episode", title = "Mindy's Back", seriesId = seriesId, year = 2005,
            overview = "Science fair.", runtimeSeconds = 1500, officialRating = "TV-Y7")
        val hero = HomeHero.from("continue", "Continue watching", episode, detail)
        assertEquals(listOf("Mindy's Back", "2005", "TV-Y7", "25 min", "★ 8.0"), hero.meta)
        assertEquals("20 min left", hero.progressLabel)
        assertEquals("/v1/img/jf/$seriesId/Backdrop", hero.backdrop)
    }

    @Test
    fun `a new movie plays from the start and a library row names its library`() {
        val movie = SearchHit(media = MediaRef(type = "movie", title = "Iron Man 3", year = 2013, backdrop = "/b"), jellyfinItemId = "m")
        val hero = HomeHero.from("library:abc", "Marvel Movies", movie)
        assertEquals("MARVEL MOVIES", hero.eyebrow)
        assertEquals("Play", hero.playLabel)
        assertEquals(0.0, hero.progress, 0.0)
        assertEquals("/b", hero.backdrop)
    }

    @Test
    fun `a row asks for the page colours of the very artwork its hero will show`() {
        val movie = SearchHit(media = MediaRef(type = "movie", title = "Iron Man 3", poster = "/p"), jellyfinItemId = "m")
        val detail = LibraryItem(id = episodeId, type = "episode", seriesId = seriesId)
        for ((hit, item) in listOf(episode to null, episode to detail, movie to null, movie.copy(media = movie.media.copy(backdrop = "/b")) to null)) {
            assertEquals(HomeHero.from("latest", "Recently added", hit, item).backdrop, HomeHero.backdrop(hit, item))
        }
        // Nothing but a poster: the poster.
        assertEquals("/p", HomeHero.backdrop(movie))
    }

    @Test
    fun `an episode whose poster is its own still has no series to borrow from`() {
        val own = episode.copy(media = episode.media.copy(poster = "/v1/img/jf/$episodeId/Primary?tag=still"))
        assertEquals(null, HomeHero.seriesIdFromPoster(own.media.poster, episodeId))
        assertEquals("/v1/img/jf/$episodeId/Primary?tag=still", HomeHero.from("continue", "Continue watching", own).backdrop)
    }

    @Test
    fun `a finished episode is not offered as a resume`() {
        val watched = episode.copy(played = true, progress = 0.0)
        assertEquals("Play", HomeHero.from("nextup", "Next up", watched).playLabel)
        assertEquals("NEXT UP · S3E4", HomeHero.from("nextup", "Next up", watched).eyebrow)
    }
}

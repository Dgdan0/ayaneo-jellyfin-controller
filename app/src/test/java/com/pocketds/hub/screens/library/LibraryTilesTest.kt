package com.pocketds.hub.screens.library

import com.pocketds.hub.model.LibraryView
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryTilesTest {
    @Test
    fun `the fan puts the day's pick at the back on the right and the others leaning in front`() {
        val three = LibraryTiles.slots(3)
        assertEquals(listOf(0f, .26f, .52f), three.map { it.fromEnd })
        assertEquals(listOf(7f, -1f, -8f), three.map { it.degrees })
        // Marvel TV holds one title: a fan of one is the back poster alone.
        assertEquals(listOf(LibraryTiles.Slot(0f, 7f)), LibraryTiles.slots(1))
        assertEquals(2, LibraryTiles.slots(2).size)
        assertEquals(3, LibraryTiles.slots(7).size)
        assertEquals(0, LibraryTiles.slots(0).size)
    }

    @Test
    fun `a tile fans the hub's posters, and without them shows the library's own picture`() {
        val fanned = LibraryView(image = "/v1/img/jf/a/Primary", fan = listOf("/1", "/2", "/3", "/4"))
        assertEquals(listOf("/1", "/2", "/3"), LibraryTiles.fan(fanned))
        // A hub before #13 sends no fan; the picture is all there is, and no pages are fetched for more.
        assertEquals(emptyList<String>(), LibraryTiles.fan(LibraryView(image = "/v1/img/jf/a/Primary")))
        assertEquals(listOf("/1"), LibraryTiles.fan(LibraryView(fan = listOf("", "/1"))))
    }

    @Test
    fun `the root says how many libraries and titles there are`() {
        val views = listOf(
            LibraryView(name = "Anime", total = 15),
            LibraryView(name = "Marvel Movies", total = 39),
            LibraryView(name = "Marvel TV", total = 1),
            LibraryView(name = "Movies", total = 137),
            LibraryView(name = "Shows", total = 57)
        )
        assertEquals("5 libraries · 249 titles", LibraryTiles.summary(views))
        assertEquals("1 library · 1 title", LibraryTiles.summary(listOf(LibraryView(total = 1))))
        // An older hub does not count titles.
        assertEquals("2 libraries", LibraryTiles.summary(listOf(LibraryView(), LibraryView())))
    }

    @Test
    fun `the Books root says how many libraries and where they come from`() {
        val libraries = listOf(
            com.pocketds.hub.model.ReadingLibrary(id = "a", source = "kavita", kind = "manga"),
            com.pocketds.hub.model.ReadingLibrary(id = "b", source = "kavita", kind = "comic"),
            com.pocketds.hub.model.ReadingLibrary(id = "c", source = "storyteller", kind = "book")
        )
        assertEquals("3 libraries from Kavita and Storyteller, and Kavita's reading lists", LibraryTiles.readingSummary(libraries))
        assertEquals("1 library from Storyteller",
            LibraryTiles.readingSummary(listOf(com.pocketds.hub.model.ReadingLibrary(id = "c", source = "storyteller"))))
    }

    @Test
    fun `a reading library's kind is named as the prototype names it`() {
        assertEquals("Books & audio", LibraryTiles.readingKindLabel("book"))
        assertEquals("Comics", LibraryTiles.readingKindLabel("comic"))
        assertEquals("Manga", LibraryTiles.readingKindLabel("manga"))
        assertEquals("Kavita", LibraryTiles.readingKindLabel("reading_list"))
    }

    @Test
    fun `a library's kind is named in words`() {
        assertEquals("Movie library", LibraryTiles.kindLabel("movies"))
        assertEquals("TV library", LibraryTiles.kindLabel("tvshows"))
        assertEquals("Collections", LibraryTiles.kindLabel("boxsets"))
        assertEquals("Library", LibraryTiles.kindLabel(""))
        assertEquals("Library", LibraryTiles.kindLabel("mixed"))
    }
}

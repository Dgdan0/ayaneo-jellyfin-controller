package com.pocketds.hub.reader

import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubBookmarkStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val key = ReadingCheckpointKey("account/profile", "book", "edition", "epub")
    private fun locator(progression: Double, total: Double = progression) = Json.parseToJsonElement(
        """{"href":"chapter.xhtml","locations":{"progression":$progression,"totalProgression":$total},"title":"Chapter one"}"""
    ).jsonObject
    private fun store() = EpubBookmarkStore(directory.root)

    @Test fun `toggle survives reopening and repagination without duplicates`() {
        assertTrue(store().toggle(key, locator(0.25, 0.2)))
        assertEquals(1, store().list(key).size)
        assertTrue(store().contains(key, locator(0.25, 0.21)))
        assertFalse(store().toggle(key, locator(0.25, 0.21)))
        assertTrue(store().list(key).isEmpty())
        assertTrue(store().toggle(key, locator(0.25)))
        assertEquals(locator(0.25), store().list(key).single().locator)
    }

    @Test fun `account work and edition are isolated and deletion is durable`() {
        val s = store()
        s.toggle(key, locator(0.25))
        listOf(key.copy(scope = "other/profile"), key.copy(workId = "other"),
            key.copy(sourceItemId = "another-edition")).forEach { assertTrue(s.list(it).isEmpty()) }
        assertTrue(s.remove(key, s.list(key).single().anchor))
        assertTrue(store().list(key).isEmpty())
    }

    @Test fun `corrupt record fails rather than silently replacing bookmarks`() {
        Files.write(directory.root.toPath().resolve(key.fileName + ".bookmarks.json"), "broken".toByteArray())
        assertThrows(Exception::class.java) { store().toggle(key, locator(0.25)) }
    }
}

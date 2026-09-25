package com.pocketds.hub.reader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class DictionaryDefinition(val partOfSpeech: String, val text: String)
data class DictionaryEntry(val requested: String, val headword: String, val definitions: List<DictionaryDefinition>)

/** Versioned, local-only WordNet index. First installation copies the asset off the UI thread. */
class OfflineEnglishDictionary(context: Context) {
    private val app = context.applicationContext
    private val path = File(app.noBackupFilesDir, "dictionary/en-wordnet-2025.db")
    private val cache = object : LinkedHashMap<String, DictionaryEntry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DictionaryEntry>?) = size > 128
    }
    private var database: SQLiteDatabase? = null

    suspend fun lookup(selectedText: String): DictionaryEntry = withContext(Dispatchers.IO) {
        synchronized(this@OfflineEnglishDictionary) {
            cache[selectedText]?.let { return@synchronized it }
            val db = openDatabase()
            var matched = ""
            var definitions = emptyList<DictionaryDefinition>()
            for (candidate in DictionaryTerms.candidates(selectedText)) {
                val found = mutableListOf<DictionaryDefinition>()
                db.rawQuery("SELECT part_of_speech, definition FROM definitions WHERE lemma = ? ORDER BY rank LIMIT 6",
                    arrayOf(candidate)).use { cursor ->
                    while (cursor.moveToNext()) found += DictionaryDefinition(cursor.getString(0), cursor.getString(1))
                }
                if (found.isNotEmpty()) { matched = candidate; definitions = found; break }
            }
            DictionaryEntry(selectedText, matched, definitions).also { cache[selectedText] = it }
        }
    }

    @Synchronized private fun openDatabase(): SQLiteDatabase {
        database?.takeIf { it.isOpen }?.let { return it }
        path.parentFile?.mkdirs()
        if (!path.exists() || !valid(path)) {
            val temporary = File(path.parentFile, path.name + ".part")
            try {
                app.assets.open("dictionary/en-wordnet-2025.db").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                if (!valid(temporary)) error("Offline dictionary asset is invalid")
                if (path.exists()) path.delete()
                check(temporary.renameTo(path)) { "Could not install offline dictionary" }
            } finally { temporary.delete() }
        }
        return SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).also { database = it }
    }

    private fun valid(file: File): Boolean = try {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT value FROM metadata WHERE key = 'index_revision'", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0) == "2"
            }
        }
    } catch (_: Exception) { false }
}

package com.pocketds.hub.settings

import android.content.Context
import com.pocketds.hub.reader.ReadingPace

/**
 * How fast this person reads (#18, E3), kept on this device: per edition of a
 * book (its positions measure its own files, and an aligned edition's files
 * carry far more markup than the ebook's), and over every book, which a book
 * leans on until enough of it has been read.
 */
object ReadingPaceSettings {
    private const val PREFIX = "reading_pace:"
    private const val EVERY_BOOK = "reading_pace"

    fun book(context: Context, key: String): ReadingPace = ReadingPace.decode(Prefs.of(context).getString(PREFIX + key, null))

    /** The pace over every book, as minutes a position, leaning on the default until there is one. */
    fun prior(context: Context): Double = everyBook(context).minutesPerPosition()

    private fun everyBook(context: Context): ReadingPace = ReadingPace.decode(Prefs.of(context).getString(EVERY_BOOK, null))

    /** [reading] counted for this book and for every book; returns this book's pace now. */
    fun record(context: Context, key: String, book: ReadingPace, reading: ReadingPace.Reading): ReadingPace {
        val next = book.observe(reading)
        if (next == book) return book
        val all = everyBook(context).observe(reading)
        Prefs.of(context).edit().putString(PREFIX + key, next.encode()).putString(EVERY_BOOK, all.encode()).apply()
        return next
    }
}

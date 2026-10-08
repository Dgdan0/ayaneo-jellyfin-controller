package com.pocketds.hub.screens.library

import com.pocketds.hub.model.ReadingCommunity
import com.pocketds.hub.model.ReadingEdition
import com.pocketds.hub.model.ReadingWork
import com.pocketds.hub.model.ReadingYou
import com.pocketds.hub.model.ReadingYouPatch
import com.pocketds.hub.model.YouEdit
import com.pocketds.hub.state.FormRow
import com.pocketds.hub.state.Fmt
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The words of a book's page under the owner's layout "1" (#39), pure so they are tested:
 * what the facts line says, how readers rate it, its genres, and, under the cover, what this
 * profile says of it. The page draws these; it decides nothing.
 */
object ReadingBookPage {
    /**
     * Whether a work's page takes the new layout: a book of its own, not a series' page and not a
     * comic or manga (which have no formats to open and nothing of "you" to say yet).
     */
    fun isBook(work: ReadingWork): Boolean = work.entityType != "collection" && ReadingBookFacts.kindTag(work.kind) == null

    /**
     * The line under the author: "2006 · 541 pages · 24h 38m · 4.5 from readers". Where the book was
     * written, how long it reads and hears, and what readers think; any part with nothing to say drops.
     */
    fun facts(work: ReadingWork): String = buildList {
        work.year.takeIf { it > 0 }?.let { add(it.toString()) }
        ReadingBookFacts.pages(work).takeIf { it > 0 }?.let { add("$it pages") }
        work.editions.firstOrNull { it.kind == "audiobook" }?.let { audio ->
            Fmt.runtime(audio.durationMs / 1000).takeIf { it.isNotBlank() }?.let(::add)
        }
        community(work.community)?.let(::add)
    }.joinToString(" · ")

    /** "4.5 from readers": one decimal, rounded half up as a person would; null while nothing is known. */
    fun community(community: ReadingCommunity?): String? {
        val rating = community?.rating?.takeIf { it > 0 } ?: return null
        return BigDecimal(rating.toString()).setScale(1, RoundingMode.HALF_UP).toPlainString() + " from readers"
    }

    /** The genres as one quiet line: "Fantasy · Epic fantasy · Magic systems". */
    fun genres(work: ReadingWork): String = work.genres.map(String::trim).filter(String::isNotBlank).joinToString(" · ")

    /**
     * Under the cover, what this profile says of a book: "Finished Sep 2025", "2nd time", both as
     * "Finished Sep 2025 · 2nd time"; a finished book nobody has rated asks "· rate it?". Null when
     * there is nothing to say. [finished] says the page knows the book is finished (it was read to the
     * end, or marked), which counts as much as a date.
     */
    fun finished(you: ReadingYou?, finished: Boolean = false): String? {
        val month = monthLabel(you?.finished.orEmpty())
        val done = month != null || you?.status == "read" || finished
        val pieces = buildList {
            if (month != null) add("Finished $month") else if (done) add("Finished")
            you?.readCount?.takeIf { it >= 2 }?.let { add("${ordinal(it)} time") }
        }
        if (pieces.isEmpty()) return null
        return pieces.joinToString(" · ") + if (done && (you?.rating ?: 0) == 0) " · rate it?" else ""
    }

    /** Your shelves, in the accent: "cosmere · favorites". */
    fun shelves(you: ReadingYou?): String? =
        you?.shelves.orEmpty().map(String::trim).filter(String::isNotBlank).joinToString(" · ").ifBlank { null }

    /** "Sep 2025" from "2025-09"; null for anything that is not a month. */
    fun monthLabel(finished: String): String? {
        val month = parse(finished) ?: return null
        return month.month.getDisplayName(TextStyle.SHORT, Locale.US) + " " + month.year
    }

    /** "2nd", "3rd", "4th", "11th", "21st". */
    fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /** The hub's "YYYY-MM", or null. */
    fun parse(finished: String): YearMonth? = runCatching { YearMonth.parse(finished.trim()) }.getOrNull()
}

/** The five stars under a cover (#39), as one control: where its cursor is and what a choice does. */
object ReadingStars {
    const val COUNT = 5

    /** The rating after star [picked] is chosen while the rating is [current]: the same star again takes it away (null). */
    fun after(current: Int, picked: Int): Int? = picked.coerceIn(1, COUNT).takeIf { it != current }

    /** Where the cursor stands when focus arrives: on the rating, else the middle star. */
    fun cursor(current: Int): Int = if (current in 1..COUNT) current else (COUNT + 1) / 2

    fun step(cursor: Int, delta: Int): Int = (cursor + delta).coerceIn(1, COUNT)

    /** The star under a finger at [x] across [width]: equal fifths, the edges counted in. */
    fun starAt(x: Float, width: Float): Int =
        if (width <= 0f) 1 else ((x / width) * COUNT).toInt().coerceIn(0, COUNT - 1) + 1

    /** What Ⓐ does with the cursor on star [cursor] while the rating is [rating]. */
    fun hint(rating: Int, cursor: Int): String = when {
        cursor == rating -> "Remove rating"
        cursor == 1 -> "Rate 1 star"
        else -> "Rate $cursor stars"
    }

    /** What the control says for a screen reader. */
    fun description(rating: Int, cursor: Int? = null): String = when {
        cursor != null -> "Star $cursor of $COUNT" + if (rating in 1..COUNT) ", your rating is $rating" else ", not rated"
        rating in 1..COUNT -> "Your rating, $rating of $COUNT stars"
        else -> "Rate this book"
    }
}

/**
 * "When did you finish?" (#39): a month and a year, preset to this month, as the rows of a
 * [com.pocketds.hub.state.FormModel], and what they choose. The hub takes "YYYY-MM" from 1900-01 to
 * the current month.
 */
object ReadingFinished {
    const val MONTH = "month"
    const val YEAR = "year"
    const val CANCEL = "cancel"
    const val MARK = "mark"
    /** How far back the year row goes, which keeps it a row a pad can walk. */
    const val YEARS_BACK = 50

    private val MONTHS = Month.entries.map { it.getDisplayName(TextStyle.FULL, Locale.US) }

    /** Oldest first, so left is earlier. A year already kept for the book ([include]) is always among them. */
    fun years(now: YearMonth, include: Int = now.year): List<Int> =
        (maxOf(1900, minOf(now.year - YEARS_BACK, include))..now.year).toList()

    /** What the panel opens on: the month already kept for the book, else this one. */
    fun preset(you: ReadingYou?, now: YearMonth): YearMonth =
        ReadingBookPage.parse(you?.finished.orEmpty())?.takeIf { it <= now && it.year >= 1900 } ?: now

    fun rows(chosen: YearMonth, now: YearMonth): List<FormRow> {
        val years = years(now, chosen.year)
        return listOf(
            FormRow.Choice(MONTH, "Month", MONTHS, chosen.monthValue - 1),
            FormRow.Choice(YEAR, "Year", years.map(Int::toString), years.indexOf(chosen.year).coerceAtLeast(0)),
            FormRow.Action(CANCEL, "Cancel", quiet = true, icon = false),
            FormRow.Action(MARK, "Mark finished", icon = false)
        )
    }

    /** The month the rows say, a later one than this month counted as this month. */
    fun chosen(rows: List<FormRow>, now: YearMonth): YearMonth {
        val month = (rows.firstOrNull { it.id == MONTH } as? FormRow.Choice)?.selected ?: (now.monthValue - 1)
        val year = (rows.firstOrNull { it.id == YEAR } as? FormRow.Choice)?.value?.toIntOrNull() ?: now.year
        return YearMonth.of(year, month + 1).coerceAtMost(now)
    }

    /** Whether the rows name a month the hub would refuse, so they are drawn again as [chosen] says. */
    fun needsSettling(rows: List<FormRow>, now: YearMonth): Boolean {
        val month = (rows.firstOrNull { it.id == MONTH } as? FormRow.Choice)?.selected ?: return false
        val year = (rows.firstOrNull { it.id == YEAR } as? FormRow.Choice)?.value?.toIntOrNull() ?: return false
        return YearMonth.of(year, month + 1) > now
    }

    private fun YearMonth.coerceAtMost(max: YearMonth) = if (this > max) max else this
}

/** What each tap on a book's page writes under "you" (#39), as bodies of `PATCH …/works/{id}/you`. */
object ReadingYouEdits {
    /** A star chosen: [rating] 1 to 5, or null to take the rating away. */
    fun rate(rating: Int?): ReadingYouPatch =
        ReadingYouPatch(rating = if (rating == null) YouEdit.Clear else YouEdit.To(rating.coerceIn(1, ReadingStars.COUNT)))

    /**
     * "Mark finished" in [month]. The hub makes it read once when it was never read. A book the page
     * knows was read before and is not finished now ([finishedNow] false) is being read again, so its
     * count goes up; one still marked finished is a date put right, and its count stays.
     */
    fun finish(you: ReadingYou?, month: YearMonth, finishedNow: Boolean): ReadingYouPatch {
        val before = you != null && (you.finished.isNotBlank() || you.readCount > 0 || you.status == "read")
        return ReadingYouPatch(
            finished = YouEdit.To(month.toString()),
            readCount = if (before && !finishedNow) YouEdit.To((you!!.readCount.coerceAtLeast(1) + 1).coerceAtMost(MAX_READS)) else YouEdit.Keep
        )
    }

    /**
     * "Mark unread" after a finish: back to what the page had before it (the [before] kept while the page
     * was open), else the month and the count taken away. Null when there is nothing to put back.
     */
    fun unfinish(you: ReadingYou?, before: ReadingYou?): ReadingYouPatch? {
        val finished = before?.finished.orEmpty()
        val count = before?.readCount ?: 0
        val now = you ?: ReadingYou()
        if (now.finished == finished && now.readCount == count) return null
        return ReadingYouPatch(
            finished = if (finished.isBlank()) YouEdit.Clear else YouEdit.To(finished),
            readCount = if (count <= 0) YouEdit.Clear else YouEdit.To(count)
        )
    }

    private const val MAX_READS = 99
}

/**
 * The formats as a row of icon and name (#39, #49): Ebook, Audiobook, Read along, in the Books accent where
 * the book has them and quiet where it does not or one is still on its way. A ready one opens that
 * format at your place; a quiet one says why it cannot.
 */
object ReadingFormatChips {
    data class Chip(
        val kind: String,
        val label: String,
        val readiness: FormatReadiness,
        /** What opens it; null while it is not ready. */
        val choice: ReadingEntryChoice?,
        /** Said when a quiet chip is pressed. */
        val note: String
    ) {
        val ready: Boolean get() = readiness == FormatReadiness.READY && choice != null
    }

    private val ORDER = listOf("ebook", "audiobook", "readaloud")

    fun of(work: ReadingWork, remembered: ReadingEntryPreference?): List<Chip> {
        val statuses = ReadingFormatStatus.forWork(work).associateBy { it.kind }
        val audioId = remembered?.audioSourceItemId.orEmpty()
        fun choice(mode: ReadingEntryMode) = ReadingEntryChoice.choose(work, ReadingEntryPreference(mode, audioId))?.takeIf { it.mode == mode }
        return ORDER.mapNotNull { kind ->
            val status = statuses[kind] ?: return@mapNotNull null
            val choice = when (kind) {
                "audiobook" -> choice(ReadingEntryMode.LISTEN)
                "ebook" -> choice(ReadingEntryMode.READ)
                else -> choice(ReadingEntryMode.READ_ALONG)
            }.takeIf { status.readiness == FormatReadiness.READY }
            Chip(kind, status.label, status.readiness, choice, note(status))
        }
    }

    private fun note(status: ReadingFormatStatus): String = when (status.readiness) {
        FormatReadiness.READY -> ""
        FormatReadiness.PENDING -> if (status.kind == "readaloud") "Read along is still being aligned" else "${status.label} is on its way"
        FormatReadiness.MISSING -> "No ${status.label.lowercase()} for this book yet"
        FormatReadiness.UNKNOWN -> "${status.label} cannot be checked right now"
    }
}

/**
 * The words of the main button (#39): "Resume · Chapter 14 · 32%", where you are in the format it opens.
 * A book never started says what the format does, one finished offers it again.
 */
object ReadingResumeLabel {
    fun of(mode: ReadingEntryMode, progress: com.pocketds.hub.model.ReadingProgress?, chapter: String?, narration: String = ""): String {
        if (progress?.completed == true) return when (mode) {
            ReadingEntryMode.READ -> "Read again"
            ReadingEntryMode.LISTEN -> "Listen again"
            ReadingEntryMode.READ_ALONG -> "Read along again"
        }
        val started = progress != null && progress.percentage > 0
        // A reader picked from the menu is named until the book is started, as the choice it still is.
        if (!started) return when (mode) {
            ReadingEntryMode.READ -> "Read book"
            ReadingEntryMode.LISTEN -> listOf("Listen", narration).filter(String::isNotBlank).joinToString(" · ")
            ReadingEntryMode.READ_ALONG -> listOf("Read along", narration).filter(String::isNotBlank).joinToString(" · ")
        }
        return listOfNotNull("Resume", chapter?.takeIf(String::isNotBlank), Fmt.readingPercentLabel(progress!!.percentage)).joinToString(" · ")
    }

    /**
     * The chapter a text locator names, when it names one: Readium's `title`, never a file path, which
     * is all some books give.
     */
    fun chapter(locator: JsonObject?): String? =
        (locator?.get("title") as? JsonPrimitive)?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= 60 && '/' !in it && !it.contains(".htm", ignoreCase = true) && !it.contains(".xhtml", ignoreCase = true) }
}

/** The round ⋯ menu of a book's page (#39): what it offers, in order, for the state the book is in. */
object ReadingMoreMenu {
    const val FINISHED = "finished"
    const val UNREAD = "unread"
    const val NARRATION = "narration"
    const val WANT = "want"
    const val LISTS = "lists"
    const val OFFLINE = "offline-remove"
    const val SERVER = "server-remove"

    data class Entry(val id: String, val label: String, val detail: String = "", val danger: Boolean = false)

    /** [narrations]: the book has more than one reader of its audiobook (or of its read-along), so there is a choice to make. */
    fun entries(you: ReadingYou?, finished: Boolean, wanted: Boolean, narrations: Boolean = false): List<Entry> = buildList {
        add(Entry(FINISHED, "Finished", ReadingBookPage.monthLabel(you?.finished.orEmpty())?.let { "Finished $it · change the date" } ?: "Say when you finished it"))
        if (finished) add(Entry(UNREAD, "Mark unread", "Start again from the beginning"))
        if (narrations) add(Entry(NARRATION, "Choose narration", "Another reader of this book"))
        add(Entry(WANT, if (wanted) "Remove from Want to read" else "Want to read"))
        add(Entry(LISTS, "Add to a list"))
        add(Entry(OFFLINE, "Remove offline copy", "Only this device; keep server files and progress"))
        add(Entry(SERVER, "Delete from server…", "Review the files before confirming", danger = true))
    }
}

package com.pocketds.hub.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

object ReadingType {
    const val ALL = "all"
    const val EBOOK = "ebook"
    const val AUDIOBOOK = "audiobook"
    const val COMIC = "comic"
    const val MANGA = "manga"
    const val LIGHT_NOVEL = "light_novel"

    val filters = listOf(
        ALL to "All",
        EBOOK to "Ebooks",
        AUDIOBOOK to "Audiobooks",
        COMIC to "Comics",
        MANGA to "Manga",
        LIGHT_NOVEL to "Light novels"
    )

    fun label(wire: String): String = filters.firstOrNull { it.first == wire }?.second
        ?: wire.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Serializable
data class ReadingItem(
    val key: String = "",
    val contentType: String = "ebook",
    val title: String = "",
    val author: String = "",
    val year: Int = 0,
    val isbn: String = "",
    val source: String = "",
    val sourceId: String = "",
    val cover: String = "",
    val description: String = "",
    val inLibrary: Boolean = false,
    val actions: List<String> = emptyList()
) {
    val subtitle: String
        get() = buildList {
            if (author.isNotBlank()) add(author)
            if (year > 0) add(year.toString())
            if (isEmpty()) add(ReadingType.label(contentType))
        }.joinToString(" · ")
}

@Serializable
data class ReadingDiscoverRow(
    val id: String = "",
    val title: String = "",
    val meta: String = "",
    val contentType: String = "ebook",
    val page: Int = 1,
    val hasMore: Boolean = false,
    val items: List<ReadingItem> = emptyList()
)

@Serializable
data class ReadingDiscoverResponse(
    val rows: List<ReadingDiscoverRow> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingSearchResponse(
    val query: String = "",
    val contentType: String = ReadingType.ALL,
    val results: List<ReadingItem> = emptyList(),
    val broaderResults: List<ReadingItem> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingRequestMode(
    val id: String = "",
    val label: String = "",
    val requiresTotalBooks: Boolean = false,
    val requiresSeriesPreview: Boolean = false
)

@Serializable
data class ReadingQualityProfile(
    val id: Int = 0,
    val label: String = "",
    val default: Boolean = false,
    val preferCompleteBatches: Boolean = false
)

@Serializable
data class ReadingRequestOptions(
    val key: String = "",
    val contentType: String = "ebook",
    val title: String = "",
    val author: String = "",
    val modes: List<ReadingRequestMode> = emptyList(),
    val qualityProfiles: List<ReadingQualityProfile> = emptyList(),
    val monitoring: List<String> = emptyList()
) {
    val defaultProfileIndex: Int
        get() = qualityProfiles.indexOfFirst { it.default }.coerceAtLeast(0)
}

@Serializable
data class ReadingCreateRequestBody(
    val key: String,
    val mode: String,
    val totalBooks: Int = 0,
    val seriesId: String = "",
    val bookIds: List<String> = emptyList(),
    val qualityProfileId: Int,
    val monitoring: String = "all"
)

@Serializable
data class ReadingRequestResponse(
    val requestId: String = "",
    val seriesId: Int = 0,
    val parentSeriesId: Int = 0,
    val requested: Int = 0,
    val alreadyPresent: Int = 0,
    val failed: Int = 0,
    val state: String = "",
    val message: String = "",
    val targets: List<ReadingRequestTarget> = emptyList()
)

@Serializable
data class ReadingRequestTarget(val seriesId: Int = 0, val title: String = "")

@Serializable
data class ReadingRelease(
    val id: String = "",
    val title: String = "",
    val indexer: String = "",
    val sizeBytes: Long = 0,
    val seeders: Int = 0,
    val leechers: Int = 0,
    val score: Double = 0.0,
    val ownership: String = "none",
    val rejected: Boolean = false,
    val reason: String = "",
    val freeleech: Boolean = false,
    val format: String = "",
    val formatStatus: String = "unknown"
) {
    fun canGrab(): Boolean = formatStatus != "incompatible"
    fun formatLabel(): String = when (formatStatus) {
        "compatible" -> if (format.isBlank()) "Format matched" else "$format · matches request"
        "incompatible" -> if (format.isBlank()) "Wrong format" else "$format · wrong format"
        else -> "Format unverified"
    }
}

@Serializable
data class ReadingReleasesResponse(
    val seriesId: Int = 0,
    val releases: List<ReadingRelease> = emptyList(),
    val errors: List<String> = emptyList()
)

@Serializable
data class ReadingReleaseGrabBody(val id: String)

@Serializable
data class ReadingSeriesPreviewBook(
    val id: String = "",
    val title: String = "",
    val authorId: String = "",
    val author: String = "",
    val year: Int = 0,
    val isbn: String = "",
    val cover: String = "",
    val position: Int = 0,
    val inLibrary: Boolean = false,
    val selected: Boolean = true
)

@Serializable
data class ReadingSeriesPreview(
    val key: String = "",
    val seriesId: String = "",
    val name: String = "",
    val description: String = "",
    val authorId: String = "",
    val author: String = "",
    val authorImage: String = "",
    val ordering: String = "publication",
    val books: List<ReadingSeriesPreviewBook> = emptyList()
)

@Serializable
data class ReadingSeriesPreviewResponse(
    val key: String = "",
    val scopes: List<ReadingSeriesPreview> = emptyList()
)

@Serializable
data class ReadingDownloadItem(
    val id: String = "",
    val seriesId: Int = 0,
    val contentType: String = "",
    val title: String = "",
    val releaseTitle: String = "",
    val status: String = "",
    val progressPercent: Int = 0,
    val downloadSpeedBytesPerSecond: Long = 0,
    val etaSeconds: Long = 0,
    val sizeBytes: Long = 0,
    val addedAt: String = "",
    val completedAt: String = "",
    val importedAt: String = "",
    val failed: Boolean = false,
    val actions: List<String> = emptyList()
) {
    val progress: Double get() = progressPercent.coerceIn(0, 100) / 100.0
    val isActive: Boolean
        get() = status == "queued" || status == "downloading" || status == "importing" || status == "retrying"
    val availableActions: List<ReadingTransferAction>
        get() = actions.mapNotNull(ReadingTransferAction::fromWire)
}

enum class ReadingTransferAction(val wire: String) {
    RETRY("retry"),
    CANCEL("cancel");

    companion object {
        fun fromWire(value: String): ReadingTransferAction? = entries.firstOrNull { it.wire == value }
    }
}

@Serializable
data class ReadingDownloadsResponse(
    val items: List<ReadingDownloadItem> = emptyList()
) {
    val anyActive: Boolean get() = items.any { it.isActive }
}

@Serializable
data class ReadingLibrary(
    val id: String = "",
    val source: String = "",
    val kind: String = "book",
    val title: String = "",
    val artwork: String = "",
    val artworkStyle: String = "poster",
    val capabilities: List<String> = emptyList()
)

@Serializable
data class ReadingLibrariesResponse(
    val libraries: List<ReadingLibrary> = emptyList(),
    /** "name" (A to Z) or "custom" (the profile arranged them, #15). */
    val order: String = "name",
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingProgress(
    val percentage: Double = 0.0,
    val completed: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val updatedAt: String = ""
)

@Serializable
data class ReadingEdition(
    val id: String = "",
    val workId: String = "",
    val source: String = "",
    val sourceItemId: String = "",
    val kind: String = "book",
    val format: String = "",
    val identifiers: Map<String, String> = emptyMap(),
    val narrator: String = "",
    val pageCount: Int = 0,
    val durationMs: Long = 0,
    val availability: String = ""
)

@Serializable
data class ReadingSectionItem(
    val sourceItemId: String = "",
    val workId: String = "",
    val title: String = "",
    val number: String = "",
    val kind: String = "book",
    val artwork: String = "",
    val authors: List<String> = emptyList(),
    val pageCount: Int = 0,
    val progress: ReadingProgress? = null,
    val availability: String = "available",
    /** What this book can be opened as: ebook, audiobook, readaloud. One book, all its editions. */
    val formats: List<String> = emptyList(),
    /** This profile's reading status of the book (#63): want, reading, finished or not-reading; blank when the hub has nothing to say. */
    val status: String = ""
) {
    val isAvailable: Boolean get() = availability.equals("available", ignoreCase = true) && workId.isNotBlank()
}

@Serializable
data class ReadingSection(
    val id: String = "",
    val title: String = "",
    val number: Double = 0.0,
    val items: List<ReadingSectionItem> = emptyList()
)

@Serializable
data class ReadingContinue(
    val workId: String = "",
    val source: String = "",
    val sourceItemId: String = "",
    val title: String = "",
    val number: String = "",
    val percentage: Double = 0.0,
    val artwork: String = "",
    val kind: String = "book"
)

@Serializable
data class ReadingWork(
    val id: String = "",
    val libraryId: String = "",
    val entityType: String = "work",
    val kind: String = "book",
    val title: String = "",
    val sortTitle: String = "",
    val authors: List<String> = emptyList(),
    val series: String = "",
    val seriesIndex: Double = 0.0,
    /** A book's series page and author pages, sent with a book's own detail. */
    val seriesId: String = "",
    val authorRefs: List<ReadingAuthorRef> = emptyList(),
    val overview: String = "",
    val artwork: String = "",
    val genres: List<String> = emptyList(),
    val year: Int = 0,
    val addedAt: String = "",
    val bookCount: Int = 0,
    val languages: List<String> = emptyList(),
    val editions: List<ReadingEdition> = emptyList(),
    val progress: ReadingProgress? = null,
    /** This profile's reading status of the book (#63): want, reading, finished or not-reading; blank when the hub has nothing to say. See [com.pocketds.hub.screens.library.ReadingStatus]. */
    val status: String = "",
    val availability: List<String> = emptyList(),
    val sections: List<ReadingSection> = emptyList(),
    /** A series item's books in order for the library's fan (#54): the ones you have and the main ones you do not. */
    val seriesBooks: List<ReadingSeriesBook> = emptyList(),
    @SerialName("continue") val continueAt: ReadingContinue? = null,
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo(),
    /** How readers rate it (#39); absent when nothing is known. Only a book's own page carries it. */
    val community: ReadingCommunity? = null,
    /** What this profile has to say about it (#39); absent when there is nothing to say. */
    val you: ReadingYou? = null,
    /** When the hub last started this work over (its milliseconds), 0 when never (#60): older than this, a place kept here is gone. */
    val resetAt: Long = 0
) {
    val byline: String get() = authors.joinToString(", ")

    val subtitle: String
        get() = buildList {
            if (series.isNotBlank()) add(series)
            if (byline.isNotBlank()) add(byline)
            if (isEmpty() && year > 0) add(year.toString())
            if (isEmpty()) add(ReadingType.label(kind))
        }.joinToString(" · ")

    /** "6", "1.5", or empty outside a numbered series. */
    val seriesNumber: String
        get() = when {
            series.isBlank() || seriesIndex <= 0 -> ""
            seriesIndex % 1.0 == 0.0 -> seriesIndex.toLong().toString()
            else -> seriesIndex.toString()
        }

    /** Under a book's card: "Red Rising #6" inside a series, otherwise who wrote it. */
    val cardSubtitle: String
        get() = if (entityType != "collection" && seriesNumber.isNotBlank()) "$series #$seriesNumber"
            else byline.ifBlank { subtitle }
}

/**
 * A book of a series for the library's fan (#54). [state] is "read" for a book you have finished and "on" for the
 * one you are on (blank for the rest); [owned] false is a main numbered book you do not have, [released] false one
 * announced and not out (the hub does not list those), [kind] "audiobook" a book that is only an audiobook.
 */
@Serializable
data class ReadingSeriesBook(
    val number: String = "",
    val title: String = "",
    val cover: String = "",
    val kind: String = "book",
    val owned: Boolean = true,
    val released: Boolean = true,
    val state: String = ""
)

/** An author page a book links to. */
@Serializable
data class ReadingAuthorRef(val id: String = "", val name: String = "")

/**
 * How readers rate a book (#39): [rating] is 0 to 5, [count] how many rated it (0 when the
 * source does not say), [source] "hardcover", or "goodreads" (the average in the owner's
 * export) when Hardcover has nothing.
 */
@Serializable
data class ReadingCommunity(val rating: Double = 0.0, val count: Long = 0, val source: String = "")

/**
 * What this profile has to say about a book (#39), from the owner's Goodreads export and from
 * the apps. Nothing is "0" or blank here: [rating] 0 is not rated, [finished] is "YYYY-MM" or
 * blank, [readCount] 0 is unknown. [shelves] never holds the three statuses, which are
 * [status]: "read", "to-read" or "currently-reading". [source] is "app" once an app set or
 * cleared a rating, finish date or read count, else "goodreads".
 */
@Serializable
data class ReadingYou(
    val rating: Int = 0,
    val finished: String = "",
    val readCount: Int = 0,
    val shelves: List<String> = emptyList(),
    val status: String = "",
    val source: String = "",
    /** The reading status the person chose from an app (#63); blank when they chose none. It outranks everything the hub works out. */
    val chosen: String = ""
)

/** The answer to `PATCH /v1/reading/works/{id}/you`: what is left to say, null when nothing; [status] is the book's reading status now (#63). */
@Serializable
data class ReadingYouResponse(val workId: String = "", val you: ReadingYou? = null, val status: String = "")

/**
 * The answer to `POST /v1/reading/works/{id}/start-over` (#60): [resetAt] is the hub's stamp for it, which a device
 * compares with the last it applied, and [you] what this profile has of the book now (its finish taken away).
 */
@Serializable
data class ReadingStartOverResponse(
    val ok: Boolean = false,
    val action: String = "",
    val workId: String = "",
    val resetAt: Long = 0,
    val you: ReadingYou? = null
)

@Serializable
data class ReadingLibraryItemsResponse(
    val libraryId: String = "",
    val page: Int = 1,
    val pageSize: Int = 60,
    val total: Int = 0,
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
    val items: List<ReadingWork> = emptyList(),
    val partial: List<PartialFailure> = emptyList(),
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingPublicationPage(
    val index: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val isWide: Boolean = false
)

@Serializable
data class ReadingPublicationManifest(
    val workId: String = "",
    val source: String = "",
    val sourceItemId: String = "",
    val kind: String = "comic",
    val title: String = "",
    val seriesTitle: String = "",
    val number: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val direction: String = "ltr",
    val pages: List<ReadingPublicationPage> = emptyList(),
    val doublePairs: Map<String, Int> = emptyMap(),
    val previousSourceItemId: String = "",
    val nextSourceItemId: String = "",
    /** When the hub last started this series over, 0 when never (#60). */
    val resetAt: Long = 0
) {
    val positionLabel: String
        get() = if (pageCount <= 0) "" else "Page ${(currentPage + 1).coerceAtMost(pageCount)} of $pageCount"
}

@Serializable
data class ReadingPublicationProgressBody(val pageIndex: Int, val expectedPage: Int? = null,
    /** The start over of this series this device last knew of (#60); the hub refuses a page written from before one. */
    val resetSeen: Long? = null)

@Serializable
data class EpubPositionResponse(
    val workId: String = "",
    val sourceItemId: String = "",
    val locator: JsonObject? = null,
    val timestamp: Long = 0,
    val updatedAt: String = "",
    /** When the hub last started this book over, 0 when never (#60). */
    val resetAt: Long = 0
)

@Serializable
data class EpubPositionBody(
    val locator: JsonObject,
    val timestamp: Long,
    val checkBase: Boolean = false,
    val expectedLocator: JsonObject? = null,
    /** The start over of this book this device last knew of (#60); the hub refuses a place written from before one. */
    val resetSeen: Long? = null
)

/**
 * An audiobook as the hub streams it (#19, A3): its tracks in the order to
 * play them, the chapters inside them, and, on a book with a read-along
 * edition, which of that edition's audio files is which track. [revision]
 * names this list of files; every track URL carries it, and a stale one is
 * refused (412 audio_changed), so a rescan cannot play another file.
 */
@Serializable
data class ReadingAudioManifest(
    val workId: String = "",
    val sourceItemId: String = "",
    val revision: String = "",
    val narrator: String = "",
    val totalMs: Long = 0,
    val aligned: Boolean = false,
    val tracks: List<ReadingAudioTrack> = emptyList(),
    val chapters: List<ReadingAudioChapter> = emptyList(),
    val alignment: ReadingAudioAlignment? = null,
    val alignmentReason: String = "",
    val cache: CacheInfo = CacheInfo()
)

@Serializable
data class ReadingAudioTrack(
    /** The `{n}` of the track's route. */
    val index: Int = 0,
    /** Stays the same when the order changes, so a place can name the track. */
    val id: String = "",
    val title: String = "",
    val durationMs: Long = 0,
    val bytes: Long = 0,
    val mime: String = "",
    /** The strong validator the bytes are served with: a new file is a new tag. */
    val etag: String = ""
)

/**
 * A chapter of the audiobook: [startMs] counts from the start of that [track].
 * [source] says where it comes from (#31): "marks", a chapter mark inside a file,
 * or "book", an entry of the table of contents of the book's read-along edition,
 * placed where its narration starts, so one chapter can run on from one track
 * into the next. A book's chapters have one source; an older hub sends none,
 * and its chapters are marks. A title arrives dressed ("Chapter 1", "Prologue"):
 * it is shown as it comes.
 */
@Serializable
data class ReadingAudioChapter(val title: String = "", val startMs: Long = 0, val track: Int = 0, val source: String = "") {
    /** An entry of the book's own contents rather than a mark in a file. */
    val fromBook: Boolean get() = source == BOOK

    companion object {
        const val BOOK = "book"
    }
}

@Serializable
data class ReadingAudioAlignment(val audio: List<ReadingAlignedAudio> = emptyList())

/** A read-along edition's audio file ([href], a path inside the EPUB) and where in which track it begins. */
@Serializable
data class ReadingAlignedAudio(val href: String = "", val track: Int = 0, val startMs: Long = 0)

@Serializable
data class ReadingAudioPositionResponse(
    val workId: String = "",
    val sourceItemId: String = "",
    val position: ReadingAudioPosition? = null,
    /** When the hub last started this book over, 0 when never (#60). */
    val resetAt: Long = 0
)

/**
 * Where the book's listener is (#19, A4). [exact] is false for a proportion
 * of the whole, a guess from a reader's place in a book without alignment.
 * [timestamp] is the hub's own clock: never compare it with this device's.
 */
@Serializable
data class ReadingAudioPosition(
    val trackId: String = "",
    val track: Int = 0,
    val offsetMs: Long = 0,
    val globalMs: Long = 0,
    val completed: Boolean = false,
    val exact: Boolean = false,
    val form: String = "",
    val timestamp: Long = 0,
    val updatedAt: String = "",
    val sentence: ReadingAudioSentence? = null
)

@Serializable
data class ReadingAudioSentence(val href: String = "", val fragment: String = "")

@Serializable
data class ReadingAuthor(val id:String="",val name:String="",val artwork:String="",
    /** The author's shelf: "2 series", "6 books". */
    val seriesCount:Int=0,val bookCount:Int=0,val total:Int=0,
    val page:Int=1,val totalPages:Int=0,val items:List<ReadingWork> = emptyList())
@Serializable
data class ReadingAuthorsResponse(val authors:List<ReadingAuthor> = emptyList(),val page:Int=1,
    val total:Int=0,val totalPages:Int=0,val partial:List<PartialFailure> = emptyList(),val cache:CacheInfo=CacheInfo())
@Serializable
data class ReadingResolveResponse(val workId:String="",val resolved:Boolean=false)

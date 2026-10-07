import Foundation

/// The Books side's hub paths (#25): Android's `net/HubEndpoints` reading
/// part, with the same paths, query rules and encoding. Kept apart from the
/// media side's paths so the two sides grow without meeting in one file.
extension HubEndpoints {
    // MARK: Libraries and works

    /// One page of a library. Storyteller's books always come as series unless
    /// a view is named (Android: sorting by title used to ask for loose books,
    /// and Mistborn fell apart into three cards); `view: "works"` lists every
    /// book on its own.
    public static func readingLibraryItems(libraryId: String, page: Int = 1, sort: String = "title",
                                           direction: String = "asc", view: String = "") -> HubRequest {
        var path = "/v1/reading/libraries/" + encode(libraryId) + "/items?page=\(max(1, page))&sort=" + encode(sort)
            + "&direction=" + encode(direction)
        if !view.isEmpty {
            path += "&view=" + encode(view)
        } else if libraryId == storytellerBooks {
            path += "&view=collections"
        }
        return HubRequest(path)
    }

    /// Storyteller's one library.
    public static let storytellerBooks = "storyteller:books"

    /// A library's authors, or with `authorId` one author's shelf.
    public static func readingAuthors(libraryId: String, page: Int = 1, direction: String = "asc",
                                      authorId: String = "") -> HubRequest {
        HubRequest("/v1/reading/libraries/" + encode(libraryId) + "/authors?page=\(max(1, page))&direction="
            + encode(direction) + "&authorId=" + encode(authorId))
    }

    /// A book, a series or a comic run, with its editions and sections.
    public static func readingWork(_ workId: String) -> HubRequest {
        HubRequest("/v1/reading/works/" + encode(workId))
    }

    /// This profile's rating, finish date or read count for a book (#39).
    public static func readingYou(_ workId: String, _ change: ReadingYouChange) -> HubRequest {
        HubRequest("/v1/reading/works/" + encode(workId) + "/you", method: .patch, body: change.body())
    }

    /// The library's own work for a title Discover found, if it has one.
    public static func readingResolve(source: String, sourceId: String, isbn: String) -> HubRequest {
        HubRequest("/v1/reading/resolve?source=" + encode(source) + "&sourceId=" + encode(sourceId) + "&isbn=" + encode(isbn))
    }

    // MARK: Discover, search, requests and transfers (BookKeeprr)

    public static func readingDiscover(type: String) -> HubRequest {
        HubRequest("/v1/reading/discover?type=" + encode(type))
    }

    /// The next page of one row, of one kind.
    public static func readingDiscoverRow(_ rowId: String, type: String, page: Int) -> HubRequest {
        HubRequest("/v1/reading/discover/" + encode(rowId) + "?type=" + encode(type) + "&page=\(max(1, page))")
    }

    public static func readingSearch(_ query: String, type: String) -> HubRequest {
        HubRequest("/v1/reading/search?q=" + encode(query.trimmingCharacters(in: .whitespacesAndNewlines))
            + "&type=" + encode(type))
    }

    public static func readingRequestOptions(key: String) -> HubRequest {
        HubRequest("/v1/reading/requests/options?key=" + encode(key))
    }

    public static func readingSeriesPreview(key: String) -> HubRequest {
        HubRequest("/v1/reading/requests/series-preview?key=" + encode(key))
    }

    /// Never retried: a timeout does not mean it did not happen.
    public static func createReadingRequest(_ body: ReadingCreateRequestBody) -> HubRequest {
        HubRequest("/v1/reading/requests", method: .post, body: json(body))
    }

    /// The releases found so far for a request's series.
    public static func readingReleases(seriesId: Int) -> HubRequest {
        HubRequest("/v1/reading/requests/\(seriesId)/releases")
    }

    /// Asks every indexer again: slow.
    public static func searchReadingReleases(seriesId: Int) -> HubRequest {
        HubRequest("/v1/reading/requests/\(seriesId)/search", method: .post, body: Data("{}".utf8), slow: true)
    }

    public static func grabReadingRelease(seriesId: Int, releaseId: String) -> HubRequest {
        HubRequest("/v1/reading/requests/\(seriesId)/grab", method: .post, body: json(ReadingReleaseGrabBody(id: releaseId)))
    }

    public static let readingDownloads = HubRequest("/v1/reading/downloads")

    public static func retryReadingDownload(_ id: String) -> HubRequest {
        HubRequest("/v1/reading/downloads/" + encode(id) + "/retry", method: .post)
    }

    public static func cancelReadingDownload(_ id: String) -> HubRequest {
        HubRequest("/v1/reading/downloads/" + encode(id), method: .delete)
    }

    // MARK: Audiobooks (#19)

    /// An audiobook's tracks, chapters and read-along map.
    public static func readingAudioManifest(workId: String, sourceItemId: String) -> HubRequest {
        HubRequest(readingAudioPath(workId: workId, sourceItemId: sourceItemId))
    }

    /// One track's bytes, by Range: the manifest's `index` under its
    /// `revision`, which the hub checks so a rescan cannot play another file.
    /// A path for the player itself, which sends the bearer as a header.
    public static func readingAudioTrack(workId: String, sourceItemId: String, index: Int, revision: String) -> String {
        readingAudioPath(workId: workId, sourceItemId: sourceItemId) + "/tracks/\(max(0, index))?rev=" + encode(revision)
    }

    /// The listening place, read.
    public static func readingAudioPosition(workId: String, sourceItemId: String) -> HubRequest {
        HubRequest(readingAudioPath(workId: workId, sourceItemId: sourceItemId) + "/position")
    }

    /// The listening place, written: `body` is `AudioPlace.body`. Never
    /// retried; the outbox sends it again on its own terms.
    public static func saveReadingAudioPosition(workId: String, sourceItemId: String, body: Data) -> HubRequest {
        HubRequest(readingAudioPath(workId: workId, sourceItemId: sourceItemId) + "/position", method: .post, body: body)
    }

    private static func readingAudioPath(workId: String, sourceItemId: String) -> String {
        "/v1/reading/works/" + encode(workId) + "/publications/" + encode(sourceItemId) + "/audio"
    }

    // MARK: Pictures

    /// A comic issue's cover, from Kavita through the hub.
    public static func kavitaChapterCover(_ sourceItemId: String) -> String {
        "/v1/img/reading/kavita-chapter/" + encode(sourceItemId)
    }
}

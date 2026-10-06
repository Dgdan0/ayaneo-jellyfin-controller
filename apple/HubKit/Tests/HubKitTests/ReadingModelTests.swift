import Foundation
import Testing
@testable import HubKit

/// The Books side's responses as the app reads them (#25): the real hub's
/// answers, and Android's `ReadingTest` and `ReadingAudioManifestTest` cases.
struct ReadingModelTests {
    @Test func theLibrariesComeWithWhatEachCanDo() throws {
        let response = try ReadingVectors.decode(ReadingLibrariesResponse.self, ReadingVectors.libraries)
        #expect(response.libraries.map(\.id) == ["storyteller:books", "kavita:3", "kavita:2"])
        #expect(response.libraries.map(\.kind) == ["book", "manga", "comic"])
        #expect(response.libraries[0].capabilities.contains("sort:author"))
        #expect(!response.libraries[1].capabilities.contains("sort:author"))
        #expect(response.libraries[2].artworkStyle == "poster")
        #expect(response.order == "name")
    }

    @Test func aLibraryPageHoldsSeriesAndBooks() throws {
        let page = try ReadingVectors.decode(ReadingLibraryItemsResponse.self, ReadingVectors.storytellerLastRead)
        #expect(page.total == 6 && page.totalPages == 1 && !page.hasMore)
        #expect(page.items.map(\.entityType) == ["collection", "collection", "work", "collection", "collection", "work"])
        let redRising = page.items[1]
        #expect(redRising.isSeries && redRising.bookCount == 6)
        #expect(redRising.progress?.updatedAt == "2026-09-27 03:16:47")
        #expect(page.items[4].kind == "audiobook")
        #expect(page.items[5].availability == ["ebook", "audiobook", "readaloud"])
        #expect(page.cache.hit)
    }

    @Test func aSeriesPageHasItsBooksInOrderAndTheOneBeingRead() throws {
        let series = try ReadingVectors.decode(ReadingWork.self, ReadingVectors.redRising)
        let books = series.sections.flatMap(\.items)
        #expect(books.map(\.number) == ["1", "2", "3", "4", "5", "6"])
        #expect(books.allSatisfy { $0.isAvailable })
        #expect(books[5].pageCount == 735 && books[5].formats == ["ebook"])
        #expect(series.continueAt?.number == "6")
        #expect(series.continueAt?.workId == "rw_0468cfed987881e585e3d96afbbc15b5")
        #expect(series.authorRefs.first?.name == "Pierce Brown")
    }

    @Test func aBookHasItsThreeEditions() throws {
        let book = try ReadingVectors.decode(ReadingWork.self, ReadingVectors.darkMatter)
        #expect(book.editions.map(\.kind) == ["ebook", "audiobook", "readaloud"])
        #expect(Set(book.editions.map(\.sourceItemId)) == ["3726292328809367"])
        #expect(book.editions[1].durationMs == 36_538_680 && book.editions[1].narrator == "Jon Lindstrom")
        #expect(book.editions[0].pageCount == 246)
        #expect(!book.isSeries)
    }

    @Test func aKavitaRunWithoutAnEntityTypeIsAWork() throws {
        let run = try ReadingVectors.decode(ReadingWork.self, ReadingVectors.amazingAdultFantasy)
        #expect(run.entityType == "work" && run.kind == "comic")
        #expect(run.sections.first?.number == 1961)
        let issue = try #require(run.sections.first?.items.first)
        // An issue opens through its run: no work of its own, so not "available" as a page.
        #expect(issue.workId.isEmpty && !issue.isAvailable)
        #expect(issue.progress?.current == 4 && issue.progress?.total == 36)
        #expect(run.continueAt?.sourceItemId == "8338")
    }

    @Test func discoverRowsAndSearchKeepHubImagePaths() throws {
        let discover = try ReadingVectors.decode(ReadingDiscoverResponse.self, ReadingVectors.discoverRow)
        let item = try #require(discover.rows.first?.items.first)
        #expect(item.title == "Atomic Habits" && item.author == "James Clear" && item.year == 2016)
        #expect(item.cover.hasPrefix("/v1/img/reading/"))
        #expect(item.canRequest && !item.inLibrary)
        #expect(discover.rows[1].items.isEmpty)
        #expect(discover.rows[0].rowKey == "ebook:ebook-trending")
        #expect(discover.cache.stale)
        let search = try ReadingVectors.decode(ReadingSearchResponse.self, ReadingVectors.search)
        #expect(search.results.count == 2 && search.broaderResults.isEmpty)
        #expect(search.results[1].inLibrary && !search.results[1].canRequest)
        #expect(search.results[0].isbn == "9780575118560")
    }

    @Test func transfersSayWhatCanBeDone() throws {
        let downloads = try ReadingVectors.decode(ReadingDownloadsResponse.self, ReadingVectors.downloads)
        #expect(downloads.items[0].availableActions == [.retry, .cancel])
        #expect(downloads.items[0].failed && !downloads.items[0].isActive)
        #expect(downloads.items[1].availableActions.isEmpty)
        #expect(!downloads.anyActive)
    }

    @Test func readingTypeLabelsAreStableAndAnUnknownOneStaysReadable() {
        #expect(ReadingType.label("ebook") == "Ebooks")
        #expect(ReadingType.label("light_novel") == "Light novels")
        #expect(ReadingType.label("future_type") == "Future type")
    }

    @Test func aWorksSubtitleIsItsSeriesThenAuthorThenKind() {
        #expect(ReadingWork(title: "Golden Son", authors: ["Pierce Brown"], series: "Red Rising").subtitle
                == "Red Rising · Pierce Brown")
        #expect(ReadingWork(kind: "manga", title: "Lab Manga").subtitle == "Manga")
    }

    @Test func requestChoicesAndTransfersDecode() throws {
        let options = try ReadingVectors.decode(ReadingRequestOptions.self, #"""
        {"key":"reading:abc","contentType":"ebook","title":"Red Rising","author":"Pierce Brown","modes":[{"id":"single","label":"This book"},{"id":"series","label":"Choose books from series","requiresSeriesPreview":true}],"qualityProfiles":[{"id":7,"label":"English EPUB","default":true,"preferCompleteBatches":true}],"monitoring":["all","none"]}
        """#)
        #expect(options.modes.last?.id == "series" && options.modes.last?.requiresSeriesPreview == true)
        #expect(options.qualityProfiles.first?.id == 7 && options.qualityProfiles.first?.isDefault == true)
        #expect(options.defaultProfileIndex == 0)
        let transfers = try ReadingVectors.decode(ReadingDownloadsResponse.self, #"""
        {"items":[{"id":"rt_abc","seriesId":4,"contentType":"ebook","title":"Red Rising","releaseTitle":"Red Rising EPUB","status":"failed","progressPercent":25,"downloadSpeedBytesPerSecond":4096,"etaSeconds":90,"sizeBytes":12345,"failed":true,"actions":["retry","cancel"]}]}
        """#)
        #expect(transfers.items.first?.progress == 0.25)
    }

    /// The hub refuses unknown fields, and leaves out what is empty.
    @Test func aRequestBodySendsOnlyWhatTheHubKnows() throws {
        let plain = ReadingCreateRequestBody(key: "reading:abc", mode: "single", qualityProfileId: 7)
        let sent = try #require(String(data: HubEndpoints.json(plain), encoding: .utf8))
        #expect(sent == #"{"key":"reading:abc","mode":"single","monitoring":"none","qualityProfileId":7}"#)
        let series = ReadingCreateRequestBody(key: "reading:abc", mode: "series", seriesId: "OL100L", bookIds: ["OL1W", "OL3W"],
                                              qualityProfileId: 7, monitoring: "all")
        let both = try #require(String(data: HubEndpoints.json(series), encoding: .utf8))
        #expect(both == #"{"bookIds":["OL1W","OL3W"],"key":"reading:abc","mode":"series","monitoring":"all","qualityProfileId":7,"seriesId":"OL100L"}"#)
    }

    @Test func theAudiobookManifestReadsWhole() throws {
        let manifest = try ReadingVectors.decode(ReadingAudioManifest.self, ReadingVectors.darkMatterAudio)
        #expect(manifest.revision == "05c8b6c63e2b" && manifest.totalMs == 36_538_680 && manifest.aligned)
        #expect(manifest.tracks.first == ReadingAudioTrack(index: 0, id: "t_f87caef727ac", title: "Track 01/08",
                                                           durationMs: 4_610_652, bytes: 36_942_522, mime: "audio/mpeg",
                                                           etag: "\"00df47473c0726ce\""))
        #expect(manifest.alignment?.audio.map(\.track) == [0, 1])
        #expect(manifest.chapters.isEmpty)
        let plain = try ReadingVectors.decode(ReadingAudioManifest.self,
                                              #"{"revision":"05c8b6c63e2b","aligned":false,"tracks":[],"chapters":[]}"#)
        #expect(plain.alignment == nil && plain.alignmentReason.isEmpty)
        let refused = try ReadingVectors.decode(ReadingAudioManifest.self, #"{"aligned":false,"alignmentReason":"lengths_ambiguous"}"#)
        #expect(refused.alignmentReason == "lengths_ambiguous")
    }

    @Test func aPlaceReadsWithOrWithoutItsSentenceAndNoneIsNil() throws {
        let place = try #require(try ReadingVectors.decode(ReadingAudioPositionResponse.self, ReadingVectors.darkMatterPosition).position)
        #expect(place.offsetMs == 153_560 && place.exact && place.form == "text")
        #expect(place.sentence == ReadingAudioSentence(href: "text/part0005.html", fragment: "id34-s33"))
        #expect(try ReadingVectors.decode(ReadingAudioPositionResponse.self, #"{"workId":"rw_1560","position":null}"#).position == nil)
        let estimate = try #require(try ReadingVectors.decode(ReadingAudioPositionResponse.self,
            #"{"position":{"trackId":"t_aaaaaaaaaaaa","track":1,"offsetMs":5,"exact":false,"form":"text"}}"#).position)
        #expect(!estimate.exact && estimate.sentence == nil)
    }

    @Test func readingPathsAreAndroidsOwn() {
        #expect(HubEndpoints.readingLibraryItems(libraryId: "storyteller:books", sort: "last_read", direction: "desc").path
                == "/v1/reading/libraries/storyteller%3Abooks/items?page=1&sort=last_read&direction=desc&view=collections")
        #expect(HubEndpoints.readingLibraryItems(libraryId: "storyteller:books", page: 2, view: "works").path
                == "/v1/reading/libraries/storyteller%3Abooks/items?page=2&sort=title&direction=asc&view=works")
        #expect(HubEndpoints.readingLibraryItems(libraryId: "kavita:2", sort: "added", direction: "desc").path
                == "/v1/reading/libraries/kavita%3A2/items?page=1&sort=added&direction=desc")
        #expect(HubEndpoints.readingAuthors(libraryId: "storyteller:books", authorId: "ra_1").path
                == "/v1/reading/libraries/storyteller%3Abooks/authors?page=1&direction=asc&authorId=ra_1")
        #expect(HubEndpoints.readingWork("rw_1").path == "/v1/reading/works/rw_1")
        #expect(HubEndpoints.readingDiscover(type: "all").path == "/v1/reading/discover?type=all")
        #expect(HubEndpoints.readingDiscoverRow("trending", type: "manga", page: 2).path
                == "/v1/reading/discover/trending?type=manga&page=2")
        #expect(HubEndpoints.readingSearch(" red rising ", type: "ebook").path == "/v1/reading/search?q=red%20rising&type=ebook")
        #expect(HubEndpoints.readingResolve(source: "openlibrary", sourceId: "OL1W", isbn: "").path
                == "/v1/reading/resolve?source=openlibrary&sourceId=OL1W&isbn=")
        #expect(HubEndpoints.readingDownloads.path == "/v1/reading/downloads")
        #expect(HubEndpoints.cancelReadingDownload("rt_1").method == .delete)
        #expect(HubEndpoints.retryReadingDownload("rt_1").path == "/v1/reading/downloads/rt_1/retry")
        #expect(HubEndpoints.searchReadingReleases(seriesId: 28).slow)
        #expect(HubEndpoints.readingAudioManifest(workId: "rw_1", sourceItemId: "37").path
                == "/v1/reading/works/rw_1/publications/37/audio")
        #expect(HubEndpoints.readingAudioTrack(workId: "rw_1", sourceItemId: "37", index: 3, revision: "05c8b6c63e2b")
                == "/v1/reading/works/rw_1/publications/37/audio/tracks/3?rev=05c8b6c63e2b")
        #expect(HubEndpoints.readingAudioPosition(workId: "rw_1", sourceItemId: "37").path
                == "/v1/reading/works/rw_1/publications/37/audio/position")
        #expect(!HubEndpoints.saveReadingAudioPosition(workId: "rw_1", sourceItemId: "37", body: Data()).idempotent)
        #expect(HubEndpoints.kavitaChapterCover("8338") == "/v1/img/reading/kavita-chapter/8338")
    }
}

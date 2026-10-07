import Foundation
import Testing
@testable import HubKit

/// Library upkeep (#34): what the Library's pages offer, the words of the
/// subtitle and deletion pages, their routes, and the demo hub's answers, which
/// keep the hub's rules (a ticket per candidate, spent by one download; a
/// preview first and a one-use ticket to confirm a deletion).
struct LibraryUpkeepRulesTests {
    private func item(_ type: String, key: String = "") -> LibraryItem { LibraryItem(id: "i", type: type, title: "T", mediaKey: key) }

    @Test func subtitlesAreForFilmsAndEpisodesAndReleasesForSeriesTheHubCanName() {
        #expect(LibraryUpkeep.offersSubtitles(item("movie")) && LibraryUpkeep.offersSubtitles(item("episode")))
        #expect(!LibraryUpkeep.offersSubtitles(item("series")) && !LibraryUpkeep.offersSubtitles(item("season")))
        #expect(LibraryUpkeep.offersReleases(item("series", key: "tmdb:series:1")) && LibraryUpkeep.offersReleases(item("series")))
        #expect(!LibraryUpkeep.offersReleases(item("movie", key: "tmdb:movie:1")))
        #expect(LibraryUpkeep.releaseKey(item("series", key: "tmdb:series:1")) == "tmdb:series:1")
        #expect(LibraryUpkeep.releaseKey(item("series")) == nil, "no TMDB match, no safe link to Sonarr")
        #expect(LibraryUpkeep.noMatchWords.hasPrefix("This series has no TMDB match"))
        #expect(["movie", "series", "season", "episode"].allSatisfy { LibraryUpkeep.offersDeleting(item($0)) })
        #expect(!LibraryUpkeep.offersDeleting(item("person")))
        #expect(LibraryUpkeep.episodeHeading(series: "Last Seen", season: 1, episode: 4, title: "Gone") == "Last Seen · S1E4 · Gone")
        let episode = LibraryItem(id: "e", type: "episode", title: "Gone", seriesTitle: "Last Seen", indexNumber: 4, seasonNumber: 1)
        #expect(LibraryUpkeep.pageTitle(episode) == "Last Seen · S1E4 · Gone")
        #expect(LibraryUpkeep.pageTitle(LibraryItem(id: "e", type: "episode", title: "Gone")) == "Gone", "no series, no heading")
        #expect(LibraryUpkeep.pageTitle(item("movie")) == "T")
    }

    @Test func aSeriesItemCarriesItsMediaKeyAndAnOlderHubsHasNone() throws {
        let with = try JSONDecoder().decode(LibraryItem.self, from: Data(#"{"id":"a","type":"series","title":"S","mediaKey":"tmdb:series:9"}"#.utf8))
        #expect(with.mediaKey == "tmdb:series:9")
        let without = try JSONDecoder().decode(LibraryItem.self, from: Data(#"{"id":"a","type":"series","title":"S"}"#.utf8))
        #expect(without.mediaKey.isEmpty)
    }

    @Test func theRoutesAreTheHubsAndEveryPostIsSentOnce() throws {
        #expect(HubEndpoints.subtitles(itemId: "abc").path == "/v1/library/items/abc/subtitles")
        let search = HubEndpoints.searchSubtitles(itemId: "abc")
        #expect(search.path == "/v1/library/items/abc/subtitles/search" && search.method == .post && search.slow && !search.idempotent)
        let download = HubEndpoints.downloadSubtitle(itemId: "abc", ticket: "t1")
        #expect(download.path == "/v1/library/items/abc/subtitles/download" && download.method == .post && download.slow)
        #expect(String(decoding: try #require(download.body), as: UTF8.self) == #"{"ticket":"t1"}"#)
        #expect(HubEndpoints.refreshSubtitles(itemId: "abc").path == "/v1/library/items/abc/subtitles/refresh")
        let preview = HubEndpoints.removalPreview(kind: "video", id: "abc")
        #expect(preview.path == "/v1/media/removal-preview" && preview.method == .post && !preview.idempotent)
        #expect(String(decoding: try #require(preview.body), as: UTF8.self) == #"{"id":"abc","kind":"video"}"#)
        let remove = HubEndpoints.removeMedia(ticket: "tt")
        #expect(remove.path == "/v1/media/remove" && remove.method == .post && !remove.idempotent)
        #expect(String(decoding: try #require(remove.body), as: UTF8.self) == #"{"confirm":true,"ticket":"tt"}"#)
    }

    @Test func aLanguageIsNamedInEnglishAndAnUnknownOneStandsForItself() {
        #expect(SubtitleLines.language("he") == "Hebrew" && SubtitleLines.language("en") == "English")
        #expect(SubtitleLines.language("pt-BR").hasPrefix("Portuguese"))
        #expect(SubtitleLines.language("zzzz") == "zzzz")
        #expect(SubtitleLines.flags(forced: true, hi: true) == " · Forced · SDH")
        #expect(SubtitleLines.flags(forced: false, hi: false).isEmpty)
    }

    @Test func aCandidateAndATrackReadAsAndroidsDo() {
        let candidate = SubtitleCandidate(ticket: "t", language: "he", provider: "OpenSubtitles", score: 93.7, release: "WEB-DL",
                                          matches: ["Series"], mismatches: [], forced: false, hi: true)
        #expect(SubtitleLines.candidateTitle(candidate) == "Hebrew · SDH · 93% match · OpenSubtitles")
        #expect(SubtitleLines.candidateHeading(candidate) == "Hebrew · 93% match")
        #expect(SubtitleLines.downloadDetail(candidate) == "Hebrew · SDH · OpenSubtitles")
        #expect(SubtitleLines.release(candidate) == "WEB-DL" && SubtitleLines.release(SubtitleCandidate(ticket: "x")) == "Release details unavailable")
        #expect(SubtitleLines.matches(candidate) == "Series" && SubtitleLines.mismatches(candidate) == "None reported")
        #expect(SubtitleLines.matches(SubtitleCandidate(ticket: "x")) == "Not reported")
        let embedded = SubtitleRecord(id: "e", language: "English", installed: true, embedded: true)
        let external = SubtitleRecord(id: "x", language: "Hebrew", provider: "Subscene", score: "88%", installed: true, forced: true)
        #expect(SubtitleLines.recordTitle(embedded) == "English · Embedded")
        #expect(SubtitleLines.recordTitle(external) == "Hebrew · Forced · Subscene")
        #expect(SubtitleLines.recordTitle(SubtitleRecord(id: "y", language: "Arabic")) == "Arabic · External")
        #expect(SubtitleLines.recordScore(external) == "88% match" && SubtitleLines.recordScore(embedded) == "Match score unavailable")
        #expect(SubtitleLines.library(installed: 1) == "Library · 1 installed subtitle track")
        #expect(SubtitleLines.recordDetail(external).isEmpty)
        #expect(SubtitleLines.recordDetail(SubtitleRecord(id: "z", date: "2026-10-01", description: "WEB-DL")) == "2026-10-01 · WEB-DL")
        #expect(SubtitleLines.library(installed: 2) == "Library · 2 installed subtitle tracks")
    }

    @Test func theLanguageFilterListsWhatWasFoundOnceAndKeepsToTheOneChosen() {
        let found = [SubtitleCandidate(ticket: "1", language: "he"), SubtitleCandidate(ticket: "2", language: "en"),
                     SubtitleCandidate(ticket: "3", language: "he")]
        #expect(SubtitleLines.languages(found) == ["en", "he"])
        #expect(SubtitleLines.filtered(found, language: nil).count == 3)
        #expect(SubtitleLines.filtered(found, language: "he").map(\.ticket) == ["1", "3"])
        #expect(SubtitleLines.filtered(found, language: "ar").isEmpty)
        let state = SubtitleState(records: [SubtitleRecord(id: "a", installed: true), SubtitleRecord(id: "b")])
        #expect(SubtitleLines.installed(state).map(\.id) == ["a"] && SubtitleLines.history(state).map(\.id) == ["b"])
    }

    @Test func aDownloadsWordsAreTheHubsWarningThenWhatHappensNext() {
        #expect(SubtitleLines.downloaded(SubtitleDownloadAck(ok: true, warning: "Bazarr was slow", jellyfinRefreshStarted: true)) == "Bazarr was slow")
        #expect(SubtitleLines.downloaded(SubtitleDownloadAck(ok: true, jellyfinRefreshStarted: false)).hasPrefix("Saved online, but Jellyfin"))
        #expect(SubtitleLines.downloaded(SubtitleDownloadAck(ok: true, jellyfinRefreshStarted: true)).contains("the player will list it"))
    }

    @Test func theDeletionIsPlainAboutWhatGoesAndWhatStays() {
        let preview = RemovalPreview(ticket: "t", title: "Thor", description: "d", files: ["Thor.mkv"], fileCount: 1)
        #expect(RemovalLines.files(1) == "1 server file" && RemovalLines.files(7) == "7 server files")
        #expect(RemovalLines.confirmTitle(preview) == "Permanently delete Thor?")
        #expect(RemovalLines.confirmMessage(preview)
            == "1 server file will be deleted from the media server. This cannot be undone. Copies saved on this device stay.")
        #expect(RemovalLines.keep == "Cancel" && RemovalLines.delete == "Delete server files")
    }
}

struct DemoUpkeepTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })

    private func episode() throws -> String {
        let bleach = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        return bleach.id + "-e2"
    }

    @Test func aFilmsOrEpisodesSubtitlesAreListedAndAnythingElseIsRefused() async throws {
        let state = try await hub.fetch(HubEndpoints.subtitles(itemId: try episode()), as: SubtitleState.self)
        #expect(state.canDownload && state.records.count >= 1)
        #expect(state.records.first?.embedded == true && state.records.first?.language == "English")
        let series = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        await #expect(throws: HubFailure.self) { _ = try await hub.fetch(HubEndpoints.subtitles(itemId: series.id), as: SubtitleState.self) }
    }

    @Test func aSearchGivesEachCandidateATicketAndADownloadSpendsOne() async throws {
        let item = try episode()
        let found = try await hub.fetch(HubEndpoints.searchSubtitles(itemId: item), as: SubtitleSearch.self)
        #expect(found.candidates.count == 4 && found.candidates.allSatisfy { $0.ticket.count == 48 })
        #expect(Set(found.candidates.map(\.ticket)).count == 4)
        let hebrew = try #require(found.candidates.first { $0.language == "he" && !$0.hi })

        let ack = try await hub.fetch(HubEndpoints.downloadSubtitle(itemId: item, ticket: hebrew.ticket), as: SubtitleDownloadAck.self)
        #expect(ack.ok && ack.jellyfinRefreshStarted)
        let after = try await hub.fetch(HubEndpoints.subtitles(itemId: item), as: SubtitleState.self)
        #expect(DemoUpkeep.downloadedTracks(for: item).contains { $0.code == "he" && !$0.forced && !$0.hi }, "the player lists what was downloaded")
        #expect(after.records.contains { $0.language == "Hebrew" && !$0.embedded && $0.provider == "OpenSubtitles" })
        #expect(after.records.contains { $0.embedded }, "an embedded track is kept")

        // The ticket is spent: the same one again is the hub's 409.
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.downloadSubtitle(itemId: item, ticket: hebrew.ticket), as: SubtitleDownloadAck.self)
        }
    }

    @Test func aSubtitleOfTheSameLanguageAndTypeReplacesTheOldOne() async throws {
        let item = try episode()
        for index in 0..<2 {
            let found = try await hub.fetch(HubEndpoints.searchSubtitles(itemId: item), as: SubtitleSearch.self)
            let english = try #require(found.candidates.first { $0.language == "en" && $0.forced })
            _ = try await hub.fetch(HubEndpoints.downloadSubtitle(itemId: item, ticket: english.ticket), as: SubtitleDownloadAck.self)
            let state = try await hub.fetch(HubEndpoints.subtitles(itemId: item), as: SubtitleState.self)
            #expect(state.records.filter { $0.language == "English" && $0.forced }.count == 1, "download \(index + 1)")
        }
    }

    @Test func aTicketFromAnotherTitleOrAMadeUpOneIsRefused() async throws {
        let other = try #require(DemoLibrary.titles.first { $0.title == "The Matrix" })
        let found = try await hub.fetch(HubEndpoints.searchSubtitles(itemId: other.id), as: SubtitleSearch.self)
        let ticket = try #require(found.candidates.first).ticket
        let item = try episode()
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.downloadSubtitle(itemId: item, ticket: ticket), as: SubtitleDownloadAck.self)
        }
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.downloadSubtitle(itemId: item, ticket: "short"), as: SubtitleDownloadAck.self)
        }
        _ = try await hub.fetch(HubEndpoints.refreshSubtitles(itemId: item), as: ActionAck.self)
    }

    @Test func aDeletionIsPreviewedThenConfirmedOnceAndTheTitleIsGone() async throws {
        defer { DemoLibrary.restoreRemoved() }
        let thor = try #require(DemoLibrary.titles.first { $0.title == "Thor" })
        let preview = try await hub.fetch(HubEndpoints.removalPreview(kind: "video", id: thor.id), as: RemovalPreview.self)
        #expect(preview.title == "Thor" && preview.fileCount == 1 && preview.files == ["Thor (2011).mkv"])
        #expect(preview.ticket.count == 64)
        #expect(preview.description.contains("Saved copies on this device remain"))
        // Nothing is deleted by looking: Thor is still on the server, and among Home's recently added.
        _ = try await hub.fetch(HubEndpoints.libraryItem(thor.id), as: LibraryItemResponse.self)
        func recentlyAdded() async throws -> [String] {
            try await hub.fetch(HubEndpoints.home, as: HomeResponse.self).rows.first { $0.id == "latest" }?.items.map(\.media.title) ?? []
        }
        #expect(try await recentlyAdded().contains("Thor"))

        let ack = try await hub.fetch(HubEndpoints.removeMedia(ticket: preview.ticket), as: ActionAck.self)
        #expect(ack.ok)
        await #expect(throws: HubFailure.self) { _ = try await hub.fetch(HubEndpoints.libraryItem(thor.id), as: LibraryItemResponse.self) }
        #expect(try await !recentlyAdded().contains("Thor"), "Home stops offering what was deleted")
        let folder = try await hub.fetch(HubEndpoints.libraryItems(viewId: thor.folder), as: LibraryPage.self)
        #expect(!folder.items.contains { $0.media.title == "Thor" })

        // The ticket was one use: a second confirmation finds nothing.
        await #expect(throws: HubFailure.self) { _ = try await hub.fetch(HubEndpoints.removeMedia(ticket: preview.ticket), as: ActionAck.self) }
    }

    @Test func aSeriesPreviewListsEveryEpisodeAndAnEpisodeJustItsOwn() async throws {
        let bleach = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        let series = try await hub.fetch(HubEndpoints.removalPreview(kind: "video", id: bleach.id), as: RemovalPreview.self)
        #expect(series.fileCount == 3 && series.files.allSatisfy { $0.hasPrefix("Bleach · S01E0") })
        let one = try await hub.fetch(HubEndpoints.removalPreview(kind: "video", id: bleach.id + "-e2"), as: RemovalPreview.self)
        #expect(one.fileCount == 1 && one.title == "A Second Look")
        let season = try await hub.fetch(HubEndpoints.removalPreview(kind: "video", id: bleach.id + "-s1"), as: RemovalPreview.self)
        #expect(season.title == "Bleach · Season 1" && season.fileCount == 3)
    }

    @Test func aDeletionIsRefusedWithoutItsPreviewOrWithoutAnExplicitYes() async throws {
        for body in [HubEndpoints.removalPreview(kind: "video", id: "not-an-id"), HubEndpoints.removalPreview(kind: "music", id: "x"),
                     HubEndpoints.removalPreview(kind: "reading", id: "nope")] {
            await #expect(throws: HubFailure.self) { _ = try await hub.fetch(body, as: RemovalPreview.self) }
        }
        await #expect(throws: HubFailure.self) {
            _ = try await hub.fetch(HubEndpoints.removeMedia(ticket: String(repeating: "a", count: 64)), as: ActionAck.self)
        }
        let bleach = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        let preview = try await hub.fetch(HubEndpoints.removalPreview(kind: "video", id: bleach.id), as: RemovalPreview.self)
        let unconfirmed = HubRequest("/v1/media/remove", method: .post, body: Data(#"{"ticket":"\#(preview.ticket)"}"#.utf8))
        await #expect(throws: HubFailure.self) { _ = try await hub.fetch(unconfirmed, as: ActionAck.self) }
        // Still there, and its ticket still good.
        _ = try await hub.fetch(HubEndpoints.libraryItem(bleach.id), as: LibraryItemResponse.self)
    }

    @Test func aBookIsPreviewedAndConfirmedWithoutLeavingTheDemoLibrary() async throws {
        let preview = try await hub.fetch(HubEndpoints.removalPreview(kind: "reading", id: "rw_demo_rr2"), as: RemovalPreview.self)
        #expect(preview.title == "Golden Son" && preview.fileCount == 1 && preview.description.contains("editions/issues"))
        let ack = try await hub.fetch(HubEndpoints.removeMedia(ticket: preview.ticket), as: ActionAck.self)
        #expect(ack.ok)
    }

    @Test func aSeriesPageNamesItsReleaseSearch() async throws {
        let bleach = try #require(DemoLibrary.titles.first { $0.title == "Bleach" })
        let series = try await hub.fetch(HubEndpoints.libraryItem(bleach.id), as: LibraryItemResponse.self)
        #expect(LibraryUpkeep.releaseKey(series.item)?.hasPrefix("tmdb:series:") == true && LibraryUpkeep.offersReleases(series.item))
        let matrix = try #require(DemoLibrary.titles.first { $0.title == "The Matrix" })
        let film = try await hub.fetch(HubEndpoints.libraryItem(matrix.id), as: LibraryItemResponse.self)
        #expect(LibraryUpkeep.releaseKey(film.item) == nil && !LibraryUpkeep.offersReleases(film.item))
    }
}

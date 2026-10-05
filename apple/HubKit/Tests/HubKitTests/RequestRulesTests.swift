import Foundation
import Testing
@testable import HubKit

/// The request side's rules (#17), held to Android's cases where it has them
/// (`PipelineChipTest`, `DiscoverFeaturePolicyTest`, `RequestedTitlesTest`,
/// `PollScheduleTest`) and to its wording elsewhere.
struct RequestRulesTests {
    private func hit(_ key: String, title: String = "Movie", availability: String = "not_in_library",
                     actions: [String] = ["detail", "request"], poster: String = "", backdrop: String = "",
                     subtitle: String = "", overview: String = "", year: Int = 0) -> MediaHit {
        MediaHit(media: MediaRef(type: "movie", title: title, year: year, key: key, poster: poster, backdrop: backdrop),
                 subtitle: subtitle, overview: overview, availability: availability, actions: actions)
    }

    @Test func aStagesToneFollowsItsState() {
        #expect(PipelineTone.of("done") == .done)
        #expect(PipelineTone.of("active") == .active)
        #expect(PipelineTone.of("failed") == .failed && PipelineTone.of("stuck") == .failed)
        #expect(PipelineTone.of("pending") == .pending && PipelineTone.of("unknown") == .pending && PipelineTone.of("") == .pending)
    }

    @Test func onlyTheStageUnderWayShowsItsPercent() {
        let download = PipelineStage(id: "download", label: "Download", short: "Download", state: "active", progress: 0.37)
        #expect(PipelineLines.chip(download) == "Download 37%")
        #expect(PipelineLines.chip(PipelineStage(id: "download", label: "Download", short: "Download", state: "active",
                                                 progress: -1)) == "Download")
        #expect(PipelineLines.chip(PipelineStage(id: "download", short: "Download", state: "done", progress: 1)) == "Download")
        #expect(PipelineLines.chip(PipelineStage(id: "grab", label: "Find a release", short: "", state: "pending"))
                == "Find a release")
        #expect(PipelineLines.spoken(download) == "Download, under way")
        #expect(PipelineLines.spoken(PipelineStage(id: "import", label: "Import to library", state: "stuck"))
                == "Import to library, needs attention")
    }

    @Test func theSummaryShowsOnlyWhileSomethingMovesOrIsWrong() {
        let pending = (0..<5).map { PipelineStage(id: "\($0)", state: "pending") }
        #expect(!PipelineLines.showsSummary(Pipeline(summary: "Not requested", stages: pending)))
        var moving = pending
        moving[1].state = "active"
        #expect(PipelineLines.showsSummary(Pipeline(summary: "Looking for a release", stages: moving)))
        #expect(!PipelineLines.showsSummary(Pipeline(summary: "", stages: moving)))
        var stuck = pending
        stuck[2].state = "stuck"
        #expect(PipelineLines.showsSummary(Pipeline(summary: "Download stuck", stages: stuck)))
        let done = (0..<5).map { PipelineStage(id: "\($0)", state: "done") }
        #expect(!PipelineLines.showsSummary(Pipeline(summary: "In your library", stages: done)))
        #expect(PipelineLines.isMoving(Pipeline(stages: moving)) && !PipelineLines.isMoving(Pipeline(stages: stuck)))
        #expect(PipelineLines.summaryColor(availability: "available") == 0xFF3E_CB80)
        #expect(PipelineLines.summaryColor(availability: "deleted") == 0xFFE0_685C)
        #expect(PipelineLines.summaryColor(availability: "processing") == 0xFFF5_C75A)
    }

    /// Android's `PollScheduleTest` for `PIPELINE`.
    @Test func thePipelineIsReadAgainEveryFourSecondsWhileItMoves() {
        #expect(PipelineLines.nextPoll(moving: false, failures: 0) == nil)
        #expect(PipelineLines.nextPoll(moving: true, failures: 0) == .seconds(4))
        #expect(PipelineLines.nextPoll(moving: false, failures: 1) == .seconds(5))
        #expect(PipelineLines.nextPoll(moving: true, failures: 2) == .seconds(15))
        #expect(PipelineLines.nextPoll(moving: true, failures: 9) == .seconds(30))
    }

    /// Android's `DiscoverFeaturePolicyTest` (media), without its width.
    @Test func theFirstTitleWithAPictureAndSomethingToSayIsFeatured() {
        let first = hit("1", backdrop: "/back", overview: "Story")
        let second = hit("2")
        #expect(DiscoverFeature.feature([first, second]) == first)
        #expect(DiscoverFeature.shelf([first, second]) == [second])
        // Nothing to show or say: no feature, and the row keeps it.
        let bare = hit("3", backdrop: "/back")
        #expect(DiscoverFeature.feature([bare, second]) == nil)
        #expect(DiscoverFeature.shelf([bare, second]) == [bare, second])
        #expect(DiscoverFeature.feature([hit("4", poster: "/p", year: 2024)]) != nil)
        #expect(DiscoverFeature.feature([hit("5", title: " ", poster: "/p", year: 2024)]) == nil)
        #expect(DiscoverFeature.mark("not_in_library") == "NOT IN YOUR LIBRARY")
        #expect(DiscoverFeature.mark("processing") == "ON THE WAY")
        #expect(DiscoverFeature.meta(hit("6", subtitle: "2008 · Movie")) == "2008 · Movie")
        #expect(DiscoverFeature.meta(hit("7", year: 2021)) == "2021")
    }

    /// Android's `RequestedTitlesTest`.
    @Test func aTitleRequestedHereShowsItAtOnce() {
        var requested = RequestedTitles()
        let start = requested.revision
        requested.record(key: "movie:1", availability: "processing", requestId: 42)
        let applied = requested.apply(hit("movie:1"))
        #expect(applied.availability == "processing" && applied.requestId == 42 && !applied.canRequest)
        #expect(requested.apply(hit("movie:2")) == hit("movie:2"))
        // The hub's own word wins.
        #expect(requested.apply(hit("movie:1", availability: "available")).availability == "available")
        requested.record(key: "tv:5", availability: "", requestId: 3)
        #expect(requested.apply(hit("tv:5")).availability == "requested")
        #expect(requested.revision > start)
        requested.record(key: "", availability: "processing", requestId: 1)
        #expect(requested.availability(key: "movie:1", hub: "not_in_library") == "processing")
        #expect(requested.availability(key: "movie:1", hub: "partially_available") == "partially_available")
    }

    @Test func aTitlesFactsAndEyebrow() {
        var detail = MediaDetail(media: MediaRef(type: "series", title: "The Mentalist", year: 2008, key: "tmdb:series:5920"),
                                 genres: ["Crime", "Drama"], rating: 7.6, seasons: 7)
        #expect(TitleFacts.facts(detail) == ["2008", "7 seasons", "Crime, Drama", "★ 7.6"])
        detail = MediaDetail(media: MediaRef(type: "movie", title: "Dune", year: 2021, key: "tmdb:movie:438631"),
                             runtimeMinutes: 155, seasons: 1)
        #expect(TitleFacts.facts(detail) == ["2021", "155 min", "1 season"])
        #expect(TitleFacts.eyebrow("processing") == ("ON THE WAY", true))
        #expect(TitleFacts.eyebrow("not_in_library") == ("NOT IN YOUR LIBRARY", false))
        #expect(TitleFacts.eyebrow("unknown") == ("NOT IN YOUR LIBRARY", false))
        #expect(TitleFacts.seasonPill(SeasonOption(number: 2, name: "Season 2", episodeCount: 23)) == "Season 2 · 23 episodes")
        #expect(TitleFacts.seasonPill(SeasonOption(number: 0, episodeCount: 1)) == "Specials · 1 episode")
        #expect(TitleFacts.seasonPill(SeasonOption(number: 3)) == "Season 3")
        #expect(TitleFacts.statusLabel("not_in_library") == "Not in library")
        #expect(TitleFacts.statusLabel("unknown") == "Status unavailable")
        #expect(TitleFacts.statusLabel("requested") == "Requested")
    }

    private let seriesOptions = RequestOptions(
        key: "tmdb:series:5920", type: "series", title: "The Mentalist", service: "sonarr", serverId: 0, serverName: "Sonarr",
        profiles: [RequestOption(id: 1, label: "Any"), RequestOption(id: 4, label: "HD-1080p", isDefault: true)],
        rootFolders: [RootFolderOption(id: 1, path: "E:\\TV", label: "Daniel\\TV", freeSpaceBytes: 224_412_385_280),
                      RootFolderOption(id: 2, path: "E:\\Anime", label: "Daniel\\Anime", isDefault: true)],
        seasons: [SeasonOption(number: 0, name: "Specials", episodeCount: 2),
                  SeasonOption(number: 1, name: "Season 1", episodeCount: 23, year: 2008),
                  SeasonOption(number: 2, episodeCount: 23, year: 2009),
                  SeasonOption(number: 8, name: "Season 8", episodeCount: 0)])

    @Test func theFormStartsOnTheServersDefaultsAndEverySeason() {
        let draft = RequestDraft(options: seriesOptions)
        #expect(draft.profile?.label == "HD-1080p")
        #expect(draft.folder?.path == "E:\\Anime")
        #expect(draft.allSeasons && draft.ticked.isEmpty)
        // Seasons without episodes are not offered; Specials are.
        #expect(draft.seasons.map(\.number) == [0, 1, 2])
        #expect(draft.heading(fallbackTitle: "x") == "Request The Mentalist")
        #expect(draft.subtitle == "Sonarr · 3 seasons · 48 episodes")
        #expect(RequestDraft.freeSpace(seriesOptions.rootFolders[0]) == "209 GB free")
        #expect(RequestDraft.freeSpace(seriesOptions.rootFolders[1]) == "")
        #expect(RequestDraft.folderName(seriesOptions.rootFolders[0]) == "Daniel\\TV")
        #expect(RequestDraft.seasonName(SeasonOption(number: 2)) == "Season 2")
        #expect(RequestDraft.seasonDetail(seriesOptions.seasons[1]) == "23 episodes · 2008")
        #expect(RequestDraft.seasonDetail(SeasonOption(number: 0, episodeCount: 1)) == "1 episode")

        let movie = RequestDraft(options: RequestOptions(key: "tmdb:movie:1", type: "movie", title: "", serverName: "",
                                                         profiles: [RequestOption(id: 1, label: "Any")]))
        #expect(movie.profile?.id == 1 && movie.folder == nil)
        #expect(movie.heading(fallbackTitle: "Dune") == "Request Dune")
        #expect(movie.subtitle.isEmpty)
    }

    @Test func theBodyAsksForAllSeasonsUnlessSomeArePicked() {
        var draft = RequestDraft(options: seriesOptions)
        #expect(draft.body() == CreateRequestBody(key: "tmdb:series:5920", seasons: .all, profileId: 4,
                                                  rootFolder: "E:\\Anime", serverId: 0))
        draft.allSeasons = false
        // Nothing ticked still asks for every season.
        #expect(draft.body().seasons == .all)
        draft.toggle(season: 2)
        draft.toggle(season: 1)
        draft.toggle(season: 0)
        draft.toggle(season: 0)
        #expect(draft.body().seasons == .numbers([1, 2]))
        draft.profileIndex = 0
        draft.folderIndex = 0
        #expect(draft.body().profileId == 1 && draft.body().rootFolder == "E:\\TV")
        let film = RequestDraft(options: RequestOptions(key: "tmdb:movie:1", type: "movie"))
        #expect(film.body() == CreateRequestBody(key: "tmdb:movie:1", serverId: 0))
    }

    @Test func aReleasesLines() {
        #expect(ReleaseLines.tile("Bluray-1080p") == "1080p")
        #expect(ReleaseLines.tile("WEBDL-2160P") == "2160p")
        #expect(ReleaseLines.tile("DVD-R") == "R")
        #expect(ReleaseLines.tile("DVD") == "DVD")
        #expect(ReleaseLines.tile("") == "?")
        let release = Release(id: "a", title: "Gran.Torino.2008.1080p.BluRay.x264-AMIABLE", indexer: "1337x",
                              quality: "Bluray-1080p", sizeBytes: 8_589_934_592, seeders: 112, leechers: 6, ageDays: 4210,
                              languages: ["English"], freeleech: true, rejected: true,
                              rejections: ["Existing file meets cutoff: Bluray-1080p []", "Not wanted"])
        #expect(ReleaseLines.figures(release) == "Bluray-1080p · 8.0 GB · 112s/6p · 4210d old · freeleech · English · 1337x")
        #expect(ReleaseLines.rejections(release) == "Existing file meets cutoff: Bluray-1080p [] · Not wanted")
        #expect(ReleaseLines.confirmDetail(release) == "8.0 GB · Bluray-1080p · 112 seeders\nExisting file meets cutoff: Bluray-1080p []")
        let usenet = Release(id: "b", title: "x", sizeBytes: 0, seeders: 0)
        #expect(ReleaseLines.figures(usenet) == "0 B · 0s/0p")
        #expect(ReleaseLines.status(total: 52, accepted: 3) == "52 releases · 3 acceptable")
        #expect(ReleaseLines.status(total: 1, accepted: 0) == "1 release · 0 acceptable — every one was refused, see why below")
        #expect(ReleaseLines.status(total: 0, accepted: 0) == "0 releases · 0 acceptable")
        #expect(ReleaseLines.blockedTitle(season: 1, episode: 5) == "More than S01E05")
        #expect(ReleaseLines.blockedTitle(season: 2, episode: 0) == "More than Season 2")
        #expect(ReleaseLines.grabbed(GrabReply(title: "Last.Seen.S01"), release: release) == "Grabbed — Last.Seen.S01")
        #expect(ReleaseLines.grabbed(GrabReply(), release: release) == "Grabbed — " + release.title)
    }

    @Test func anAiredEpisodesLinesAndAPersonsCount() {
        let target = ReleaseEpisodeTarget(season: 1, episode: 4, title: "Mindy's Back", airDate: "2026-09-12",
                                          runtimeMinutes: 65, hasFile: true, monitored: false)
        #expect(ReleaseTargetLines.title(target) == "S1E4 · Mindy's Back")
        #expect(ReleaseTargetLines.meta(target) == "2026-09-12 · 1h 5m · Downloaded · Not monitored")
        #expect(ReleaseTargetLines.meta(ReleaseEpisodeTarget(season: 1, episode: 2, runtimeMinutes: 44)) == "44 min")
        #expect(ReleaseTargetLines.status(aired: 0) == "No episodes have aired yet · the season search is still available")
        #expect(ReleaseTargetLines.status(aired: 1) == "1 aired episode")
        #expect(ReleaseTargetLines.heading(series: "Last Seen", seasonTitle: "Season 1") == "Last Seen · Season 1")
        #expect(ReleaseTargetLines.heading(series: "Last Seen", target: target) == "Last Seen · S1E4 · Mindy's Back")

        let person = PersonResponse(id: 1, name: "Timothée Chalamet", knownFor: "Acting",
                                    credits: [hit("a"), hit("b")], sortedBy: "release")
        #expect(PersonLines.status(person) == "2 credits · Acting · newest first")
        #expect(PersonLines.status(PersonResponse(id: 1, name: "x", credits: [hit("a")], sortedBy: "popularity"))
                == "1 credit · by popularity")
    }

    @Test func theAvailabilityChip() {
        #expect(Availability.label("available") == "In library")
        #expect(Availability.label("partially_available") == "Partial")
        #expect(Availability.label("processing") == "On the way")
        #expect(Availability.label("requested") == "Requested")
        #expect(Availability.label("downloading") == "Downloading")
        #expect(Availability.label("blocked") == "Blocked" && Availability.label("deleted") == "Deleted")
        #expect(Availability.label("not_in_library") == nil && Availability.label("unknown") == nil)
        #expect(Availability.tone("partially_available") == .partial)
        #expect(Availability.ink(.partial) == 0xFFFF_FFFF && Availability.ink(.available) == 0xFF11_1116)
        #expect(Availability.inLibrary("partially_available") && !Availability.inLibrary("processing"))
    }
}

import Foundation
import Testing
@testable import HubKit

/// The cases of Android's `ResumeRulesTest`, `EpisodeLabelTest`,
/// `AppearancePolicyTest` (sorting), `FmtTest` (clock, runtime) and
/// `HomeHeroTest`, translated one for one.
struct ResumeRulesTests {
    let hour: Int64 = 3_600_000

    @Test func underFivePercentIsNotAResumePoint() {
        #expect(ResumeRules.judge(positionMillis: 0, durationMillis: hour) == .notStarted)
        #expect(ResumeRules.judge(positionMillis: 179_999, durationMillis: hour) == .notStarted)
        #expect(ResumeRules.resumePosition(positionMillis: 179_999, durationMillis: hour) == 0)
    }

    @Test func betweenFiveAndNinetyPercentResumes() {
        #expect(ResumeRules.resumePosition(positionMillis: 180_000, durationMillis: hour) == 180_000)
        #expect(ResumeRules.resumePosition(positionMillis: 3_240_000, durationMillis: hour) == 3_240_000)
    }

    @Test func pastNinetyPercentIsWatchedTheWayTheHubSavesIt() {
        #expect(ResumeRules.judge(positionMillis: 3_312_000, durationMillis: hour) == .finished)
        #expect(ResumeRules.isFinished(positionMillis: 3_312_000, durationMillis: hour))
        #expect(ResumeRules.resumePosition(positionMillis: 3_312_000, durationMillis: hour) == 0)
    }

    @Test func anythingShorterThanFiveMinutesIsWatchedOnceStarted() {
        #expect(ResumeRules.judge(positionMillis: 60_000, durationMillis: 240_000) == .finished)
        #expect(ResumeRules.judge(positionMillis: 5_000, durationMillis: 240_000) == .notStarted)
    }

    @Test func noRuntimeKeepsThePositionAsTheHubDoes() {
        #expect(ResumeRules.judge(positionMillis: 60_000, durationMillis: 0) == .resume)
        #expect(ResumeRules.judge(positionMillis: 0, durationMillis: 0) == .notStarted)
    }

    @Test func aRewatchShowsItsProgressNotTheWatchedTick() {
        #expect(!ResumeRules.showsWatched(played: true, progress: 0.13))
        #expect(ResumeRules.watchLabel(played: true, progress: 0.13) == "13% watched")
        #expect(!ResumeRules.isFinished(positionMillis: 1_200_000, durationMillis: hour))
    }

    @Test func watchedWithNothingToResumeShowsTheTick() {
        #expect(ResumeRules.showsWatched(played: true, progress: 0))
        #expect(ResumeRules.watchLabel(played: true, progress: 0) == "Watched")
        #expect(!ResumeRules.showsWatched(played: false, progress: 0))
        #expect(ResumeRules.watchLabel(played: false, progress: 0) == nil)
    }
}

struct EpisodeLabelTests {
    @Test func aNumberedEpisodeReadsCodeThenTitle() {
        #expect(EpisodeLabel.of(season: 1, episode: 4, title: "Mindy's Back") == "S1E4 · Mindy's Back")
    }

    @Test func seasonZeroIsSpecials() {
        #expect(EpisodeLabel.season(0) == "Specials")
        #expect(EpisodeLabel.season(3) == "Season 3")
    }

    @Test func whicheverHalfExistsIsKept() {
        #expect(EpisodeLabel.of(season: 0, episode: 2, title: "Behind the Scenes") == "S0E2 · Behind the Scenes")
        #expect(EpisodeLabel.of(season: 0, episode: 0, title: "Pilot") == "Pilot")
        #expect(EpisodeLabel.of(season: 2, episode: 3, title: "") == "S2E3")
    }
}

struct SortAndClockTests {
    @Test func aNewFieldStartsInTheUsefulDirection() {
        #expect(!SortPreference.forField("played").ascending)
        #expect(!SortPreference.forField("rating").ascending)
        #expect(SortPreference.forField("author").ascending)
        #expect(SortPreference.forField("added") == SortPreference(field: "added", ascending: false))
    }

    @Test func theDirectionIsNamedForItsField() {
        #expect(SortPreference(field: "added", ascending: false).directionLabel == "Newest first")
        #expect(SortPreference(field: "added", ascending: true).directionLabel == "Oldest first")
        #expect(SortPreference(field: "name", ascending: true).directionLabel == "A to Z")
        #expect(SortPreference(field: "rating", ascending: false).directionLabel == "Highest first")
        #expect(SortPreference.label(for: "parental") == "Parental rating")
    }

    @Test func aStoredSortRoundTripsAndABadOneFallsBack() {
        #expect(SortPreference(field: "added", ascending: false).encoded == "added:desc")
        #expect(SortPreference.decode("added:desc", fallback: "name") == SortPreference(field: "added", ascending: false))
        #expect(SortPreference.decode("last_read:asc", fallback: "name") == SortPreference(field: "last_read", ascending: true))
        #expect(SortPreference.decode(nil, fallback: "name") == SortPreference(field: "name", ascending: true))
        #expect(SortPreference.decode("Added:up", fallback: "added") == SortPreference(field: "added", ascending: false))
        #expect(SortPreference.decode("a:b:c", fallback: "name") == SortPreference(field: "name", ascending: true))
    }

    @Test func clockShowsMinutesAndSecondsUnderAnHour() {
        #expect(Fmt.clock(millis: 0) == "0:00")
        #expect(Fmt.clock(millis: 9_999) == "0:09")
        #expect(Fmt.clock(millis: (17 * 60 + 12) * 1_000) == "17:12")
        #expect(Fmt.clock(millis: -5_000) == "0:00")
    }

    @Test func clockCarriesHoursInsteadOfLettingMinutesRunPastSixty() {
        #expect(Fmt.clock(millis: (75 * 60 + 30) * 1_000) == "1:15:30")
        #expect(Fmt.clock(millis: 10 * 3_600_000) == "10:00:00")
    }

    @Test func runtimeReadsInMinutesUntilItNeedsHours() {
        #expect(Fmt.runtime(seconds: 24 * 60) == "24 min")
        #expect(Fmt.runtime(seconds: 59 * 60 + 59) == "59 min")
        #expect(Fmt.runtime(seconds: 3_600) == "1h 0m")
        #expect(Fmt.runtime(seconds: 0) == "")
    }
}

struct HomeHeroTests {
    let seriesId = "8e950bfabe23bbfbb1a99ecccd1b954d"
    let episodeId = "2d47480cfb56648c4c1dbae678660479"
    var episode: MediaHit {
        MediaHit(media: MediaRef(type: "episode", title: "Drake & Josh", year: 2005,
                                 poster: "/v1/img/jf/\(seriesId)/Primary?tag=p",
                                 backdrop: "/v1/img/jf/\(episodeId)/Primary?tag=still"),
                 subtitle: "S3E4 · Mindy's Back", rating: 8.0, jellyfinItemId: episodeId, progress: 0.2)
    }

    @Test func anEpisodeShowsItsSeriesAsTheTitleAndItsCodeInTheEyebrow() {
        let hero = HomeHero.from(rowId: "continue", rowTitle: "Continue watching", hit: episode)
        #expect(hero.eyebrow == "CONTINUE WATCHING · S3E4")
        #expect(hero.title == "Drake & Josh")
        #expect(hero.meta == ["Mindy's Back", "2005", "★ 8.0"])
        #expect(hero.playLabel == "Resume")
        #expect(hero.backdrop == "/v1/img/jf/\(seriesId)/Backdrop")
    }

    @Test func anEpisodeWithoutNumbersKeepsItsTitleOutOfTheEyebrow() {
        var hit = episode
        hit.subtitle = "בלאגן ועד אילת - פרק 6 "
        let hero = HomeHero.from(rowId: "nextup", rowTitle: "Next up", hit: hit)
        #expect(hero.eyebrow == "NEXT UP")
        #expect(hero.meta == ["בלאגן ועד אילת - פרק 6", "2005", "★ 8.0"])
    }

    @Test func detailsAddTheCertificationRuntimeLeftAndTheSeriesBackdrop() {
        let detail = LibraryItem(id: episodeId, type: "episode", title: "Mindy's Back", seriesId: seriesId, year: 2005,
                                 overview: "Science fair.", runtimeSeconds: 1500, officialRating: "TV-Y7")
        let hero = HomeHero.from(rowId: "continue", rowTitle: "Continue watching", hit: episode, detail: detail)
        #expect(hero.meta == ["Mindy's Back", "2005", "TV-Y7", "25 min", "★ 8.0"])
        #expect(hero.progressLabel == "20 min left")
        #expect(hero.backdrop == "/v1/img/jf/\(seriesId)/Backdrop")
    }

    @Test func aNewMoviePlaysFromTheStartAndALibraryRowNamesItsLibrary() {
        let movie = MediaHit(media: MediaRef(type: "movie", title: "Iron Man 3", year: 2013, backdrop: "/b"),
                             jellyfinItemId: "m")
        let hero = HomeHero.from(rowId: "library:abc", rowTitle: "Marvel Movies", hit: movie)
        #expect(hero.eyebrow == "MARVEL MOVIES")
        #expect(hero.playLabel == "Play")
        #expect(hero.progress == 0)
        #expect(hero.backdrop == "/b")
    }

    @Test func anEpisodeWhosePosterIsItsOwnStillHasNoSeriesToBorrowFrom() {
        var own = episode
        own.media.poster = "/v1/img/jf/\(episodeId)/Primary?tag=still"
        #expect(HomeHero.seriesIdFromPoster(own.media.poster, itemId: episodeId) == nil)
        #expect(HomeHero.from(rowId: "continue", rowTitle: "Continue watching", hit: own).backdrop
            == "/v1/img/jf/\(episodeId)/Primary?tag=still")
    }

    @Test func aFinishedEpisodeIsNotOfferedAsAResume() {
        var watched = episode
        watched.played = true
        watched.progress = 0
        #expect(HomeHero.from(rowId: "nextup", rowTitle: "Next up", hit: watched).playLabel == "Play")
        #expect(HomeHero.from(rowId: "nextup", rowTitle: "Next up", hit: watched).eyebrow == "NEXT UP · S3E4")
    }

    @Test func rowsFollowHomesOrderNotTheHubs() {
        let rows = ["favourites", "continue", "nextup", "latest", "something-new"].map {
            HomeRow(id: $0, title: $0, items: [])
        }
        #expect(HomeHero.ordered(rows).map(\.id) == ["continue", "nextup", "latest", "favourites", "something-new"])
        #expect(HomeHero.isLandscape(rowId: "continue") && !HomeHero.isLandscape(rowId: "latest"))
    }
}

struct DetailLinesTests {
    @Test func anEpisodesFactsStartWithItsCode() {
        let item = LibraryItem(id: "e", type: "episode", title: "Bit by a Dead Bee", subtitle: "S2E3 · Bit by a Dead Bee",
                               year: 2009, runtimeSeconds: 2820, rating: 8.1, officialRating: "TV-MA",
                               genres: ["Drama", "Crime", "Thriller", "Western"])
        #expect(DetailLines.facts(item) == "S2E3 · Bit by a Dead Bee  ·  2009  ·  TV-MA  ·  47 min  ·  ★ 8.1  ·  Drama  ·  Crime  ·  Thriller")
    }

    @Test func theStateLineSaysWatchedResumeAndFavourite() {
        let watched = LibraryItem(id: "m", type: "movie", title: "Gran Torino", played: true, favorite: true)
        #expect(DetailLines.state(watched) == "✓ Watched · ★ Favourite")
        let rewatch = LibraryItem(id: "m", type: "movie", title: "Gran Torino", played: true, progress: 0.4, positionSeconds: 754)
        #expect(DetailLines.state(rewatch) == "40% watched · Continue at 12:34")
        #expect(DetailLines.playLabel(rewatch) == "Resume · 12:34")
        let series = LibraryItem(id: "s", type: "series", title: "Severance", unplayedCount: 9)
        #expect(DetailLines.state(series) == "9 unwatched")
        #expect(DetailLines.playLabel(series) == "Play")
    }

    @Test func theDetailsSectionNamesWhoMadeItAndWhen() {
        let film = LibraryItem(
            id: "m", type: "movie", title: "Amélie", originalTitle: "Le Fabuleux Destin d'Amélie Poulain",
            premiereDate: "2001-04-25T00:00:00.0000000Z", genres: ["Comedy", "Romance"], studios: ["UGC"],
            people: [LibraryPerson(name: "Jean-Pierre Jeunet", type: "Director"),
                     LibraryPerson(name: "Guillaume Laurant", type: "Writer"),
                     LibraryPerson(name: "Jean-Pierre Jeunet", type: "Writer"),
                     LibraryPerson(name: "Audrey Tautou", role: "Amélie", type: "Actor")])
        let facts = DetailLines.details(film)
        #expect(facts.map(\.label) == ["Directed by", "Written by", "Studio", "Genres", "Released", "Original title"])
        #expect(facts[1].value == "Guillaume Laurant, Jean-Pierre Jeunet")
        #expect(facts[4].value == "25 Apr 2001")
        #expect(DetailLines.cast(film).map(\.name) == ["Audrey Tautou"])

        let series = LibraryItem(id: "s", type: "series", title: "Dark", originalTitle: "dark",
                                 premiereDate: "2017-12-01", studios: ["Netflix", "Wiedemann & Berg"])
        #expect(DetailLines.details(series).map(\.label) == ["Studios", "First aired"])
        #expect(DetailLines.day("") == "" && DetailLines.day("0001-01-01T00:00:00Z") == "")
    }

    @Test func anEpisodeInASeasonListReadsNumberTitleAndProgress() {
        let episode = LibraryItem(id: "e", type: "episode", title: "Pilot", indexNumber: 1, runtimeSeconds: 2820,
                                  progress: 0.4)
        #expect(DetailLines.episodeTitle(episode) == "1. Pilot")
        #expect(DetailLines.episodeMeta(episode) == "47 min · 40% watched")
        let special = LibraryItem(id: "x", type: "episode", title: "Making of", played: true)
        #expect(DetailLines.episodeTitle(special) == "Making of")
        #expect(DetailLines.episodeMeta(special) == "Watched")
    }
}

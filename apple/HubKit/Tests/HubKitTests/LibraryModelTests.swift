import Foundation
import Testing
@testable import HubKit

/// Home and Library responses in the shapes the hub writes (`home.go`,
/// `library.go`, `sweeper.go`'s `itemToHit`).
struct LibraryModelTests {
    @Test func homeRowsKeepTheirOrderAndCardFields() throws {
        let json = #"""
        {"rows":[
          {"id":"continue","title":"Continue watching","page":1,"totalPages":1,"items":[
            {"media":{"key":"tmdb:episode:1396","type":"episode","title":"Breaking Bad","year":2008,
                      "ids":{"tmdb":1396},"poster":"/v1/img/jf/s1/Primary?tag=a",
                      "backdrop":"/v1/img/jf/e1/Primary?tag=b"},
             "subtitle":"S2E3 · Bit by a Dead Bee","availability":"available","jellyfinItemId":"e1",
             "played":false,"favorite":false,"progress":0.42,"actions":["detail"]}]},
          {"id":"latest","title":"Recently added","page":1,"totalPages":1,"items":[
            {"media":{"type":"series","title":"Severance","year":2022},"subtitle":"2022",
             "jellyfinItemId":"s2","unplayedCount":9,"actions":["detail"]}]}],
         "partial":[{"service":"jellyfin","reason":"row_unavailable","affects":["rows.nextup"],
                     "message":"Next up could not be loaded"}],
         "cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#
        let home = try JSONDecoder().decode(HomeResponse.self, from: Data(json.utf8))
        #expect(home.rows.map(\.id) == ["continue", "latest"])
        let card = home.rows[0].items[0]
        #expect(card.media.type == "episode")
        #expect(card.media.title == "Breaking Bad")
        #expect(card.subtitle == "S2E3 · Bit by a Dead Bee")
        #expect(card.media.tmdb == 1396)
        #expect(card.media.backdrop == "/v1/img/jf/e1/Primary?tag=b")
        #expect(card.progress == 0.42)
        #expect(card.id == "e1")
        #expect(home.rows[1].items[0].unplayedCount == 9)
        #expect(home.rows[1].items[0].media.poster == "")
        #expect(home.partial.first?.message == "Next up could not be loaded")
    }

    @Test func aLibraryFolderAndAPageOfIt() throws {
        let folders = try JSONDecoder().decode(LibraryResponse.self, from: Data(#"""
        {"views":[{"id":"v1","name":"Movies","kind":"movies","image":"/v1/img/jf/m9/Primary","imageStyle":"poster"},
                  {"id":"v2","name":"Marvel TV","kind":"tvshows","image":"/v1/img/jf/v2/Primary","imageStyle":"banner"}],
         "partial":[],"cache":{"hit":true,"ageSeconds":12}}
        """#.utf8))
        #expect(folders.views.map(\.name) == ["Movies", "Marvel TV"])
        #expect(folders.views[1].imageStyle == "banner")

        let page = try JSONDecoder().decode(LibraryPage.self, from: Data(#"""
        {"viewId":"v1","title":"Movies","page":2,"totalPages":3,"total":179,"sortedBy":"name","sortOrder":"asc",
         "items":[{"media":{"type":"movie","title":"Gran Torino","year":2008},"subtitle":"2008",
                   "jellyfinItemId":"m1","played":true,"favorite":true}],"partial":[]}
        """#.utf8))
        #expect(page.page == 2 && page.totalPages == 3 && page.total == 179)
        #expect(page.items[0].played && page.items[0].favorite)
    }

    @Test func anEpisodeItemReadsEveryFieldTheScreensUse() throws {
        let json = #"""
        {"item":{"id":"e1","type":"episode","title":"Bit by a Dead Bee","subtitle":"S2E3 · Bit by a Dead Bee",
                 "seriesTitle":"Breaking Bad",
                 "seriesId":"s1","seasonId":"se2","year":2009,"indexNumber":3,"seasonNumber":2,
                 "overview":"Walt and Jesse…","runtimeSeconds":2820,"rating":8.1,"officialRating":"TV-MA",
                 "genres":["Drama"],"studios":["AMC"],
                 "people":[{"id":"p1","name":"Bryan Cranston","role":"Walter White","type":"Actor",
                            "image":"/v1/img/jf/p1/Primary","tmdbId":17419},
                           {"id":"p2","name":"Someone Unknown","role":"Guard","type":"Actor"}],
                 "mediaVersions":[{"id":"mv","container":"mkv","tracks":[]}],
                 "played":false,"favorite":true,"progress":0.25,"positionSeconds":705,"lastPlayedAt":1759500000000,
                 "poster":"/v1/img/jf/s1/Primary","thumb":"/v1/img/jf/e1/Primary","backdrop":"/v1/img/jf/s1/Backdrop",
                 "library":{"id":"v2","name":"Shows"}},
         "partial":[],"cache":{"hit":false}}
        """#
        let item = try JSONDecoder().decode(LibraryItemResponse.self, from: Data(json.utf8)).item
        #expect(item.type == "episode")
        #expect(item.subtitle == "S2E3 · Bit by a Dead Bee")
        #expect(item.seriesTitle == "Breaking Bad")
        #expect(item.seasonNumber == 2 && item.indexNumber == 3)
        #expect(item.runtimeSeconds == 2820 && item.positionSeconds == 705)
        #expect(item.people.first?.role == "Walter White")
        #expect(item.people.first?.id == "p1Walter White")
        // The TMDB id the filmography needs (#27); absent is 0, which opens nothing.
        #expect(item.people.map(\.tmdbId) == [17419, 0])
        #expect(item.favorite && !item.played)
        #expect(item.thumb == "/v1/img/jf/e1/Primary")
    }

    @Test func seasonsAndEpisodesShareOneListShape() throws {
        let list = try JSONDecoder().decode(LibraryItemList.self, from: Data(#"""
        {"seriesId":"s1","seasonId":"se2","page":1,"totalPages":2,"total":13,
         "items":[{"id":"e1","type":"episode","title":"Pilot","indexNumber":1,"seasonNumber":1}]}
        """#.utf8))
        #expect(list.totalPages == 2)
        #expect(list.items.map(\.title) == ["Pilot"])
    }

    @Test func aStateChangeSendsOnlyTheValueThatChanged() throws {
        let played = String(decoding: LibraryStateChange.played(true).body(), as: UTF8.self)
        let favourite = String(decoding: LibraryStateChange.favorite(false).body(), as: UTF8.self)
        #expect(played == #"{"played":true}"#)
        #expect(favourite == #"{"favorite":false}"#)
    }
}

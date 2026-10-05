import Foundation
import Testing
@testable import HubKit

/// The request side's responses in the shapes the hub writes (#17), from the
/// hub's own Go types and test fixtures.
struct DiscoverModelTests {
    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    @Test func discoverRowsKeepTheHubsOrderAndCards() throws {
        let response = try decode(DiscoverResponse.self, #"""
        {"rows":[{"id":"trending","title":"Trending now","page":1,"totalPages":500,"items":[
           {"media":{"key":"tmdb:movie:438631","type":"movie","title":"Dune","year":2021,
                     "ids":{"tmdb":438631,"imdb":"tt1160419"},"poster":"/v1/img/tmdb/w342/abc.jpg",
                     "backdrop":"/v1/img/tmdb/w780/def.jpg"},
            "subtitle":"2021 · Movie","overview":"Paul Atreides…","availability":"downloading","rating":7.8,
            "played":false,"favorite":false,"progress":0.63,"eta":"00:08:12","actions":["detail"]},
           {"media":{"key":"tmdb:series:258230","type":"series","title":"Last Seen","year":2025,
                     "ids":{"tmdb":258230,"tvdb":451782},"poster":"/v1/img/tmdb/w342/ghi.jpg"},
            "subtitle":"2025 · Series","availability":"partially_available","rating":6.9,
            "jellyfinItemId":"4f1c2a9b8e7d6c5b4a39281706f5e4d3","played":false,"favorite":false,
            "actions":["play","detail"]}]},
          {"id":"movies","title":"Popular films","page":1,"totalPages":58801,"items":[
           {"media":{"key":"tmdb:movie:693134","type":"movie","title":"Dune: Part Two","year":2024,"ids":{"tmdb":693134}},
            "subtitle":"2024 · Movie","availability":"not_in_library","actions":["detail","request"]}]}],
         "partial":[{"service":"jellyseerr","reason":"row_unavailable","affects":["rows.upcoming"],
                     "since":"0001-01-01T00:00:00Z","retryAt":"0001-01-01T00:00:00Z",
                     "message":"Coming soon could not be loaded"}],
         "cache":{"hit":true,"ageSeconds":412,"stale":false}}
        """#)
        #expect(response.rows.map(\.id) == ["trending", "movies"])
        #expect(response.rows[1].totalPages == 58801)
        let dune = response.rows[0].items[0]
        #expect(dune.media.key == "tmdb:movie:438631")
        #expect(dune.progress == 0.63)
        #expect(!dune.canRequest)
        #expect(response.rows[0].items[1].jellyfinItemId == "4f1c2a9b8e7d6c5b4a39281706f5e4d3")
        #expect(response.rows[1].items[0].canRequest)
        #expect(response.partial.first?.message == "Coming soon could not be loaded")
        #expect(response.cache.ageSeconds == 412)
    }

    @Test func searchResultsAndTheirCounts() throws {
        let response = try decode(SearchResponse.self, #"""
        {"query":"dune","page":1,"totalPages":3,"totalResults":52,"results":[
          {"media":{"key":"tmdb:movie:438631","type":"movie","title":"Dune","year":2021,"ids":{"tmdb":438631}},
           "subtitle":"2021 · Movie","availability":"available","jellyfinItemId":"8f2c0d6e1b3a49c7a5e2f1d0c9b8a7e6",
           "played":false,"favorite":false,"actions":["play","detail"]}],
         "partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#)
        #expect(response.totalResults == 52 && response.totalPages == 3)
        #expect(response.results.first?.media.title == "Dune")
    }

    @Test func aTitlesDetailWithItsPipeline() throws {
        let detail = try decode(MediaDetail.self, #"""
        {"media":{"key":"tmdb:series:5920","type":"series","title":"The Mentalist","year":2008,
                  "ids":{"tmdb":5920,"tvdb":82459,"imdb":"tt1196946"},
                  "poster":"/v1/img/tmdb/w342/p.jpg","backdrop":"/v1/img/tmdb/w780/b.jpg"},
         "overview":"Patrick Jane…","genres":["Crime","Drama","Mystery"],"rating":7.6,"seasons":7,"episodes":151,
         "seasonList":[{"number":1,"name":"Season 1","episodeCount":23,"year":2008,"image":"/v1/img/tmdb/w342/s1.jpg"},
                       {"number":2,"name":"Season 2","episodeCount":23,"year":2009}],
         "availability":"processing",
         "cast":[{"id":1001,"name":"Simon Baker","character":"Patrick Jane","profile":"/v1/img/tmdb/w185/sb.jpg"},
                 {"id":1002,"name":"Robin Tunney","character":"Teresa Lisbon"}],
         "pipeline":{"summary":"Downloading — 37% · 2.5 MB/s","stages":[
           {"id":"request","label":"Requested","short":"Request","state":"done","source":"jellyseerr"},
           {"id":"grab","label":"Find a release","short":"Grab","state":"done","detail":"The.Mentalist.S01-S08","source":"arr"},
           {"id":"download","label":"Download","short":"Download","state":"active","detail":"37% · 2.5 MB/s",
            "source":"download client","progress":0.37},
           {"id":"import","label":"Import to library","short":"Import","state":"pending","source":"arr"},
           {"id":"library","label":"In Jellyfin","short":"Library","state":"pending","source":"jellyfin"}]},
         "actions":["detail"],"partial":[],"cache":{"hit":true,"ageSeconds":12,"stale":false}}
        """#)
        #expect(detail.isSeries && !detail.canRequest)
        #expect(detail.seasonList.map(\.episodeCount) == [23, 23])
        #expect(detail.cast[1].profile.isEmpty)
        #expect(detail.pipeline.stages.map(\.state) == ["done", "done", "active", "pending", "pending"])
        #expect(detail.pipeline.stages[2].progress == 0.37)
        #expect(detail.trailerUrl.isEmpty)

        let film = try decode(MediaDetail.self, #"""
        {"media":{"key":"tmdb:movie:438631","type":"movie","title":"Dune","year":2021,"ids":{"tmdb":438631}},
         "runtimeMinutes":155,"trailerUrl":"https://www.youtube.com/watch?v=n9xhJrPXop4","trailerKey":"n9xhJrPXop4",
         "availability":"not_in_library","pipeline":{"summary":"Not requested","stages":[]},
         "actions":["detail","request"],"partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#)
        #expect(film.canRequest && !film.isSeries && film.runtimeMinutes == 155)
        #expect(film.trailerKey == "n9xhJrPXop4")
    }

    @Test func requestOptionsMarkTheServersDefaults() throws {
        let options = try decode(RequestOptions.self, #"""
        {"key":"tmdb:movie:438631","type":"movie","title":"Dune","service":"radarr","serverId":0,"serverName":"Radarr",
         "has4k":false,
         "profiles":[{"id":1,"label":"Any","default":false},{"id":4,"label":"HD-1080p","default":true}],
         "rootFolders":[{"id":1,"path":"E:\\Videos\\Daniel\\Movies","label":"Daniel\\Movies","freeSpaceBytes":812345678901,"default":true},
                        {"id":2,"path":"E:\\Videos\\Daniel\\Marvel\\Movies","label":"Marvel\\Movies","freeSpaceBytes":812345678901,"default":false}],
         "tags":[],"partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#)
        #expect(options.profiles[1].isDefault)
        #expect(options.rootFolders[0].path == "E:\\Videos\\Daniel\\Movies")
        #expect(options.rootFolders[1].label == "Marvel\\Movies")
        #expect(options.serverName == "Radarr" && options.serverId == 0)
    }

    @Test func aRequestBodyNamesOnlyWhatWasChosen() throws {
        func text(_ body: CreateRequestBody) throws -> String {
            String(decoding: try #require(HubEndpoints.createRequest(body).body), as: UTF8.self)
        }
        #expect(try text(CreateRequestBody(key: "tmdb:movie:1", profileId: 4, rootFolder: "E:\\Movies", serverId: 0))
                == #"{"is4k":false,"key":"tmdb:movie:1","profileId":4,"rootFolder":"E:\\Movies","serverId":0}"#)
        #expect(try text(CreateRequestBody(key: "tmdb:series:5920", seasons: .all))
                == #"{"is4k":false,"key":"tmdb:series:5920","seasons":"all"}"#)
        #expect(try text(CreateRequestBody(key: "tmdb:series:5920", seasons: .numbers([1, 2]), serverId: 0))
                == #"{"is4k":false,"key":"tmdb:series:5920","seasons":[1,2],"serverId":0}"#)
        let request = HubEndpoints.createRequest(CreateRequestBody(key: "tmdb:movie:1"))
        #expect(request.method == .post && !request.idempotent)
        let reply = try decode(CreateRequestReply.self, #"""
        {"requestId":57,"state":"approved","message":"Approved — looking for a release","availability":"processing",
         "pipeline":{"summary":"Looking for a release","stages":[]}}
        """#)
        #expect(reply.requestId == 57 && reply.availability == "processing")
    }

    @Test func releaseTargetsAndReleases() throws {
        let targets = try decode(ReleaseTargetsResponse.self, #"""
        {"key":"tmdb:series:258230","title":"Last Seen","season":1,"seasonTitle":"Season 1",
         "seasonImage":"/v1/img/tmdb/w342/season.jpg",
         "episodes":[{"season":1,"episode":1,"title":"The Dispatcher","overview":"A clue appears.","airDate":"2000-01-01",
                      "runtimeMinutes":53,"image":"/v1/img/tmdb/w500/episode-1.jpg","hasFile":false,"monitored":true},
                     {"season":1,"episode":3,"title":"Recovered","runtimeMinutes":44,"hasFile":true,"monitored":false}],
         "partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#)
        #expect(targets.episodes.map(\.episode) == [1, 3])
        #expect(targets.episodes[1].hasFile && !targets.episodes[1].monitored)

        let releases = try decode(ReleasesResponse.self, #"""
        {"key":"tmdb:movie:13223","title":"Gran Torino","service":"radarr","releases":[
          {"id":"3f9a1c0b7d2e4a51","title":"Gran.Torino.2008.1080p.BluRay.x264-AMIABLE","indexer":"1337x (Prowlarr)",
           "quality":"Bluray-1080p","protocol":"torrent","sizeBytes":8589934592,"seeders":112,"leechers":6,
           "ageDays":4210,"releaseGroup":"AMIABLE","languages":["English"],"score":0,
           "rejected":true,"rejections":["Existing file meets cutoff: Bluray-1080p []"]}],
         "accepted":0,"partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}
        """#)
        let release = try #require(releases.releases.first)
        #expect(release.protocolName == "torrent" && release.sizeBytes == 8_589_934_592)
        #expect(release.rejected && !release.scopeBlocked)
        #expect(releases.accepted == 0)
    }

    @Test func theCalendarAndAPerson() throws {
        let calendar = try decode(CalendarResponse.self, #"""
        {"start":"2026-09-25","end":"2026-10-02","timezone":"Asia/Jerusalem","generatedAt":"2026-09-25T06:02:11.4417823Z",
         "items":[{"id":"sonarr:episode:7","service":"sonarr",
                   "media":{"key":"tmdb:series:88","type":"series","title":"A show","ids":{},"poster":"/v1/img/arr/sonarr/8"},
                   "date":"2026-09-25","at":"2026-09-25T20:00:00Z","releaseType":"Episode","season":2,"episode":1,
                   "episodeTitle":"Episode","hasFile":false},
                  {"id":"radarr:movie:3:Digital release","service":"radarr",
                   "media":{"key":"tmdb:movie:99","type":"movie","title":"A film","year":2026,"ids":{},"poster":"/v1/img/arr/radarr/3"},
                   "date":"2026-09-25","releaseType":"Digital release","overview":"…","hasFile":false}],
         "partial":[]}
        """#)
        #expect(calendar.items.map(\.id) == ["sonarr:episode:7", "radarr:movie:3:Digital release"])
        #expect(calendar.items[0].media.tmdb == 0 && calendar.items[0].media.key == "tmdb:series:88")
        #expect(calendar.items[1].at.isEmpty && calendar.items[1].season == 0)

        let person = try decode(PersonResponse.self, #"""
        {"id":1190668,"name":"Timothée Chalamet","profile":"/v1/img/tmdb/w185/tc.jpg","knownFor":"Acting",
         "credits":[{"media":{"key":"tmdb:movie:693134","type":"movie","title":"Dune: Part Two","year":2024,"ids":{"tmdb":693134}},
                     "subtitle":"2024 · Movie · Paul Atreides","availability":"unknown","actions":["detail"]}],
         "sortedBy":"release","partial":[],"cache":{"hit":true,"ageSeconds":3600,"stale":false}}
        """#)
        #expect(person.credits.count == 1 && person.sortedBy == "release")
        #expect(Availability.label(person.credits[0].availability) == nil)
    }

    @Test func thePathsAndTheirQueries() {
        #expect(HubEndpoints.discover.path == "/v1/discover")
        #expect(HubEndpoints.discoverRow("movies", page: 2).path == "/v1/discover/movies?page=2")
        #expect(HubEndpoints.search("gran torino").path == "/v1/search?q=gran%20torino")
        #expect(HubEndpoints.search("dune", page: 3).path == "/v1/search?q=dune&page=3")
        #expect(HubEndpoints.mediaDetail(key: "tmdb:series:5920").path == "/v1/media/tmdb%3Aseries%3A5920")
        #expect(HubEndpoints.person(id: 1190668).path == "/v1/person/1190668")
        #expect(HubEndpoints.person(id: 7, byPopularity: true).path == "/v1/person/7?sort=popularity")
        #expect(HubEndpoints.requestOptions(key: "tmdb:movie:1").path == "/v1/requests/options?key=tmdb%3Amovie%3A1")
        #expect(HubEndpoints.releaseTargets(key: "tmdb:series:2", season: 0).path
                == "/v1/media/tmdb%3Aseries%3A2/release-targets?season=0")
        let film = HubEndpoints.releases(key: "tmdb:movie:13223")
        #expect(film.path == "/v1/media/tmdb%3Amovie%3A13223/releases" && film.slow)
        // A series always names its season: 0 is Specials, not "every season".
        #expect(HubEndpoints.releases(key: "tmdb:series:2", season: 0).path == "/v1/media/tmdb%3Aseries%3A2/releases?season=0")
        #expect(HubEndpoints.releases(key: "tmdb:series:2", season: 1, episode: 4).path
                == "/v1/media/tmdb%3Aseries%3A2/releases?season=1&episode=4")
        let grab = HubEndpoints.grab(key: "tmdb:series:2", GrabBody(releaseId: "3f9a1c0b7d2e4a51", season: 1))
        #expect(grab.method == .post && grab.slow && !grab.idempotent)
        #expect(String(decoding: grab.body ?? Data(), as: UTF8.self) == #"{"releaseId":"3f9a1c0b7d2e4a51","season":1}"#)
        #expect(HubEndpoints.calendar(start: "2026-09-28", end: "2026-10-05", timezone: "Asia/Jerusalem").path
                == "/v1/calendar?start=2026-09-28&end=2026-10-05&timezone=Asia%2FJerusalem")
    }
}

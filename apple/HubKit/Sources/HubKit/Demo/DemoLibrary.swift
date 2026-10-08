import Foundation
import Synchronization

/// The demo hub's Jellyfin library (`-demo`): a few titles in each of
/// `DemoMedia`'s libraries, a search that keeps to one library when it is
/// given one (#14), Favourites, a title's page with its season and episodes,
/// and watched and favourite, which change and stay for the run as Jellyfin
/// keeps them (#9). It answers as the hub does, so a request the hub would
/// refuse is refused here too. Nothing real changes.
enum DemoLibrary {
    struct Title: Sendable {
        let id: String
        let folder: String
        let type: String
        let title: String
        let year: Int
        let minutes: Int
        let genres: [String]
    }

    /// What a profile has done to a title in this run.
    struct UserState: Sendable {
        var played = false
        var favorite = false
    }

    private static let anime = DemoMedia.folders[0].id
    private static let marvelMovies = DemoMedia.folders[1].id
    private static let marvelTV = DemoMedia.folders[2].id
    private static let movies = DemoMedia.folders[3].id
    private static let shows = DemoMedia.folders[4].id

    /// Jellyfin's ids are 32 hex characters.
    private static func id(_ number: Int) -> String { String(format: "%032lx", 0xdeb0_0000 + number) }

    static let titles: [Title] = [
        Title(id: id(1), folder: anime, type: "series", title: "Attack on Titan", year: 2013, minutes: 24, genres: ["Animation", "Action"]),
        Title(id: id(2), folder: anime, type: "series", title: "Avatar: The Last Airbender", year: 2005, minutes: 23, genres: ["Animation", "Adventure"]),
        Title(id: id(3), folder: anime, type: "series", title: "Bleach", year: 2004, minutes: 24, genres: ["Animation", "Action"]),
        Title(id: id(4), folder: anime, type: "series", title: "Code Geass: Lelouch of the Rebellion", year: 2006, minutes: 24, genres: ["Animation", "Drama"]),
        Title(id: id(5), folder: anime, type: "series", title: "Cowboy Bebop", year: 1998, minutes: 24, genres: ["Animation", "Sci-Fi"]),
        Title(id: id(6), folder: marvelMovies, type: "movie", title: "Thor", year: 2011, minutes: 115, genres: ["Action", "Fantasy"]),
        Title(id: id(7), folder: marvelMovies, type: "movie", title: "Guardians of the Galaxy Vol. 2", year: 2017, minutes: 137, genres: ["Action", "Comedy"]),
        Title(id: id(8), folder: marvelMovies, type: "movie", title: "Avengers: Endgame", year: 2019, minutes: 181, genres: ["Action", "Sci-Fi"]),
        Title(id: id(9), folder: marvelTV, type: "series", title: "Loki", year: 2021, minutes: 50, genres: ["Fantasy", "Sci-Fi"]),
        Title(id: id(10), folder: marvelTV, type: "series", title: "WandaVision", year: 2021, minutes: 35, genres: ["Comedy", "Drama"]),
        Title(id: id(11), folder: movies, type: "movie", title: "Gran Torino", year: 2008, minutes: 116, genres: ["Drama"]),
        Title(id: id(12), folder: movies, type: "movie", title: "The Dark Knight", year: 2008, minutes: 152, genres: ["Action", "Crime"]),
        Title(id: id(13), folder: movies, type: "movie", title: "Dune", year: 2021, minutes: 155, genres: ["Sci-Fi", "Adventure"]),
        Title(id: id(14), folder: movies, type: "movie", title: "Inception", year: 2010, minutes: 148, genres: ["Action", "Sci-Fi"]),
        Title(id: id(15), folder: movies, type: "movie", title: "The Matrix", year: 1999, minutes: 136, genres: ["Action", "Sci-Fi"]),
        Title(id: id(16), folder: shows, type: "series", title: "The Mentalist", year: 2008, minutes: 43, genres: ["Crime", "Drama"]),
        Title(id: id(17), folder: shows, type: "series", title: "Dark Matter", year: 2024, minutes: 55, genres: ["Sci-Fi", "Drama"]),
        Title(id: id(18), folder: shows, type: "series", title: "Slow Horses", year: 2022, minutes: 50, genres: ["Drama", "Thriller"]),
        Title(id: id(19), folder: shows, type: "series", title: "Ted Lasso", year: 2020, minutes: 33, genres: ["Comedy", "Drama"]),
    ]

    private static let states = Mutex<[String: UserState]>([:])

    /// What has been deleted from the server in this run (#34): a title the
    /// hub's confirmed removal took. Nothing real is deleted.
    private static let removed = Mutex<Set<String>>([])

    /// The titles still on the demo server.
    static var available: [Title] {
        let gone = removed.withLock { $0 }
        return titles.filter { !gone.contains($0.id) }
    }

    /// Deletes a title, as a confirmed removal would.
    static func remove(_ id: String) { removed.withLock { _ = $0.insert(id) } }

    /// The titles back, for a test that deleted one.
    static func restoreRemoved() { removed.withLock { $0 = [] } }

    /// What an id names, as the hub's removal plan sees it: a film or a series
    /// by its own id, its season ("-s1") or an episode ("-e2"), with the files
    /// that would go.
    static func plan(for id: String) -> (type: String, title: String, files: [String])? {
        let base = String(id.prefix(32))
        guard let found = title(base) else { return nil }
        let suffix = id.dropFirst(32)
        func episodeFile(_ number: Int) -> String {
            "\(found.title) · S01E0\(number) · \(episodeNames[number - 1]).mkv"
        }
        switch (found.type, suffix) {
        case ("movie", ""): return ("movie", found.title, ["\(found.title) (\(found.year)).mkv"])
        case ("series", ""): return ("series", found.title, (1...3).map(episodeFile))
        case ("series", "-s1"): return ("season", "\(found.title) · Season 1", (1...3).map(episodeFile))
        case ("series", _) where suffix.hasPrefix("-e"):
            guard let number = Int(suffix.dropFirst(2)), (1...3).contains(number) else { return nil }
            return ("episode", episodeNames[number - 1], [episodeFile(number)])
        default: return nil
        }
    }

    private static let episodeNames = ["The Beginning", "A Second Look", "Third Time Lucky"]

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 3, parts[0] == "v1", parts[1] == "library" else { return nil }
        switch (method, parts.count, parts[2]) {
        case ("GET", 3, "search"):
            let viewId = value("viewId", in: query)
            // The hub refuses a malformed library before asking Jellyfin (#14).
            if !viewId.isEmpty, !isHex32(viewId) {
                return failure(400, "invalid_request", "viewId must be a library's 32-character id")
            }
            let words = value("q", in: query).lowercased()
            let found = available.filter { (viewId.isEmpty || $0.folder == viewId) && $0.title.lowercased().contains(words) }
            return page(title: "Search", found)
        case ("GET", 3, "favorites"):
            let starred = states.withLock { all in available.filter { all[$0.id]?.favorite == true } }
            return page(title: "Favourites", starred)
        case ("GET", 4, _) where parts[3] == "items":
            guard let folder = DemoMedia.folders.first(where: { $0.id == parts[2] }) else {
                return failure(404, "not_found", "No such library")
            }
            return page(title: folder.name, available.filter { $0.folder == folder.id })
        case ("GET", 4, "items"):
            guard let title = title(parts[3]) else { return episode(parts[3]) }
            return item(title)
        case ("GET", 5, "items") where parts[4] == "similar":
            guard let title = title(parts[3]) else { return failure(404, "not_found", "No such title") }
            return page(title: "More like this", available.filter { $0.folder == title.folder && $0.id != title.id })
        case ("POST", 5, "items") where parts[4] == "state":
            return change(parts[3], body: body)
        case ("GET", 5, "series") where parts[4] == "seasons":
            guard let title = title(parts[3]), title.type == "series" else { return failure(404, "not_found", "No such series") }
            let season: [String: Any] = ["id": title.id + "-s1", "type": "season", "title": "Season 1", "seriesTitle": title.title,
                                         "seriesId": title.id, "indexNumber": 1, "unplayedCount": 3]
            return json(["page": 1, "totalPages": 1, "items": [season]])
        case ("GET", 5, "series") where parts[4] == "episodes":
            guard let title = title(parts[3]), title.type == "series" else { return failure(404, "not_found", "No such series") }
            return json(["page": 1, "totalPages": 1, "items": (1...3).map { episodeFields(title, number: $0) }])
        case ("GET", 5, "series") where parts[4] == "play-target":
            guard let title = title(parts[3]), title.type == "series" else { return failure(404, "not_found", "No such series") }
            return json(["kind": "start", "item": episodeFields(title, number: 1)])
        default:
            return nil
        }
    }

    // MARK: Home

    /// `GET /v1/home` (#35): what is part way through, what comes next, what is
    /// new and the favourites, for the profile. An empty row is never sent, as
    /// the hub's.
    static func home() -> DemoTransport.Answer {
        func tile(_ index: Int) -> Title? { available.first { $0.id == id(index) } }
        func partway(_ title: Title, _ progress: Double) -> [String: Any] {
            var card = hit(title)
            card["progress"] = progress
            return card
        }
        func next(_ title: Title, number: Int) -> [String: Any] {
            let episode = episodeFields(title, number: number)
            let media: [String: Any] = ["type": "episode", "title": title.title, "year": title.year, "key": "jf:\(title.id)"]
            return ["media": media, "subtitle": "S1E\(number) · \(episodeNames[number - 1])", "availability": "available",
                    "jellyfinItemId": episode["id"] ?? "", "actions": ["play", "detail"]]
        }
        let starred = states.withLock { all in available.filter { all[$0.id]?.favorite == true } }
        let rows: [[String: Any]] = [
            ["id": "continue", "title": "Continue watching",
             "items": [tile(11).map { partway($0, 0.42) }, tile(13).map { partway($0, 0.12) }].compactMap { $0 }],
            ["id": "nextup", "title": "Next up",
             "items": [tile(17).map { next($0, number: 2) }, tile(19).map { next($0, number: 3) }].compactMap { $0 }],
            ["id": "latest", "title": "Recently added", "items": [tile(13), tile(14), tile(6), tile(18)].compactMap { $0 }.map(hit)],
            ["id": "favourites", "title": "Favourites", "items": starred.map(hit)],
        ].filter { !(($0["items"] as? [Any]) ?? []).isEmpty }
        return json(["rows": rows, "partial": [Any](), "cache": ["hit": false, "ageSeconds": 0, "stale": false]])
    }

    // MARK: Answers

    private static func title(_ id: String) -> Title? { available.first { $0.id == id } }

    private static func state(_ id: String) -> UserState { states.withLock { $0[id] ?? UserState() } }

    /// A card, as the hub's `SearchHit` for a title in the library.
    private static func hit(_ title: Title) -> [String: Any] {
        let now = state(title.id)
        let media: [String: Any] = ["type": title.type, "title": title.title, "year": title.year, "key": "jf:\(title.id)"]
        return ["media": media,
                "subtitle": "\(title.year)", "availability": "available", "jellyfinItemId": title.id,
                "played": now.played, "favorite": now.favorite,
                // Drawn by `DemoArtwork`, so a title's page has a backdrop and a download keeps its pictures.
                "poster": "/v1/img/jf/\(title.id)/Primary", "backdrop": "/v1/img/jf/\(title.id)/Backdrop",
                "unplayedCount": title.type == "series" && !now.played ? 3 : 0, "actions": ["play", "detail"]]
    }

    private static func page(title: String, _ list: [Title]) -> DemoTransport.Answer {
        json(["title": title, "page": 1, "totalPages": 1, "total": list.count, "items": list.map(hit)])
    }

    private static func itemFields(_ title: Title) -> [String: Any] {
        let now = state(title.id)
        return ["id": title.id, "type": title.type, "title": title.title, "year": title.year,
                "overview": "\(title.title), as the demo hub tells it: a title in the library, with nothing real behind it.",
                "runtimeSeconds": title.minutes * 60, "rating": 8.1, "officialRating": "PG-13", "genres": title.genres,
                "played": now.played, "favorite": now.favorite,
                // Drawn by `DemoArtwork`: the page's backdrop, and what a download keeps beside its file.
                "poster": "/v1/img/jf/\(title.id)/Primary", "backdrop": "/v1/img/jf/\(title.id)/Backdrop",
                "unplayedCount": title.type == "series" && !now.played ? 3 : 0,
                // A series the hub can name on TMDB has the key its release search is asked by (#34).
                "mediaKey": title.type == "series" ? "tmdb:series:\(100_000 + (Int(title.id.suffix(4), radix: 16) ?? 0))" : "",
                // One the hub names on TMDB (#27), whose portrait opens the
                // filmography the demo hub answers, and one it cannot name.
                "people": [["id": "demo-person-1", "name": "Rebecca Ferguson", "role": "Lead", "type": "Actor",
                            "tmdbId": 933238],
                           ["id": "demo-person-2", "name": "A Face in the Crowd", "role": "Extra", "type": "Actor"]]]
    }

    private static func item(_ title: Title) -> DemoTransport.Answer { json(["item": itemFields(title)]) }

    /// "<series id>-e2": the second episode of that series' only season.
    private static func episodeFields(_ title: Title, number: Int) -> [String: Any] {
        ["id": "\(title.id)-e\(number)", "type": "episode", "title": episodeNames[number - 1],
         "subtitle": "S1E\(number) · \(episodeNames[number - 1])", "seriesTitle": title.title, "seriesId": title.id,
         "seasonId": title.id + "-s1", "year": title.year, "indexNumber": number, "seasonNumber": 1,
         "overview": "Episode \(number) of \(title.title) in the demo hub.", "runtimeSeconds": title.minutes * 60,
         "thumb": "/v1/img/jf/\(title.id)-e\(number)/Primary", "poster": "/v1/img/jf/\(title.id)/Primary",
         "backdrop": "/v1/img/jf/\(title.id)/Backdrop"]
    }

    private static func episode(_ id: String) -> DemoTransport.Answer {
        let pieces = id.split(separator: "-")
        guard pieces.count == 2, let series = title(String(pieces[0])), pieces[1].hasPrefix("e"),
              let number = Int(pieces[1].dropFirst()), (1...3).contains(number) else {
            return failure(404, "not_found", "No such title")
        }
        return json(["item": episodeFields(series, number: number)])
    }

    /// The hub's rule: exactly one of `played` and `favorite`, a true or a
    /// false, then the title as Jellyfin now has it.
    private static func change(_ id: String, body: Data?) -> DemoTransport.Answer {
        guard let title = title(id) else { return failure(404, "not_found", "No such title") }
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any], fields.count == 1,
              let key = fields.keys.first, key == "played" || key == "favorite",
              let number = fields[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
            return failure(400, "invalid_request", "Send exactly one of played and favorite, as true or false")
        }
        states.withLock { all in
            var now = all[id] ?? UserState()
            if key == "played" { now.played = number.boolValue } else { now.favorite = number.boolValue }
            all[id] = now
        }
        return item(title)
    }

    // MARK: Plumbing

    private static func json(_ object: [String: Any]) -> DemoTransport.Answer {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return DemoTransport.Answer(200, data: data, type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func isHex32(_ text: String) -> Bool {
        text.count == 32 && text.allSatisfy { $0.isHexDigit }
    }

    private static func value(_ name: String, in query: String) -> String {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, kv[0] == name { return kv[1].removingPercentEncoding ?? kv[1] }
        }
        return ""
    }
}

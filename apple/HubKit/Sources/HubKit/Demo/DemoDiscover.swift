import Foundation

/// The demo hub's request side (`-demo`, #17): two Discover rows, a film to
/// request and a series on its way, request options, a week of releases and a
/// short release search. A request or a grab answers as the real hub would,
/// and changes nothing anywhere.
enum DemoDiscover {
    private struct Title {
        let key: String
        let type: String
        let title: String
        let year: Int
        let availability: String
        let progress: Double

        var canRequest: Bool { availability == "not_in_library" }

        var json: String {
            let actions = canRequest ? #"["detail","request"]"# : #"["detail"]"#
            let kind = type == "movie" ? "Movie" : "Series"
            return DemoDiscover.join(#"{"media":{"key":"\#(key)","type":"\#(type)","title":"\#(title)","year":\#(year),"ids":{}},"#,
                        #""subtitle":"\#(year) · \#(kind)","overview":"\#(title), as the demo hub tells it.","#,
                        #""availability":"\#(availability)","rating":7.4,"played":false,"favorite":false,"#,
                        progress > 0 ? #""progress":\#(progress),"# : "",
                        #""actions":\#(actions)}"#)
        }
    }

    /// One string from many: quick for the type checker, where a long `+`
    /// chain of literals is slow.
    fileprivate static func join(_ parts: String...) -> String { parts.joined() }

    private static let trending = [
        Title(key: "tmdb:movie:693134", type: "movie", title: "Dune: Part Two", year: 2024, availability: "not_in_library", progress: 0),
        Title(key: "tmdb:series:5920", type: "series", title: "The Mentalist", year: 2008, availability: "processing", progress: 0),
        Title(key: "tmdb:series:95396", type: "series", title: "Severance", year: 2022, availability: "downloading", progress: 0.42),
        Title(key: "tmdb:series:258230", type: "series", title: "Last Seen", year: 2025, availability: "requested", progress: 0),
        Title(key: "tmdb:movie:13223", type: "movie", title: "Gran Torino", year: 2008, availability: "available", progress: 0),
    ]

    private static let films = [
        Title(key: "tmdb:movie:438631", type: "movie", title: "Dune", year: 2021, availability: "available", progress: 0),
        Title(key: "tmdb:movie:157336", type: "movie", title: "Interstellar", year: 2014, availability: "not_in_library", progress: 0),
        Title(key: "tmdb:movie:27205", type: "movie", title: "Inception", year: 2010, availability: "partially_available", progress: 0),
    ]

    static func answer(method: String, path: String, query: String) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        let area = parts.count > 1 ? parts[1] : ""
        switch (method, area) {
        case ("GET", "discover") where parts.count == 2:
            return DemoTransport.Answer(200, join(#"{"rows":["#, row("trending", "Trending now", trending), ",",
                                                  row("movies", "Popular films", films),
                                                  #"],"partial":[],"cache":{"hit":false,"ageSeconds":0,"stale":false}}"#))
        case ("GET", "discover"):
            let id = parts.count > 2 ? parts[2] : "trending"
            return DemoTransport.Answer(200, #"{"rows":[{"id":"\#(id)","title":"","page":2,"totalPages":2,"items":[]}],"partial":[]}"#)
        case ("GET", "search"):
            let q = value("q", in: query).lowercased()
            let hits = (trending + films).filter { $0.title.lowercased().contains(q) }
            return DemoTransport.Answer(200, join(#"{"query":"\#(q)","page":1,"totalPages":1,"totalResults":\#(hits.count),"#,
                                                  #""results":["#, hits.map(\.json).joined(separator: ","),
                                                  #"],"partial":[],"cache":{"hit":false,"ageSeconds":0}}"#))
        case ("GET", "media") where parts.count == 3:
            return DemoTransport.Answer(200, detail(parts[2]))
        case ("GET", "media") where parts.count == 4 && parts[3] == "release-targets":
            return DemoTransport.Answer(200, join(
                #"{"key":"\#(parts[2])","title":"The Mentalist","season":1,"seasonTitle":"Season 1","episodes":["#,
                #"{"season":1,"episode":1,"title":"Pilot","airDate":"2008-09-23","runtimeMinutes":43,"hasFile":true,"monitored":true},"#,
                #"{"season":1,"episode":2,"title":"Red Hair and Silver Tape","airDate":"2008-09-30","runtimeMinutes":43,"#,
                #""hasFile":false,"monitored":true}],"partial":[],"cache":{"hit":false,"ageSeconds":0}}"#))
        case ("GET", "media") where parts.count == 4 && parts[3] == "releases":
            return DemoTransport.Answer(200, releases(parts[2]))
        case ("POST", "media") where parts.count == 4 && parts[3] == "grab":
            return DemoTransport.Answer(200, #"{"ok":true,"quality":"WEBDL-1080p","service":"radarr","title":"Demo.Release.1080p.WEB-DL"}"#)
        case ("GET", "requests") where parts.count == 3 && parts[2] == "options":
            return DemoTransport.Answer(200, options(value("key", in: query)))
        case ("POST", "requests"):
            return DemoTransport.Answer(201, join(#"{"requestId":57,"state":"approved","message":"Approved — looking for a release","#,
                                                  #""availability":"processing","pipeline":{"summary":"Looking for a release","stages":[]}}"#))
        case ("GET", "person"):
            return DemoTransport.Answer(200, join(#"{"id":1,"name":"Rebecca Ferguson","knownFor":"Acting","credits":["#,
                                                  [trending[0], films[0]].map(\.json).joined(separator: ","),
                                                  #"],"sortedBy":"release","partial":[],"cache":{"hit":true}}"#))
        case ("GET", "calendar"):
            return DemoTransport.Answer(200, calendar(start: value("start", in: query)))
        default:
            return nil
        }
    }

    private static func row(_ id: String, _ title: String, _ items: [Title]) -> String {
        join(#"{"id":"\#(id)","title":"\#(title)","page":1,"totalPages":2,"items":["#,
             items.map(\.json).joined(separator: ","), "]}")
    }

    private static func value(_ name: String, in query: String) -> String {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, kv[0] == name { return kv[1].removingPercentEncoding ?? kv[1] }
        }
        return ""
    }

    private static let stageNames: [(id: String, label: String, short: String)] = [
        ("request", "Request", "Request"), ("grab", "Find a release", "Grab"), ("download", "Download", "Download"),
        ("import", "Import to library", "Import"), ("library", "In Jellyfin", "Library"),
    ]

    private static func pipeline(_ states: [String], summary: String) -> String {
        let list = zip(stageNames, states).map { stage, state in
            let extra = stage.id == "download" && state == "active" ? #","progress":0.37,"detail":"37% · 2.5 MB/s""# : ""
            return #"{"id":"\#(stage.id)","label":"\#(stage.label)","short":"\#(stage.short)","state":"\#(state)","source":"demo"\#(extra)}"#
        }
        return join(#"{"summary":"\#(summary)","stages":["#, list.joined(separator: ","), "]}")
    }

    private static func detail(_ key: String) -> String {
        let title = (trending + films).first { $0.key == key }
            ?? Title(key: key, type: key.contains(":series:") ? "series" : "movie", title: "Demo title",
                     year: 2024, availability: "not_in_library", progress: 0)
        let states: [String]
        let summary: String
        switch title.availability {
        case "processing", "downloading":
            states = ["done", "done", "active", "pending", "pending"]
            summary = "Downloading — 37% · 2.5 MB/s"
        case "requested":
            states = ["done", "pending", "pending", "pending", "pending"]
            summary = "Requested — waiting for approval"
        case "available":
            states = ["done", "done", "done", "done", "done"]
            summary = "In your library"
        default:
            states = ["pending", "pending", "pending", "pending", "pending"]
            summary = "Not requested"
        }
        let size = title.type == "series"
            ? join(#","seasons":2,"episodes":46,"seasonList":[{"number":1,"name":"Season 1","episodeCount":23,"year":2008},"#,
                   #"{"number":2,"name":"Season 2","episodeCount":23,"year":2009}]"#)
            : #","runtimeMinutes":167"#
        let actions = title.canRequest ? #"["detail","request"]"# : #"["detail"]"#
        return join(
            #"{"media":{"key":"\#(title.key)","type":"\#(title.type)","title":"\#(title.title)","year":\#(title.year),"ids":{}},"#,
            #""overview":"\#(title.title) in the demo hub: a story long enough to need a second line on a phone, which it gets.","#,
            #""genres":["Science Fiction","Adventure"],"rating":8.2"#, size, ",",
            #""trailerUrl":"https://www.youtube.com/watch?v=Way9Dexny3w","trailerKey":"Way9Dexny3w","#,
            #""availability":"\#(title.availability)","#,
            #""cast":[{"id":1,"name":"Rebecca Ferguson","character":"Lady Jessica"},"#,
            #"{"id":2,"name":"Javier Bardem","character":"Stilgar"}],"#,
            #""pipeline":"#, pipeline(states, summary: summary), #","actions":"#, actions,
            #","partial":[],"cache":{"hit":false,"ageSeconds":0}}"#)
    }

    private static func options(_ key: String) -> String {
        let series = key.contains(":series:")
        let seasons = series
            ? join(#","seasons":[{"number":0,"name":"Specials","episodeCount":2},"#,
                   #"{"number":1,"name":"Season 1","episodeCount":23,"year":2008},"#,
                   #"{"number":2,"name":"Season 2","episodeCount":23,"year":2009}]"#)
            : ""
        let folders = series
            ? #"[{"id":1,"path":"E:\\Videos\\Daniel\\TV Shows","label":"Daniel\\TV Shows","freeSpaceBytes":224412385280,"default":true}]"#
            : join(#"[{"id":1,"path":"E:\\Videos\\Daniel\\Movies","label":"Daniel\\Movies","freeSpaceBytes":224412385280,"default":true},"#,
                   #"{"id":2,"path":"E:\\Videos\\Daniel\\Marvel\\Movies","label":"Marvel\\Movies","freeSpaceBytes":224412385280,"default":false}]"#)
        return join(
            #"{"key":"\#(key)","type":"\#(series ? "series" : "movie")","title":"","service":"\#(series ? "sonarr" : "radarr")","#,
            #""serverId":0,"serverName":"\#(series ? "Sonarr" : "Radarr")","has4k":false,"#,
            #""profiles":[{"id":1,"label":"Any","default":false},{"id":4,"label":"HD-1080p","default":true},"#,
            #"{"id":5,"label":"Ultra-HD","default":false}],"rootFolders":"#, folders, #","tags":[]"#, seasons,
            #","partial":[],"cache":{"hit":false,"ageSeconds":0}}"#)
    }

    private static func releases(_ key: String) -> String {
        join(#"{"key":"\#(key)","title":"Demo","service":"radarr","releases":["#,
             #"{"id":"1111111111111111","title":"Demo.Title.2024.2160p.UHD.BluRay.x265-GROUP","indexer":"Demo indexer","#,
             #""quality":"Bluray-2160p","protocol":"torrent","sizeBytes":48318382080,"seeders":212,"leechers":9,"ageDays":31,"#,
             #""languages":["English"],"score":0,"rejected":false},"#,
             #"{"id":"2222222222222222","title":"Demo.Title.2024.1080p.WEB-DL.DDP5.1.H.264-GROUP","indexer":"Demo indexer","#,
             #""quality":"WEBDL-1080p","protocol":"torrent","sizeBytes":6442450944,"seeders":88,"leechers":4,"ageDays":40,"#,
             #""score":0,"rejected":true,"rejections":["Existing file meets cutoff: WEBDL-1080p []"]}],"#,
             #""accepted":1,"partial":[],"cache":{"hit":false,"ageSeconds":0}}"#)
    }

    /// Releases on days of the week asked for, so This week always has some.
    private static func calendar(start: String) -> String {
        let day = { (offset: Int) in UpcomingPresentation.add(days: offset, to: start) }
        return join(
            #"{"start":"\#(start)","end":"\#(day(7))","timezone":"UTC","items":["#,
            #"{"id":"sonarr:episode:1","service":"sonarr","media":{"key":"tmdb:series:95396","type":"series","title":"Severance","ids":{}},"#,
            #""date":"\#(day(0))","at":"\#(day(0))T02:00:00Z","releaseType":"Episode","season":2,"episode":9,"#,
            #""episodeTitle":"The After Hours","hasFile":true},"#,
            #"{"id":"sonarr:episode:2","service":"sonarr","media":{"key":"tmdb:series:258230","type":"series","title":"Last Seen","ids":{}},"#,
            #""date":"\#(day(2))","at":"\#(day(2))T20:00:00Z","releaseType":"Episode","season":1,"episode":5,"#,
            #""episodeTitle":"Recovered","hasFile":false},"#,
            #"{"id":"sonarr:episode:3","service":"sonarr","media":{"key":"tmdb:series:258230","type":"series","title":"Last Seen","ids":{}},"#,
            #""date":"\#(day(2))","at":"\#(day(2))T20:45:00Z","releaseType":"Episode","season":1,"episode":6,"#,
            #""episodeTitle":"Found","hasFile":false},"#,
            #"{"id":"radarr:movie:3:Digital release","service":"radarr","#,
            #""media":{"key":"tmdb:movie:693134","type":"movie","title":"Dune: Part Two","year":2024,"ids":{}},"#,
            #""date":"\#(day(4))","releaseType":"Digital release","hasFile":false}],"partial":[]}"#)
    }
}

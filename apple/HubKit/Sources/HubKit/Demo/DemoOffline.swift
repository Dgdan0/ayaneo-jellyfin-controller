import Foundation
import Synchronization

/// The demo hub's offline downloads (#5), answered as the hub answers them
/// (`hub/internal/api/offline.go`): a series' selection, grants for films
/// and episodes, the file and a subtitle file under each grant, renewal, and
/// the watches sent back. Every download is `DemoVideo`, a real MP4 written
/// once per run, with an SRT beside it. The demo library's titles
/// (`DemoLibrary`) are read through its own answers, so the two agree.
/// Nothing real is read or written.
enum DemoOffline {
    /// A grant: its item, and its manifest as JSON.
    private struct Grant {
        let itemId: String
        let manifest: Data
    }

    private static let grants = Mutex<[String: Grant]>([:])
    /// The watches the demo hub was sent, for the tests: clientEventKey to itemId.
    static let synced = Mutex<[String: String]>([:])

    static let sourceId = "demo-mp4"

    static func answer(method: String, path: String, query: String, body: Data?) async -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 3, parts[0] == "v1", parts[1] == "offline" else { return nil }
        switch (method, parts.count, parts[2]) {
        case ("GET", 5, "series") where parts[4] == "selection":
            return await selection(parts[3])
        case ("POST", 3, "prepare"):
            return await prepare(body)
        case ("GET", 5, "grants") where parts[4] == "media":
            guard grant(parts[3]) != nil else { return failure(404, "not_found", "no such offline grant") }
            guard let video = await DemoVideo.data() else { return failure(500, "internal", "the demo video could not be made") }
            return DemoTransport.Answer(200, data: video, type: "video/mp4")
        case ("GET", 6, "grants") where parts[4] == "subtitles":
            guard grant(parts[3]) != nil, parts[5] == "2" else { return failure(404, "not_found", "no such offline subtitle") }
            return DemoTransport.Answer(200, data: Data(subtitles.utf8), type: "application/x-subrip")
        case ("POST", 5, "grants") where parts[4] == "renew":
            guard let grant = grant(parts[3]),
                  var manifest = (try? JSONSerialization.jsonObject(with: grant.manifest)) as? [String: Any] else {
                return failure(404, "not_found", "no such offline grant")
            }
            manifest["expiresAt"] = expiry()
            let renewed = (try? JSONSerialization.data(withJSONObject: manifest)) ?? grant.manifest
            grants.withLock { $0[parts[3]] = Grant(itemId: grant.itemId, manifest: renewed) }
            return DemoTransport.Answer(200, data: renewed, type: "application/json")
        case ("POST", 4, "progress") where parts[3] == "sync":
            return sync(body)
        default:
            return nil
        }
    }

    // MARK: Answers

    private static func selection(_ seriesId: String) async -> DemoTransport.Answer {
        guard let series = library("/v1/library/items/" + seriesId)?["item"] as? [String: Any],
              series["type"] as? String == "series" else { return failure(400, "invalid_request", "item is not a series") }
        let size = Int64(await DemoVideo.data()?.count ?? 0)
        let seasons = (library("/v1/library/series/\(seriesId)/seasons")?["items"] as? [[String: Any]]) ?? []
        let episodes = (library("/v1/library/series/\(seriesId)/episodes")?["items"] as? [[String: Any]]) ?? []
        let selectionSeasons = seasons.map { season -> [String: Any] in
            let id = season["id"] as? String ?? ""
            let inSeason = episodes.filter { $0["seasonId"] as? String == id }
            return ["season": season, "episodes": inSeason.map { episode -> [String: Any] in
                ["item": episode, "sources": [source(size)], "estimatedSizeBytes": size, "available": size > 0]
            }]
        }
        return json(["series": series, "seasons": selectionSeasons, "episodeCount": episodes.count,
                     "estimatedSizeBytes": size * Int64(episodes.count),
                     "playTargetId": episodes.first?["id"] as? String ?? ""])
    }

    private static func prepare(_ body: Data?) async -> DemoTransport.Answer {
        guard let body, let fields = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let batchKey = fields["batchKey"] as? String, !batchKey.isEmpty,
              let items = fields["items"] as? [[String: Any]], !items.isEmpty else {
            return failure(400, "invalid_request", "offline batch is empty or invalid")
        }
        let seriesId = fields["seriesId"] as? String ?? ""
        guard let video = await DemoVideo.data() else { return failure(500, "internal", "the demo video could not be made") }
        var manifests: [Any] = []
        for request in items {
            guard let itemId = request["itemId"] as? String, let key = request["clientItemKey"] as? String, !key.isEmpty,
                  var item = library("/v1/library/items/" + itemId)?["item"] as? [String: Any] else {
                return failure(400, "invalid_request", "offline item id or key is invalid")
            }
            let type = item["type"] as? String ?? ""
            guard type == "movie" || type == "episode" else {
                return failure(400, "invalid_request", "offline items must be movies or episodes")
            }
            if !seriesId.isEmpty, item["seriesId"] as? String != seriesId {
                return failure(400, "invalid_request", "episode is outside the selected series")
            }
            let grantId = "demo-grant-" + key
            if let existing = grant(grantId), let manifest = try? JSONSerialization.jsonObject(with: existing.manifest) {
                manifests.append(manifest)
                continue
            }
            if let library = libraryName(item) { item["library"] = ["id": library.id, "name": library.name] }
            let manifest: [String: Any] = [
                "grantId": grantId, "batchKey": batchKey, "clientItemKey": key, "expiresAt": expiry(), "item": item,
                "source": source(Int64(video.count)), "mediaUrl": "/v1/offline/grants/\(grantId)/media",
                "subtitles": [["track": ["index": 2, "type": "Subtitle", "label": "English", "language": "eng", "codec": "srt",
                                         "external": true],
                               "url": "/v1/offline/grants/\(grantId)/subtitles/2"]],
            ]
            let stored = (try? JSONSerialization.data(withJSONObject: manifest)) ?? Data()
            grants.withLock { $0[grantId] = Grant(itemId: itemId, manifest: stored) }
            manifests.append(manifest)
        }
        return json(["batchKey": batchKey, "items": manifests])
    }

    private static func sync(_ body: Data?) -> DemoTransport.Answer {
        guard let body, let fields = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let events = fields["events"] as? [[String: Any]], !events.isEmpty, events.count <= 100 else {
            return failure(400, "invalid_request", "invalid offline progress events")
        }
        let results = events.map { event -> [String: Any] in
            let key = event["clientEventKey"] as? String ?? ""
            let itemId = event["itemId"] as? String ?? ""
            let seen = synced.withLock { log in
                defer { log[key] = itemId }
                return log[key] != nil
            }
            return ["clientEventKey": key, "itemId": itemId, "status": seen ? "duplicate" : "applied"]
        }
        return json(["results": results])
    }

    // MARK: Plumbing

    private static func source(_ size: Int64) -> [String: Any] {
        ["id": sourceId, "name": "Demo · 360p", "container": "mp4", "mimeType": "video/mp4", "sizeBytes": size,
         "bitrate": 400_000,
         "tracks": [["index": 0, "type": "Video", "label": "360p H.264", "codec": "h264"],
                    ["index": 2, "type": "Subtitle", "label": "English", "language": "eng", "codec": "srt", "external": true]]]
    }

    private static func grant(_ id: String) -> Grant? { grants.withLock { $0[id] } }

    private static func expiry() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) + 30 * 86_400_000 }

    /// The title's library, from the demo library's own folders.
    private static func libraryName(_ item: [String: Any]) -> (id: String, name: String)? {
        let id = (item["seriesId"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? item["id"] as? String ?? ""
        guard let title = DemoLibrary.titles.first(where: { $0.id == id }),
              let folder = DemoMedia.folders.first(where: { $0.id == title.folder }) else { return nil }
        return (folder.id, folder.name)
    }

    /// An answer of the demo library's, read.
    private static func library(_ path: String) -> [String: Any]? {
        guard let answer = DemoLibrary.answer(method: "GET", path: path, query: "", body: nil), answer.status == 200 else {
            return nil
        }
        return (try? JSONSerialization.jsonObject(with: answer.body)) as? [String: Any]
    }

    private static func json(_ fields: [String: Any]) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: fields)) ?? Data("{}".utf8),
                             type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static let subtitles = """
        1
        00:00:01,000 --> 00:00:04,000
        A download from the demo hub.

        2
        00:00:05,000 --> 00:00:09,000
        It plays on this device with no network.

        """
}

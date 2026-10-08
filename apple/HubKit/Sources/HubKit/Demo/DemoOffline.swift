import Foundation
import Synchronization

/// The demo hub's offline downloads (#5), answered as the hub answers them
/// (`hub/internal/api/offline.go` and `offline_apple.go`): a series'
/// selection, grants for films and episodes, the file under each grant,
/// renewal, and the watches sent back. Every download is `DemoVideo`, a real
/// MP4 written once per run. The demo library's titles (`DemoLibrary`) are
/// read through its own answers, so the two agree. Nothing real is read or
/// written.
///
/// An Apple grant (`"format": "apple"`) waits on the pretend PC first, as the
/// hub's does: a second in line, then about three seconds preparing (The
/// Matrix, whose picture is converted, eight), then ready with its size and
/// ETag; until then its file is 409 `offline_preparing`. Dune fails once
/// (`ffmpeg_failed`, retryable) until it is retried, and Inception has a
/// French picture subtitle that is left out. A grant without a format is the
/// original, an MP4 here with an SRT beside it.
///
/// An Apple grant's subtitles as they are now (#45): an English and a
/// Hebrew sidecar as WebVTT, each with its signature as its ETag. The tests
/// replace, add and take away tracks (`setSubtitles`) and let a grant expire
/// (`expire`), which renewing undoes.
enum DemoOffline {
    /// A grant: its item, its manifest as JSON, and where its MP4 is.
    private struct Grant {
        let itemId: String
        var manifest: Data
        let apple: Bool
        /// When the pretend PC took it, Unix milliseconds.
        var startedAt: Int64
        var released = false
        /// Dune's one failure, until it is retried.
        var failing = false
        /// How many times its MP4 was made: part of its ETag.
        var builds = 1
    }

    private static let grants = Mutex<[String: Grant]>([:])

    /// A subtitle of the demo's source: its key, facts and WebVTT.
    struct Subtitle: Sendable {
        var key: String
        var language: String
        var label: String
        var rtl: Bool
        var text: String

        init(key: String, language: String, label: String, rtl: Bool = false, text: String) {
            self.key = key
            self.language = language
            self.label = label
            self.rtl = rtl
            self.text = text
        }
    }

    /// Every Apple grant's subtitles, until a test says otherwise.
    static let standardSubtitles = [
        Subtitle(key: "ext-eng", language: "eng", label: "English - SubRip - External",
                 text: "WEBVTT\n\n00:00:00.500 --> 00:00:04.000\nA subtitle kept beside the download.\n\n"
                     + "00:00:04.500 --> 00:00:09.000\nIt plays with no network.\n"),
        Subtitle(key: "ext-heb", language: "heb", label: "Hebrew - SubRip - External", rtl: true,
                 text: "WEBVTT\n\n00:00:00.500 --> 00:00:04.000\nכתובית שנשמרה ליד ההורדה\n\n"
                     + "00:00:04.500 --> 00:00:09.000\nהיא מוצגת גם בלי רשת\n"),
    ]
    private static let subtitleSets = Mutex<[String: [Subtitle]]>([:])
    private static let expired = Mutex<Set<String>>([])

    /// A grant's subtitles from now on, for the tests: replaced, added or taken away.
    static func setSubtitles(_ grantId: String, _ subtitles: [Subtitle]) {
        subtitleSets.withLock { $0[grantId] = subtitles }
    }

    /// The grant expires: its routes answer 410 until it is renewed.
    static func expire(_ grantId: String) {
        _ = expired.withLock { $0.insert(grantId) }
    }

    /// A WebVTT's signature: 32 hexadecimal characters, as the hub's.
    static func signature(_ text: String) -> String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        var second: UInt64 = 0x8422_2325_cbf2_9ce4
        for byte in text.utf8 {
            hash = (hash ^ UInt64(byte)) &* 0x0000_0100_0000_01b3
            second = (second &+ UInt64(byte)) &* 0x9e37_79b9_7f4a_7c15
        }
        return String(format: "%016llx%016llx", hash, second)
    }
    /// The watches the demo hub was sent, for the tests: clientEventKey to itemId.
    static let synced = Mutex<[String: String]>([:])

    static let sourceId = "demo-mp4"

    /// How long an MP4 takes on the pretend PC: a second in line, then three
    /// seconds, or seven for a picture that is converted. Tests go faster.
    struct Pace: Sendable {
        var queuedMillis: Int64 = 1_000
        var preparingMillis: Int64 = 3_000
        var convertingMillis: Int64 = 7_000
    }

    static let pace = Mutex(Pace())

    /// The Matrix (converted), Dune (fails once) and Inception (a subtitle left out).
    static let matrix = String(format: "%032lx", 0xdeb0_0000 + 15)
    static let dune = String(format: "%032lx", 0xdeb0_0000 + 13)
    static let inception = String(format: "%032lx", 0xdeb0_0000 + 14)

    static func answer(method: String, path: String, query: String, body: Data?) async -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 3, parts[0] == "v1", parts[1] == "offline" else { return nil }
        switch (method, parts.count, parts[2]) {
        case ("GET", 5, "series") where parts[4] == "selection":
            return await selection(parts[3], apple: query.contains("format=apple"))
        case ("POST", 3, "prepare"):
            return await prepare(body)
        case ("GET", 5, "grants") where parts[4] == "status":
            return status(parts[3])
        case ("POST", 5, "grants") where parts[4] == "retry":
            return retry(parts[3])
        case ("DELETE", 5, "grants") where parts[4] == "media":
            grants.withLock { all in all[parts[3]]?.released = true }
            return DemoTransport.Answer(204, "")
        case ("GET", 5, "grants") where parts[4] == "media":
            return await media(parts[3])
        case ("GET", 6, "grants") where parts[4] == "subtitles":
            guard let grant = grant(parts[3]), !grant.apple, parts[5] == "2" else {
                return failure(404, "not_found", "no such offline subtitle")
            }
            return DemoTransport.Answer(200, data: Data(subtitles.utf8), type: "application/x-subrip")
        case ("GET", 5, "grants") where parts[4] == "subtitle-tracks":
            return subtitleTracks(parts[3])
        case ("GET", 6, "grants") where parts[4] == "subtitle-tracks", ("HEAD", 6, "grants") where parts[4] == "subtitle-tracks":
            return subtitleTrack(parts[3], key: parts[5])
        case ("POST", 5, "grants") where parts[4] == "renew":
            _ = expired.withLock { $0.remove(parts[3]) }
            guard let grant = grant(parts[3]),
                  var manifest = (try? JSONSerialization.jsonObject(with: grant.manifest)) as? [String: Any] else {
                return failure(404, "not_found", "no such offline grant")
            }
            manifest["expiresAt"] = expiry()
            let renewed = (try? JSONSerialization.data(withJSONObject: manifest)) ?? grant.manifest
            grants.withLock { $0[parts[3]]?.manifest = renewed }
            return DemoTransport.Answer(200, data: renewed, type: "application/json")
        case ("POST", 4, "progress") where parts[3] == "sync":
            return sync(body)
        default:
            return nil
        }
    }

    // MARK: Subtitles kept beside an Apple download (#45)

    private static func subtitles(of grantId: String) -> [Subtitle] {
        subtitleSets.withLock { $0[grantId] } ?? standardSubtitles
    }

    private static func subtitleTracks(_ grantId: String) -> DemoTransport.Answer {
        guard let grant = grant(grantId), grant.apple else { return failure(404, "not_found", "no such offline grant") }
        if expired.withLock({ $0.contains(grantId) }) { return failure(410, "grant_expired", "the offline grant has expired") }
        let tracks = subtitles(of: grantId).map { subtitle -> [String: Any] in
            ["key": subtitle.key, "sourceIndex": 0, "language": subtitle.language, "title": "", "label": subtitle.label,
             "codec": "subrip", "external": true, "default": false, "forced": false, "hearingImpaired": false,
             "rtl": subtitle.rtl, "signature": signature(subtitle.text),
             "url": "/v1/offline/grants/\(grantId)/subtitle-tracks/\(subtitle.key)"]
        }
        var omitted: [[String: Any]] = []
        if grant.itemId == inception {
            omitted.append(["sourceIndex": 3, "language": "fra", "label": "French - PGSSUB", "codec": "hdmv_pgs_subtitle",
                            "external": false, "reason": "picture_subtitle"])
        }
        return json(["grantId": grantId, "format": OfflineFormat.apple, "tracks": tracks, "omitted": omitted])
    }

    private static func subtitleTrack(_ grantId: String, key: String) -> DemoTransport.Answer {
        guard let grant = grant(grantId), grant.apple,
              let subtitle = subtitles(of: grantId).first(where: { $0.key == key }) else {
            return failure(404, "not_found", "no such offline subtitle")
        }
        if expired.withLock({ $0.contains(grantId) }) { return failure(410, "grant_expired", "the offline grant has expired") }
        var answer = DemoTransport.Answer(200, data: Data(subtitle.text.utf8), type: "text/vtt; charset=utf-8")
        answer.headers = ["ETag": "\"\(signature(subtitle.text))\"", "Cache-Control": "private, no-store"]
        return answer
    }

    // MARK: Answers

    private static func selection(_ seriesId: String, apple: Bool) async -> DemoTransport.Answer {
        guard let series = library("/v1/library/items/" + seriesId)?["item"] as? [String: Any],
              series["type"] as? String == "series" else { return failure(400, "invalid_request", "item is not a series") }
        let size = Int64(await DemoVideo.data()?.count ?? 0)
        let estimate = apple ? estimated(size) : size
        let seasons = (library("/v1/library/series/\(seriesId)/seasons")?["items"] as? [[String: Any]]) ?? []
        let episodes = (library("/v1/library/series/\(seriesId)/episodes")?["items"] as? [[String: Any]]) ?? []
        let selectionSeasons = seasons.map { season -> [String: Any] in
            let id = season["id"] as? String ?? ""
            let inSeason = episodes.filter { $0["seasonId"] as? String == id }
            return ["season": season, "episodes": inSeason.map { episode -> [String: Any] in
                var entry: [String: Any] = ["item": episode, "sources": [source(size, apple: apple)],
                                            "estimatedSizeBytes": estimate, "available": size > 0]
                if apple {
                    entry["apple"] = ["estimatedSizeBytes": estimate, "videoConverted": false, "audioConverted": 0,
                                      "omittedSubtitles": 0]
                }
                return entry
            }]
        }
        return json(["series": series, "seasons": selectionSeasons, "episodeCount": episodes.count,
                     "estimatedSizeBytes": estimate * Int64(episodes.count),
                     "playTargetId": episodes.first?["id"] as? String ?? ""])
    }

    private static func prepare(_ body: Data?) async -> DemoTransport.Answer {
        guard let body, let fields = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let batchKey = fields["batchKey"] as? String, !batchKey.isEmpty,
              let items = fields["items"] as? [[String: Any]], !items.isEmpty else {
            return failure(400, "invalid_request", "offline batch is empty or invalid")
        }
        let format = (fields["format"] as? String ?? "").lowercased()
        guard format.isEmpty || format == OfflineFormat.original || format == OfflineFormat.apple else {
            return failure(400, "invalid_request", "format must be original or apple")
        }
        let apple = format == OfflineFormat.apple
        let seriesId = fields["seriesId"] as? String ?? ""
        guard let video = await DemoVideo.data() else { return failure(500, "internal", "the demo video could not be made") }
        var manifests: [Any] = []
        var made: [String: Grant] = [:]
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
            // A key belongs to one format: the other format is a grant of its own.
            let grantId = "demo-grant-" + (apple ? "apple-" : "") + key
            if let existing = grant(grantId), let manifest = try? JSONSerialization.jsonObject(with: existing.manifest) {
                manifests.append(manifest)
                continue
            }
            if let library = libraryName(item) { item["library"] = ["id": library.id, "name": library.name] }
            let size = Int64(video.count)
            var manifest: [String: Any] = [
                "grantId": grantId, "batchKey": batchKey, "clientItemKey": key, "expiresAt": expiry(), "item": item,
                "source": source(size, apple: apple), "mediaUrl": "/v1/offline/grants/\(grantId)/media",
            ]
            if apple {
                manifest["subtitles"] = [Any]()
                manifest["format"] = OfflineFormat.apple
                manifest["apple"] = appleBlock(grantId: grantId, itemId: itemId, size: size)
            } else {
                manifest["subtitles"] = [["track": ["index": 2, "type": "Subtitle", "label": "English", "language": "eng",
                                                    "codec": "srt", "external": true],
                                          "url": "/v1/offline/grants/\(grantId)/subtitles/2"]]
            }
            let stored = (try? JSONSerialization.data(withJSONObject: manifest)) ?? Data()
            made[grantId] = Grant(itemId: itemId, manifest: stored, apple: apple, startedAt: now(), failing: itemId == dune)
            manifests.append(manifest)
        }
        // All or nothing, as the hub's prepare is.
        grants.withLock { all in all.merge(made) { first, _ in first } }
        return json(["batchKey": batchKey, "items": manifests])
    }

    /// Where an Apple grant's MP4 is on the pretend PC, from how long ago it was taken.
    private static func status(_ grantId: String) -> DemoTransport.Answer {
        guard var grant = grant(grantId) else { return failure(404, "not_found", "no such offline grant") }
        guard grant.apple else {
            return json(["grantId": grantId, "format": OfflineFormat.original, "state": "ready", "percent": 100,
                         "queuePosition": 0, "estimatedSizeBytes": 0, "sizeBytes": sourceSize(grant)])
        }
        // Released (or never started): asking queues it again.
        if grant.released {
            grant.released = false
            grant.startedAt = now()
            grant.builds += 1
            grants.withLock { $0[grantId] = grant }
        }
        return json(statusFields(grantId, grant))
    }

    private static func retry(_ grantId: String) -> DemoTransport.Answer {
        guard var grant = grant(grantId) else { return failure(404, "not_found", "no such offline grant") }
        if grant.apple, case .failed = phase(of: grant) {
            grant.failing = false
            grant.startedAt = now()
            grant.builds += 1
            grants.withLock { $0[grantId] = grant }
        }
        return json(statusFields(grantId, grant))
    }

    private static func media(_ grantId: String) async -> DemoTransport.Answer {
        guard let grant = grant(grantId) else { return failure(404, "not_found", "no such offline grant") }
        if grant.apple {
            switch phase(of: grant) {
            case .queued:
                return failure(409, "offline_preparing", "the download is still being prepared", reason: "queued",
                               retryable: true, headers: ["Retry-After": "1"])
            case .preparing:
                return failure(409, "offline_preparing", "the download is still being prepared", reason: "preparing",
                               retryable: true, headers: ["Retry-After": "1"])
            case .failed:
                return failure(409, "offline_failed", "The PC could not make this download's MP4.", reason: "ffmpeg_failed",
                               retryable: true)
            case .ready:
                break
            }
        }
        guard let video = await DemoVideo.data() else { return failure(500, "internal", "the demo video could not be made") }
        var answer = DemoTransport.Answer(200, data: video, type: "video/mp4")
        answer.headers = ["Accept-Ranges": "bytes", "ETag": etag(grantId, grant)]
        return answer
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

    // MARK: The pretend PC

    private enum Phase {
        case queued, preparing(Int), ready, failed
    }

    private static func phase(of grant: Grant) -> Phase {
        let elapsed = now() - grant.startedAt
        let timing = Self.pace.withLock { $0 }
        let working = max(grant.itemId == matrix ? timing.convertingMillis : timing.preparingMillis, 1)
        if elapsed < timing.queuedMillis { return .queued }
        if elapsed < timing.queuedMillis + working {
            return .preparing(Int(Double(elapsed - timing.queuedMillis) / Double(working) * 99))
        }
        return grant.failing ? .failed : .ready
    }

    private static func statusFields(_ grantId: String, _ grant: Grant) -> [String: Any] {
        var fields: [String: Any] = ["grantId": grantId, "format": OfflineFormat.apple, "queuePosition": 0,
                                     "estimatedSizeBytes": estimated(sourceSize(grant))]
        switch phase(of: grant) {
        case .queued:
            fields["state"] = "queued"
            fields["percent"] = 0
            fields["queuePosition"] = 1
        case .preparing(let percent):
            fields["state"] = "preparing"
            fields["percent"] = percent
        case .ready:
            fields["state"] = "ready"
            fields["percent"] = 100
            fields["sizeBytes"] = sourceSize(grant)
            fields["etag"] = etag(grantId, grant)
        case .failed:
            fields["state"] = "failed"
            fields["percent"] = 99
            fields["error"] = ["code": "ffmpeg_failed", "message": "The PC could not make this download's MP4.",
                               "retryable": true]
        }
        return fields
    }

    private static func appleBlock(grantId: String, itemId: String, size: Int64) -> [String: Any] {
        let converted = itemId == matrix
        let video: [String: Any] = converted
            ? ["sourceIndex": 0, "codec": "mpeg4", "outputCodec": "h264", "tag": "avc1", "converted": true,
               "reason": "unsupported_codec", "width": DemoVideo.width, "height": DemoVideo.height]
            : ["sourceIndex": 0, "codec": "h264", "outputCodec": "h264", "tag": "avc1", "converted": false,
               "width": DemoVideo.width, "height": DemoVideo.height]
        var subtitles: [Any] = []
        if itemId == inception {
            subtitles.append(["sourceIndex": 3, "language": "fra", "label": "French - PGSSUB", "codec": "hdmv_pgs_subtitle",
                              "external": false, "forced": false, "hearingImpaired": false, "available": false,
                              "reason": "picture_subtitle"])
        }
        return ["container": "mp4", "mimeType": "video/mp4", "estimatedSizeBytes": estimated(size),
                "statusUrl": "/v1/offline/grants/\(grantId)/status", "video": video, "audio": [Any](), "subtitles": subtitles]
    }

    // MARK: Plumbing

    /// The original as the manifest describes it: an MKV for an Apple
    /// download (which the PC makes into the MP4), the MP4 itself otherwise.
    private static func source(_ size: Int64, apple: Bool) -> [String: Any] {
        if apple {
            return ["id": sourceId, "name": "Demo · 360p", "container": "mkv", "mimeType": "video/x-matroska",
                    "sizeBytes": size, "bitrate": 400_000,
                    "tracks": [["index": 0, "type": "Video", "label": "360p H.264", "codec": "h264"]]]
        }
        return ["id": sourceId, "name": "Demo · 360p", "container": "mp4", "mimeType": "video/mp4", "sizeBytes": size,
                "bitrate": 400_000,
                "tracks": [["index": 0, "type": "Video", "label": "360p H.264", "codec": "h264"],
                           ["index": 2, "type": "Subtitle", "label": "English", "language": "eng", "codec": "srt",
                            "external": true]]]
    }

    /// The MP4's estimate: a little over what it will be, as the hub's is.
    private static func estimated(_ size: Int64) -> Int64 { size + size / 20 }

    private static func sourceSize(_ grant: Grant) -> Int64 {
        guard let manifest = try? JSONDecoder().decode(OfflineManifest.self, from: grant.manifest) else { return 0 }
        return manifest.source.sizeBytes
    }

    private static func etag(_ grantId: String, _ grant: Grant) -> String {
        "\"\(grantId)-\(grant.builds)\""
    }

    private static func grant(_ id: String) -> Grant? { grants.withLock { $0[id] } }

    private static func now() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private static func expiry() -> Int64 { now() + 30 * 86_400_000 }

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

    private static func failure(_ status: Int, _ code: String, _ message: String, reason: String = "", retryable: Bool = false,
                                headers: [String: String] = [:]) -> DemoTransport.Answer {
        var error: [String: Any] = ["code": code, "message": message, "retryable": retryable]
        if !reason.isEmpty { error["reason"] = reason }
        var answer = DemoTransport.Answer(status, data: (try? JSONSerialization.data(withJSONObject: ["error": error])) ?? Data(),
                                          type: "application/json")
        answer.headers = headers
        return answer
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

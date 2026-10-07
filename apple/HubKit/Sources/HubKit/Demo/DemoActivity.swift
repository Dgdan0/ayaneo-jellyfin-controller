import Foundation
import Synchronization

/// The demo hub's Activity (#29): transfers in every state, joined as the hub
/// joins them, with the hub's rules for what may be done to each; qBittorrent's
/// speed limits with the hub's checks; the media PC's disks, one of them
/// nearly full; and a month of releases around today for the agenda.
///
/// Like the hub, a stopped transfer offers Start and a moving one Stop, a
/// removed one is gone from the next read, an action a transfer does not
/// offer is refused, and a change to the limits with a field the hub does not
/// know, or a cap past 1 GiB/s, is a 400.
enum DemoActivity {
    /// The *arr row a transfer joined.
    struct Arr: Sendable {
        var service: String
        var queueId: Int
        var movieId = 0
        var seriesId = 0
        var tmdbId = 0
        var tvdbId = 0
        var state = ""
        var status = ""
        var problem = ""

        var fields: [String: Any] {
            ["service": service, "queueId": queueId, "movieId": movieId, "seriesId": seriesId, "tmdbId": tmdbId,
             "tvdbId": tvdbId, "trackedDownloadState": state, "trackedDownloadStatus": status, "problem": problem]
        }
    }

    /// One transfer as the demo keeps it: the hub's fields, and what changes.
    struct Transfer: Sendable {
        var id: String
        var title: String
        var mediaTitle = ""
        var stage: String
        var clientStage: String
        var clientState = ""
        var progress: Double
        var sizeBytes: Int64
        /// Its speed while it moves; nothing is reported while it is stopped.
        var movingBps: Int64 = 0
        var uploadBps: Int64 = 0
        var etaSeconds: Int64 = -1
        var seeds = 0
        var peers = 0
        var priority = 0
        var indexer = "Demo Indexer"
        var category = ""
        var arr: Arr?
        var warnings: [String] = []
        var queueItems = 0
        var actions: [String]
        var diagnosis: ActivityDiagnosis?
        /// Shown only with `?all=true`, as the hub hides finished transfers.
        var finished = false

        var fields: [String: Any] {
            let moving = stage == Stages.downloading
            var out: [String: Any] = [
                "id": id, "title": title, "mediaTitle": mediaTitle, "stage": stage, "progress": progress,
                "sizeBytes": sizeBytes, "remainingBytes": Int64(Double(sizeBytes) * (1 - progress)),
                "speedBps": moving ? movingBps : 0, "uploadBps": stage == Stages.seeding || moving ? uploadBps : 0,
                "etaSeconds": moving ? etaSeconds : -1, "seeds": seeds, "peers": peers, "protocol": "torrent",
                "client": "qbittorrent", "clientStage": clientStage, "clientState": clientState, "priority": priority,
                "category": category, "indexer": indexer, "torrentHash": String(id.dropFirst(5)),
                "matchConfidence": arr == nil ? "none" : "exact", "warnings": warnings, "queueItems": queueItems,
                "actions": actions,
            ]
            if let arr { out["arr"] = arr.fields }
            if let diagnosis {
                out["diagnosis"] = ["code": diagnosis.code, "title": diagnosis.title, "explanation": diagnosis.explanation,
                                    "evidence": diagnosis.evidence, "nextStep": diagnosis.nextStep,
                                    "needsAttention": diagnosis.needsAttention, "action": diagnosis.action] as [String: Any]
            }
            return out
        }
    }

    /// qBittorrent's two sets of limits, as the hub reports them.
    struct Limits: Sendable {
        var mode = "normal"
        var downloadBps: Int64 = 0
        var uploadBps: Int64 = 5 << 20
        var alternativeDownloadBps: Int64 = 2 << 20
        var alternativeUploadBps: Int64 = 512 << 10

        var fields: [String: Any] {
            ["mode": mode, "downloadBps": downloadBps, "uploadBps": uploadBps, "alternativeDownloadBps": alternativeDownloadBps,
             "alternativeUploadBps": alternativeUploadBps, "queueingEnabled": true, "schedulerEnabled": false,
             "modeSwitchSupported": true, "canControl": true]
        }
    }

    private static func hash(_ pair: String) -> String { String(repeating: pair, count: 20) }

    // The hub's own words for each state (`hub/internal/api/diagnostics.go`).
    private static let downloading = ActivityDiagnosis(
        code: "downloading", title: "Download is in progress", explanation: "The latest queue state reports an active download.",
        nextStep: "Keep downloading; refresh to see updated progress.")
    private static let importing = ActivityDiagnosis(
        code: "importing", title: "Waiting for library import",
        explanation: "The download queue reports an import in progress. Jellyfin availability has not been checked here.",
        nextStep: "Allow Radarr/Sonarr to import the files, then check the title in Library.")
    private static let paused = ActivityDiagnosis(
        code: "paused", title: "Transfer is paused", explanation: "The download client is not running this transfer.",
        nextStep: "Resume when you want it to continue.", action: "start")
    private static let complete = ActivityDiagnosis(
        code: "download_complete", title: "Download is complete",
        explanation: "The client has finished downloading. This does not confirm a successful import or Jellyfin scan.",
        nextStep: "Open the title in Library to check availability.")

    private static let gib: Int64 = 1 << 30
    private static let all = ["stop", "priority_up", "priority_down", "delete", "delete_with_data", "arr_remove",
                              "arr_blocklist_and_search"]

    /// Problems first, then moving, importing, waiting, stopped; then what is finished.
    static let start: [Transfer] = [
        Transfer(id: "qbit:" + hash("d4"), title: "Last.Seen.S01E05.Recovered.1080p.WEB.h264-ETHEL", mediaTitle: "Last Seen",
                 stage: Stages.stuck, clientStage: Stages.seeding, clientState: "stalledUP", progress: 1,
                 sizeBytes: 2_576_980_378, peers: 2, category: "tv-sonarr",
                 arr: Arr(service: "sonarr", queueId: 503, seriesId: 12, tvdbId: 412_256, state: "importBlocked",
                          status: "warning", problem: "Found executable file with extension: '.exe'"),
                 actions: ["stop", "delete", "delete_with_data", "arr_remove", "arr_blocklist_and_search"],
                 diagnosis: ActivityDiagnosis(
                    code: "import_blocked", title: "Import needs attention",
                    explanation: "sonarr reported a problem with this release. Its exact message is shown below.",
                    evidence: ["qBittorrent: stalledUP", "sonarr: importBlocked", "Found executable file with extension: '.exe'"],
                    nextStep: "Check the reported import issue in sonarr. Search for another release only if this one cannot be used.",
                    needsAttention: true)),
        Transfer(id: "qbit:" + hash("07"), title: "The.Expanse.S03.1080p.BluRay.x265-RARBG", stage: Stages.stuck,
                 clientStage: Stages.stuck, clientState: "missingFiles", progress: 0.87, sizeBytes: 22 * gib,
                 warnings: ["missingFiles"], actions: ["delete", "delete_with_data"],
                 diagnosis: ActivityDiagnosis(
                    code: "missing_files", title: "Downloaded files are missing",
                    explanation: "qBittorrent reports missing files. The current state does not tell us whether they were moved, deleted, or the drive disconnected.",
                    evidence: ["qBittorrent: missingFiles"],
                    nextStep: "Check the download drive and save location in qBittorrent before retrying.", needsAttention: true)),
        Transfer(id: "qbit:" + hash("a1"), title: "Severance.S02E09.The.After.Hours.2160p.ATVP.WEB-DL.DDP5.1.H.265-NTb",
                 mediaTitle: "Severance", stage: Stages.downloading, clientStage: Stages.downloading, progress: 0.62,
                 sizeBytes: 6_871_947_673, movingBps: 3_565_158, uploadBps: 225_280, etaSeconds: 690, seeds: 41, peers: 8,
                 priority: 1, category: "tv-sonarr", arr: Arr(service: "sonarr", queueId: 501, seriesId: 11, tvdbId: 371_980),
                 actions: all, diagnosis: downloading),
        Transfer(id: "qbit:" + hash("b2"), title: "The.Mentalist.S01.1080p.BluRay.x264-SHORTBREHD", mediaTitle: "The Mentalist",
                 stage: Stages.downloading, clientStage: Stages.downloading, progress: 0.31, sizeBytes: 43_808_666_419,
                 movingBps: 1_992_294, uploadBps: 51_200, etaSeconds: 15_840, seeds: 12, peers: 3, priority: 2,
                 category: "tv-sonarr", arr: Arr(service: "sonarr", queueId: 502, seriesId: 3, tvdbId: 82_459),
                 queueItems: 23, actions: all, diagnosis: downloading),
        Transfer(id: "qbit:" + hash("f6"), title: "Ted.Lasso.S04E09.1080p.ATVP.WEB-DL.DDP5.1.H.264-FLUX", mediaTitle: "Ted Lasso",
                 stage: Stages.importing, clientStage: Stages.seeding, progress: 1, sizeBytes: 2_040_109_466,
                 uploadBps: 96_000, seeds: 30, peers: 1, category: "tv-sonarr",
                 arr: Arr(service: "sonarr", queueId: 504, seriesId: 7, tvdbId: 383_203),
                 actions: ["stop", "delete", "delete_with_data", "arr_remove"],
                 diagnosis: importing),
        Transfer(id: "qbit:" + hash("c3"), title: "Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX",
                 mediaTitle: "Dune: Part Two", stage: Stages.queued, clientStage: Stages.queued, progress: 0,
                 sizeBytes: 9_771_050_598, movingBps: 2_400_000, etaSeconds: 3_900, seeds: 64, peers: 11, priority: 3,
                 category: "radarr", arr: Arr(service: "radarr", queueId: 601, movieId: 3, tmdbId: 693_134), actions: all,
                 diagnosis: ActivityDiagnosis(
                    code: "queued", title: "Waiting in the download queue",
                    explanation: "qBittorrent has queued this transfer behind its active-transfer limits.",
                    nextStep: "Let an active transfer finish, or review queue limits and priority in qBittorrent.")),
        Transfer(id: "qbit:" + hash("e5"), title: "Gran.Torino.2008.1080p.BluRay.x264-AMIABLE", mediaTitle: "Gran Torino",
                 stage: Stages.stopped, clientStage: Stages.stopped, progress: 0.45, sizeBytes: 8_697_620_480,
                 movingBps: 1_310_720, etaSeconds: 3_660, seeds: 9, peers: 2, priority: 4, category: "radarr",
                 arr: Arr(service: "radarr", queueId: 602, movieId: 9, tmdbId: 13_223),
                 actions: ["start", "priority_up", "priority_down", "delete", "delete_with_data", "arr_remove",
                           "arr_blocklist_and_search"],
                 diagnosis: paused),
        Transfer(id: "qbit:" + hash("08"), title: "Andor.S02E12.2160p.DSNP.WEB-DL.DDP5.1.Atmos.DV.HDR.H.265-FLUX",
                 stage: Stages.seeding, clientStage: Stages.seeding, progress: 1, sizeBytes: 8_482_560_000, uploadBps: 419_840,
                 seeds: 120, peers: 6, actions: ["stop", "delete", "delete_with_data"], diagnosis: complete, finished: true),
        Transfer(id: "qbit:" + hash("09"), title: "Oppenheimer.2023.2160p.UHD.BluRay.x265-SURCODE", stage: Stages.done,
                 clientStage: Stages.stopped, progress: 1, sizeBytes: 31 * gib, actions: ["delete", "delete_with_data"],
                 diagnosis: complete, finished: true),
    ]

    private static let transfers = Mutex<[Transfer]>(start)
    private static let limits = Mutex<Limits>(Limits())

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        switch (method, path) {
        case ("GET", "/v1/activity"):
            return activity(includeFinished: value("all", in: query) == "true")
        case ("GET", "/v1/downloads/bandwidth"):
            return json(limits.withLock { $0 }.fields)
        case ("POST", "/v1/downloads/bandwidth"):
            return setLimits(body)
        case ("GET", "/v1/manage/monitor"):
            return json(monitor)
        case ("GET", "/v1/calendar"):
            return calendar(query: query)
        default:
            break
        }
        let parts = path.split(separator: "/").map(String.init)
        // POST /v1/downloads/{id}/{action}, DELETE /v1/downloads/{id}, POST /v1/queue/{service}/{queueId}/remove
        if method == "POST", parts.count == 4, parts[0] == "v1", parts[1] == "downloads" {
            return act(parts[3], on: parts[2])
        }
        if method == "DELETE", parts.count == 3, parts[0] == "v1", parts[1] == "downloads" {
            return remove(parts[2], deleteFiles: value("deleteFiles", in: query) == "true")
        }
        if method == "POST", parts.count == 5, parts[0] == "v1", parts[1] == "queue", parts[4] == "remove",
           let queueId = Int(parts[3]) {
            return removeFromQueue(service: parts[2], queueId: queueId)
        }
        return nil
    }

    // MARK: Transfers

    private static func activity(includeFinished: Bool) -> DemoTransport.Answer {
        let rows = transfers.withLock { $0 }
        // The hub hides what is finished unless asked; a broken transfer is never hidden.
        let shown = rows.filter { includeFinished || !$0.finished || $0.stage == Stages.stuck }
        let summary: [String: Any] = [
            "downloading": rows.filter { $0.stage == Stages.downloading }.count,
            "queued": rows.filter { $0.stage == Stages.queued }.count,
            "seeding": rows.filter { $0.stage == Stages.seeding }.count,
            "stuck": rows.filter { $0.stage == Stages.stuck }.count,
            "downSpeedBytes": rows.filter { $0.stage == Stages.downloading }.reduce(Int64(0)) { $0 + $1.movingBps },
            "upSpeedBytes": rows.filter { $0.stage == Stages.downloading || $0.stage == Stages.seeding }
                .reduce(Int64(0)) { $0 + $1.uploadBps },
        ]
        return json(["generatedAt": ISO8601DateFormatter().string(from: Date()), "summary": summary,
                     "items": shown.map(\.fields), "partial": [Any]()])
    }

    /// What became of an action, decided under the lock and answered outside it.
    private enum Outcome: Sendable {
        case done(String)
        case gone
        case refused
    }

    private static func act(_ action: String, on id: String) -> DemoTransport.Answer {
        guard ["stop", "start", "priority_up", "priority_down"].contains(action) else {
            return failure(404, "not_found", "No such route in the demo hub")
        }
        let outcome = transfers.withLock { rows -> Outcome in
            guard let index = rows.firstIndex(where: { $0.id == id }) else { return .gone }
            guard rows[index].actions.contains(action) else { return .refused }
            var row = rows[index]
            switch action {
            case "stop":
                if row.stage != Stages.stuck { row.stage = Stages.stopped }
                row.clientStage = Stages.stopped
                row.actions = row.actions.map { $0 == "stop" ? "start" : $0 }
                if row.diagnosis?.needsAttention != true { row.diagnosis = paused }
            case "start":
                let finished = row.progress >= 1
                if row.stage != Stages.stuck { row.stage = finished ? Stages.seeding : Stages.downloading }
                row.clientStage = finished ? Stages.seeding : Stages.downloading
                row.actions = row.actions.map { $0 == "start" ? "stop" : $0 }
                if row.diagnosis?.needsAttention != true { row.diagnosis = finished ? complete : downloading }
            case "priority_up":
                row.priority = max(1, row.priority - 1)
            default:
                row.priority += 1
            }
            rows[index] = row
            return .done(action)
        }
        return answer(outcome)
    }

    private static func remove(_ id: String, deleteFiles: Bool) -> DemoTransport.Answer {
        let outcome = transfers.withLock { rows -> Outcome in
            guard let index = rows.firstIndex(where: { $0.id == id }) else { return .gone }
            guard rows[index].actions.contains(deleteFiles ? "delete_with_data" : "delete") else { return .refused }
            rows.remove(at: index)
            return .done("delete")
        }
        if case .done = outcome { return json(["ok": true, "action": "delete", "deletedFiles": deleteFiles]) }
        return answer(outcome)
    }

    private static func removeFromQueue(service: String, queueId: Int) -> DemoTransport.Answer {
        let outcome = transfers.withLock { rows -> Outcome in
            guard let index = rows.firstIndex(where: { $0.arr?.service == service && $0.arr?.queueId == queueId }) else {
                return .gone
            }
            rows.remove(at: index)
            return .done("queue_remove")
        }
        return answer(outcome)
    }

    private static func answer(_ outcome: Outcome) -> DemoTransport.Answer {
        switch outcome {
        case .done(let action): json(["ok": true, "action": action])
        case .gone: failure(404, "not_found", "This transfer is no longer in the queue")
        case .refused: failure(409, "invalid_state", "This transfer can't do that now")
        }
    }

    // MARK: Speed limits

    /// The hub's checks (`qbittorrent.BandwidthChange.Validate`): only its own
    /// fields, a mode that is normal or alternative, caps named for a set,
    /// something to change, and nothing past 1 GiB/s.
    private static func setLimits(_ body: Data?) -> DemoTransport.Answer {
        let refused = failure(400, "invalid_request", "Provide a valid mode or limits in bytes/sec (0 = unlimited, max 1 GiB/sec)")
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              Set(fields.keys).isSubset(of: ["mode", "limitsFor", "downloadBps", "uploadBps"]) else { return refused }
        let mode = fields["mode"] as? String ?? ""
        let limitsFor = fields["limitsFor"] as? String ?? ""
        let down = (fields["downloadBps"] as? NSNumber)?.int64Value
        let up = (fields["uploadBps"] as? NSNumber)?.int64Value
        let sets = ["normal", "alternative"]
        let hasLimits = down != nil || up != nil
        guard mode.isEmpty || sets.contains(mode), !hasLimits || sets.contains(limitsFor), hasLimits || limitsFor.isEmpty,
              !mode.isEmpty || hasLimits,
              [down, up].compactMap({ $0 }).allSatisfy({ $0 >= 0 && $0 <= gib }) else { return refused }
        let state = limits.withLock { state -> Limits in
            if !mode.isEmpty { state.mode = mode }
            if limitsFor == "alternative" {
                if let down { state.alternativeDownloadBps = down }
                if let up { state.alternativeUploadBps = up }
            } else {
                if let down { state.downloadBps = down }
                if let up { state.uploadBps = up }
            }
            return state
        }
        return json(state.fields)
    }

    // MARK: The PC and the agenda

    /// The media PC: E:, where the downloads go, nearly full.
    private static var monitor: [String: Any] {
        let disks: [[String: Any]] = [
            ["name": "C:\\", "totalBytes": Int64(999_653_638_144), "availableBytes": Int64(312_010_096_640)],
            ["name": "E:\\", "totalBytes": Int64(7_999_997_456_384), "availableBytes": Int64(412_316_860_416)],
            ["name": "F:\\", "totalBytes": Int64(3_999_847_239_680), "availableBytes": Int64(1_870_331_658_240)],
        ]
        let host: [String: Any] = ["os": "Windows 11 Pro", "cpuPercent": 14.5, "memoryTotalBytes": 32 * gib,
                                   "memoryAvailableBytes": 17 * gib, "uptimeSeconds": 363_600, "disks": disks,
                                   "warnings": ["G:\\ space unavailable"]]
        // The server monitor's (#36): one container that is up and well, one that says it is not, one stopped.
        let containers: [[String: String]] = [
            ["name": "jellyseerr", "image": "fallenbagel/jellyseerr:2.7.3", "state": "running", "status": "Up 4 days"],
            ["name": "cleanuparr", "image": "ghcr.io/cleanuparr/cleanuparr:2.1", "state": "running",
             "status": "Up 4 days (unhealthy)"],
            ["name": "flaresolverr", "image": "flaresolverr/flaresolverr:3.3", "state": "exited", "status": "Exited (0) 2 days ago"],
        ]
        let sessions: [[String: Any]] = [
            ["title": "Severance S2E9", "device": "Living Room TV", "client": "Jellyfin Android TV", "method": "Direct play",
             "paused": false],
            ["title": "Gran Torino", "device": "Daniel's iPad", "client": "JellyHub", "method": "Transcoding", "paused": true],
        ]
        return ["host": host, "containers": containers, "sessions": sessions, "dockerWarning": "", "sessionWarning": "",
                "checkedAt": ISO8601DateFormatter().string(from: Date())]
    }

    /// Activity's agenda asks for a month around today; the Upcoming page's
    /// weeks are `DemoDiscover`'s. Releases missed, in the library, today and coming.
    private static func calendar(query: String) -> DemoTransport.Answer? {
        let start = value("start", in: query)
        let end = value("end", in: query)
        guard let first = UpcomingPresentation.date(start), let last = UpcomingPresentation.date(end),
              last.timeIntervalSince(first) > 8 * 86_400 else { return nil }
        let zone = TimeZone(identifier: value("timezone", in: query)) ?? .current
        let today = UpcomingPresentation.today(now: Date(), zone: zone)
        func day(_ offset: Int) -> String { UpcomingPresentation.add(days: offset, to: today) }
        func series(_ id: Int, _ title: String, _ offset: Int, _ hour: String, _ season: Int, _ episode: Int, _ name: String,
                    has: Bool) -> [String: Any] {
            ["id": "sonarr:episode:\(id)", "service": "sonarr",
             "media": ["key": "tmdb:series:\(id)", "type": "series", "title": title, "ids": [String: Any]()] as [String: Any],
             "date": day(offset), "at": day(offset) + "T" + hour + ":00Z", "releaseType": "Episode", "season": season,
             "episode": episode, "episodeTitle": name, "hasFile": has]
        }
        let film: [String: Any] = [
            "id": "radarr:movie:3:Digital release", "service": "radarr",
            "media": ["key": "tmdb:movie:693134", "type": "movie", "title": "Dune: Part Two", "year": 2024,
                      "ids": [String: Any]()] as [String: Any],
            "date": day(3), "releaseType": "Digital release", "hasFile": false]
        let items: [[String: Any]] = [
            series(258_230, "Last Seen", -3, "20:00", 1, 4, "Gone", has: false),
            series(95_396, "Severance", -1, "02:00", 2, 8, "Sweet Vitriol", has: true),
            series(202_555, "Dark Matter", 0, "04:00", 2, 6, "The Box", has: false),
            series(126_308, "Shōgun", 1, "01:00", 2, 1, "Return", has: false),
            film,
            series(136_315, "The Bear", 6, "04:00", 5, 1, "Doors", has: false),
        ]
        // The hub answers only the days asked for: [start, end).
        let asked = items.filter { item in
            guard let date = item["date"] as? String else { return false }
            return date >= start && date < end
        }
        return json(["start": start, "end": end, "timezone": zone.identifier, "items": asked, "partial": [Any]()])
    }

    // MARK: Plumbing

    private static func json(_ object: [String: Any]) -> DemoTransport.Answer {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8)
        return DemoTransport.Answer(200, data: data, type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }

    private static func value(_ name: String, in query: String) -> String {
        for pair in query.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2, kv[0] == name { return kv[1].removingPercentEncoding ?? kv[1] }
        }
        return ""
    }

    /// The transfers and limits the demo starts with, again (for tests).
    static func reset() {
        transfers.withLock { $0 = start }
        limits.withLock { $0 = Limits() }
    }
}

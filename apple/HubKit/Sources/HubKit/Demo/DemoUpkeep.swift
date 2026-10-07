import Foundation
import Synchronization

/// The demo hub's library upkeep (#34): subtitles through a made-up Bazarr and
/// the deleting of a title, answered as the hub answers them: a search gives
/// each candidate a ticket good for one download of that title, a download
/// spends it, a deletion is previewed first and confirmed by the one-use
/// ticket the preview gave, and a ticket that is unknown or spent is a 409.
/// Nothing real is downloaded or deleted: a "deleted" title only leaves the
/// demo library for the rest of the run.
enum DemoUpkeep {
    private struct Track: Sendable {
        var id: String
        var language: String
        var code: String
        var provider: String
        var score: String
        var date: String
        var description: String
        var embedded: Bool
        var forced: Bool
        var hi: Bool
    }

    private struct Candidate: Sendable {
        var language: String
        var provider: String
        var score: Double
        var release: String
        var matches: [String]
        var mismatches: [String]
        var forced: Bool
        var hi: Bool
    }

    private struct State: Sendable {
        /// Installed tracks by item, beside what the demo starts with.
        var added: [String: [Track]] = [:]
        var tickets: [String: (item: String, candidate: Candidate)] = [:]
        var removals: [String: (kind: String, id: String)] = [:]
        var counter = 0
    }

    private static let state = Mutex(State())

    /// What every film and episode starts with: English inside the file.
    private static func starting(_ item: String) -> [Track] {
        [Track(id: "embedded-en-\(item.suffix(6))", language: "English", code: "en", provider: "", score: "", date: "",
               description: "", embedded: true, forced: false, hi: false)]
    }

    /// What a search finds: Hebrew first, as the family watches it.
    private static let candidates = [
        Candidate(language: "he", provider: "OpenSubtitles", score: 94, release: "WEB-DL · 1080p · ETHEL",
                  matches: ["Series", "Season", "Episode", "Release group"], mismatches: [], forced: false, hi: false),
        Candidate(language: "he", provider: "Subscene", score: 88, release: "WEB · 720p",
                  matches: ["Series", "Season", "Episode"], mismatches: ["Release group"], forced: false, hi: true),
        Candidate(language: "en", provider: "OpenSubtitles", score: 97, release: "WEB-DL · 1080p · ETHEL",
                  matches: ["Series", "Season", "Episode", "Release group"], mismatches: [], forced: true, hi: false),
        Candidate(language: "ar", provider: "Podnapisi", score: 71, release: "",
                  matches: ["Series"], mismatches: ["Season", "Episode", "Release group"], forced: false, hi: false),
    ]

    static func answer(method: String, path: String, query: String, body: Data?) -> DemoTransport.Answer? {
        let parts = path.split(separator: "/").map(String.init)
        guard parts.count >= 3, parts[0] == "v1" else { return nil }
        if parts[1] == "library", parts.count >= 5, parts[2] == "items", parts[4] == "subtitles" {
            return subtitles(method: method, item: parts[3], action: parts.count > 5 ? parts[5] : "", body: body)
        }
        if parts[1] == "media", parts.count == 3, method == "POST" {
            switch parts[2] {
            case "removal-preview": return preview(body)
            case "remove": return remove(body)
            default: return nil
            }
        }
        return nil
    }

    // MARK: Subtitles

    private static func subtitles(method: String, item: String, action: String, body: Data?) -> DemoTransport.Answer {
        guard let plan = DemoLibrary.plan(for: item), plan.type == "movie" || plan.type == "episode" else {
            return failure(502, "upstream_down",
                           "Could not match this item to a downloaded file in Arr and Bazarr. Check those services and refresh.")
        }
        switch (method, action) {
        case ("GET", ""):
            let tracks = starting(item) + state.withLock { $0.added[item] ?? [] }
            let records = tracks.map { track -> [String: Any] in
                ["id": track.id, "language": track.language, "code": track.code, "provider": track.provider, "score": track.score,
                 "date": track.date, "description": track.description, "installed": true, "embedded": track.embedded,
                 "forced": track.forced, "hi": track.hi]
            }
            return json(["records": records, "canDownload": true, "warning": ""])
        case ("POST", "search"):
            let choices = state.withLock { all -> [[String: Any]] in
                candidates.map { candidate in
                    all.counter += 1
                    // 48 hex characters, as the hub's tickets are.
                    let ticket = String(format: "%048x", all.counter + 0xdec0_0000)
                    all.tickets[ticket] = (item, candidate)
                    return ["ticket": ticket, "language": candidate.language, "provider": candidate.provider,
                            "score": candidate.score, "release": candidate.release, "matches": candidate.matches,
                            "mismatches": candidate.mismatches, "forced": candidate.forced, "hi": candidate.hi]
                }
            }
            return json(["candidates": choices])
        case ("POST", "download"):
            guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any], fields.count == 1,
                  let ticket = fields["ticket"] as? String, ticket.count == 48 else {
                return failure(400, "invalid_request", "Choose a subtitle from a fresh search")
            }
            let spent = state.withLock { all -> Bool in
                guard let found = all.tickets[ticket], found.item == item else { return false }
                // Consumed before the write: a second tap cannot replay it.
                all.tickets[ticket] = nil
                var track = Track(id: "added-\(all.counter)-\(found.candidate.language)", language: SubtitleLines.language(found.candidate.language),
                                  code: found.candidate.language, provider: found.candidate.provider,
                                  score: "\(Int(found.candidate.score))%", date: "today",
                                  description: found.candidate.release, embedded: false, forced: found.candidate.forced,
                                  hi: found.candidate.hi)
                track.score = "\(Int(found.candidate.score))%"
                // A new external subtitle of the same language and type replaces the old one; embedded ones stay.
                var tracks = all.added[item] ?? []
                tracks.removeAll { $0.code == track.code && $0.forced == track.forced && $0.hi == track.hi }
                tracks.append(track)
                all.added[item] = tracks
                return true
            }
            guard spent else { return failure(409, "invalid_request", "Selection expired. Search again.") }
            return json(["ok": true, "action": "subtitle_download", "jellyfinRefreshStarted": true])
        case ("POST", "refresh"):
            return DemoTransport.Answer(202, #"{"ok":true,"action":"subtitle_item_refresh"}"#)
        default:
            return failure(404, "not_found", "No such route in the demo hub")
        }
    }

    // MARK: Deleting

    private static func preview(_ body: Data?) -> DemoTransport.Answer {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: String],
              let kind = fields["kind"], let id = fields["id"] else {
            return failure(400, "invalid_request", "Invalid removal request")
        }
        let title: String
        let files: [String]
        switch kind {
        case "video":
            guard id.count >= 32, id.prefix(32).allSatisfy({ $0.isHexDigit }) else {
                return failure(400, "invalid_request", "Invalid library item")
            }
            guard let plan = DemoLibrary.plan(for: id) else {
                return failure(400, "invalid_request", "Could not load the server title")
            }
            title = plan.title
            files = plan.files
        case "reading":
            // A book the demo has: a preview and a confirmation, and the book stays in the demo library.
            guard id.hasPrefix("rw_") else { return failure(400, "invalid_request", "Invalid media selection") }
            title = DemoReading.works.first { $0.id == id }?.title
                ?? DemoReading.works.lazy.flatMap(\.books).first { $0.workId == id }?.title ?? "Demo book"
            files = ["\(title).epub"]
        default:
            return failure(400, "invalid_request", "Invalid media selection")
        }
        var description = "Permanently delete these server files and remove the title from the library. Saved copies on this device remain."
        if kind == "video" {
            description += " Jellyfin also removes associated subtitle and metadata files. Download managers may fetch monitored titles again."
        }
        if kind == "reading" {
            description += " All listed editions/issues are included. Reading-list entries for deleted issues are also removed."
        }
        let ticket = state.withLock { all -> String in
            all.counter += 1
            // 64 hex characters.
            let ticket = String(format: "%064x", all.counter + 0xfee1_0000)
            all.removals[ticket] = (kind, id)
            return ticket
        }
        return json(["ticket": ticket, "title": title, "description": description, "files": files, "fileCount": files.count])
    }

    private static func remove(_ body: Data?) -> DemoTransport.Answer {
        guard let body, let fields = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              fields["confirm"] as? Bool == true, let ticket = fields["ticket"] as? String else {
            return failure(400, "invalid_request", "Explicit confirmation is required")
        }
        // The ticket is spent before anything is deleted, and a second use finds nothing.
        let found = state.withLock { all -> (kind: String, id: String)? in
            guard let removal = all.removals[ticket] else { return nil }
            all.removals[ticket] = nil
            return removal
        }
        guard let found else { return failure(409, "invalid_request", "Confirmation expired. Review the title again.") }
        // A film or a series leaves the demo library; a season or an episode of one is not tracked.
        if found.kind == "video", found.id.count == 32 { DemoLibrary.remove(found.id) }
        return json(["ok": true])
    }

    // MARK: Plumbing

    private static func json(_ object: [String: Any]) -> DemoTransport.Answer {
        DemoTransport.Answer(200, data: (try? JSONSerialization.data(withJSONObject: object)) ?? Data("{}".utf8), type: "application/json")
    }

    private static func failure(_ status: Int, _ code: String, _ message: String) -> DemoTransport.Answer {
        DemoTransport.Answer(status, #"{"error":{"code":"\#(code)","message":"\#(message)"}}"#)
    }
}

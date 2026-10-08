import Foundation

/// Keeping an Apple download's subtitles in step with the hub (#45): which
/// to fetch, which to let go of, what an answer means, and how the kept
/// files become the player's subtitle choices.
public enum OfflineSubtitleSync {
    /// On opening Downloads, a download asked about this recently is not asked again.
    public static let recheckMillis: Int64 = 10 * 60_000
    /// Before it plays, what was asked a moment ago stands.
    public static let playRecheckMillis: Int64 = 60_000

    /// Whether to ask the hub about a download's subtitles now.
    public static func due(_ kept: OfflineKeptSubtitles?, now: Int64, freshMillis: Int64 = recheckMillis) -> Bool {
        guard let kept, kept.checkedAt > 0 else { return true }
        return now - kept.checkedAt >= freshMillis || now < kept.checkedAt
    }

    /// An ETag as a signature: without `W/` and its quotes.
    public static func signature(_ etag: String) -> String {
        var text = etag.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("W/") { text.removeFirst(2) }
        if text.count >= 2, text.hasPrefix("\""), text.hasSuffix("\"") { text = String(text.dropFirst().dropLast()) }
        return text
    }

    /// What a list asks of this device.
    public struct Plan: Equatable, Sendable {
        /// Listed tracks with no kept file, or a kept one of another signature.
        public var fetch: [OfflineSubtitleTrack]
        /// Kept keys the hub no longer has: in neither `tracks` nor `omitted`.
        public var remove: [String]
    }

    /// A successful list against what is kept (`hasFile` says whether a kept
    /// key's file is really there). Only a successful list may remove.
    public static func plan(kept: [OfflineKeptSubtitle], list: OfflineSubtitleList, hasFile: (String) -> Bool) -> Plan {
        let held = Dictionary(kept.map { ($0.key, $0) }) { first, _ in first }
        let fetch = list.tracks.filter { track in
            guard !track.key.isEmpty, let mine = held[track.key], hasFile(track.key) else { return !track.key.isEmpty }
            return signature(mine.etag) != track.signature
        }
        let named = Set(list.tracks.map(\.key) + list.omitted.map(\.key).filter { !$0.isEmpty })
        return Plan(fetch: fetch, remove: kept.map(\.key).filter { !named.contains($0) })
    }

    /// What is kept once the fetches are done: each listed track with its new
    /// ETag (`fetched`), else as it was kept with the list's facts, else
    /// nothing (a fetch that failed); then the kept ones the hub cannot hand
    /// over just now (`unreadable`, with their key), as they were. In the list's order.
    public static func merged(kept: [OfflineKeptSubtitle], list: OfflineSubtitleList, fetched: [String: String],
                              hasFile: (String) -> Bool) -> [OfflineKeptSubtitle] {
        let held = Dictionary(kept.map { ($0.key, $0) }) { first, _ in first }
        var out: [OfflineKeptSubtitle] = []
        for track in list.tracks where !track.key.isEmpty {
            if let etag = fetched[track.key] {
                out.append(OfflineKeptSubtitle(track, etag: etag))
            } else if let mine = held[track.key], hasFile(track.key) {
                out.append(OfflineKeptSubtitle(track, etag: mine.etag))
            }
        }
        for omitted in list.omitted where !omitted.key.isEmpty {
            if let mine = held[omitted.key], hasFile(omitted.key), !out.contains(where: { $0.key == omitted.key }) {
                out.append(mine)
            }
        }
        return out
    }

    /// What a failed list or track means.
    public enum Failure: Equatable, Sendable {
        /// 410: renew the grant and ask again.
        case expired
        /// 404 (no such grant, or a hub without these routes), 409 (another
        /// video), 403, 422: nothing to refresh now; what is kept stays.
        case settled
        /// No answer worth acting on (no network, the hub or Jellyfin down,
        /// busy): what is kept stays, and the hub is asked again next time.
        case unreachable
    }

    public static func failure(status: Int?) -> Failure {
        switch status {
        case 410?: .expired
        case 403?, 404?, 409?, 422?: .settled
        default: .unreachable
        }
    }

    // MARK: Refreshing

    /// What one download's refresh came to.
    public enum Outcome: Equatable, Sendable {
        /// A file was fetched, replaced or let go.
        case changed
        /// Nothing new, not due yet, or nothing the hub can refresh now.
        case unchanged
        /// The hub could not be reached: ask about no other download now.
        case unreachable
    }

    /// One download's subtitles brought up to date: its list (an expired
    /// grant renewed, then asked again once), each new or changed track
    /// fetched with the ETag kept as `If-None-Match` and written beside the
    /// MP4, and the files of tracks gone removed. Any other answer keeps what
    /// is there. Quick requests: a slow hub is an unreachable one.
    public static func refresh(_ row: OfflineRow, store: OfflineStore, hub: HubClient, now: Int64,
                               freshMillis: Int64 = recheckMillis) async -> Outcome {
        let before = store.keptSubtitles(row)
        guard row.isApple, due(before, now: now, freshMillis: freshMillis) else { return .unchanged }
        let grantId = row.manifest.grantId
        var list: OfflineSubtitleList?
        for attempt in 0..<2 {
            do throws(HubFailure) {
                if case .file(let data, _) = try await hub.file(HubEndpoints.offlineSubtitleTracks(grantId: grantId, user: row.userId),
                                                                quick: true) {
                    list = try? JSONDecoder().decode(OfflineSubtitleList.self, from: data)
                }
            } catch {
                switch failure(status: error.status) {
                case .unreachable:
                    return .unreachable
                case .settled:
                    break
                case .expired:
                    // Renewing succeeds after a sidecar change, and the renewed
                    // manifest (its new expiry) is kept; a refusal means another video.
                    if attempt == 0,
                       let renewed = try? await hub.data(HubEndpoints.renewOffline(grantId: grantId, user: row.userId)) {
                        if let manifest = try? JSONDecoder().decode(OfflineManifest.self, from: renewed),
                           manifest.isApple, manifest.grantId == grantId {
                            store.updateManifest(row.id, manifest: manifest, json: renewed, now: now)
                        }
                        continue
                    }
                }
            }
            break
        }
        var kept = before ?? OfflineKeptSubtitles()
        kept.checkedAt = now
        guard let list else {
            // Nothing to refresh now (another video, a hub without these routes): kept as it is.
            store.saveKeptSubtitles(row, kept)
            return .unchanged
        }
        let hasFile = { (key: String) in store.hasKeptSubtitle(row, key: key) }
        let work = Self.plan(kept: kept.tracks, list: list, hasFile: hasFile)
        var fetched: [String: String] = [:]
        var missed = false
        for track in work.fetch {
            let mine = kept.tracks.first { $0.key == track.key && hasFile(track.key) }
            do throws(HubFailure) {
                switch try await hub.file(HubEndpoints.offlineSubtitleTrack(track.url, user: row.userId),
                                          ifNoneMatch: mine?.etag, quick: true) {
                case .unchanged:
                    if let mine { fetched[track.key] = mine.etag }
                case .file(let data, let etag):
                    try? store.writeKeptSubtitle(row, key: track.key, data: data)
                    // The ETag received is the signature kept; the list's only when none came.
                    if store.hasKeptSubtitle(row, key: track.key) { fetched[track.key] = etag ?? "\"\(track.signature)\"" }
                }
            } catch {
                // Kept as it was, and asked again at the next refresh rather than in ten minutes.
                missed = true
                if failure(status: error.status) == .unreachable { break }
            }
        }
        for key in work.remove { store.removeKeptSubtitle(row, key: key) }
        kept.tracks = Self.merged(kept: kept.tracks, list: list, fetched: fetched, hasFile: hasFile)
        if missed { kept.checkedAt = before?.checkedAt ?? 0 }
        store.saveKeptSubtitles(row, kept)
        let replaced = fetched.contains { key, etag in before?.tracks.first { $0.key == key }?.etag != etag }
        return replaced || !work.remove.isEmpty ? .changed : .unchanged
    }

    // MARK: Playing

    /// A kept track's index in the plan when the MP4 has no option for it:
    /// past any index Jellyfin gives.
    public static let keptIndexBase = 100_000

    /// The download's subtitle choices: the kept files first (drawn by the
    /// app, `external` with the file's address), each with the index of the
    /// MP4 option it stands for, or a new one for a track that came later;
    /// then the MP4's own options no kept file stands for, each knowing its
    /// place among the MP4's options (`fileOption`).
    public static func tracks(kept: [(track: OfflineKeptSubtitle, file: URL)], mp4: [OfflineAppleSubtitle]) -> [PlaybackTrack] {
        var out: [PlaybackTrack] = []
        var covered = Set<Int>()
        for (position, entry) in kept.enumerated() {
            let option = entry.track.mp4Index.flatMap { mp4.indices.contains($0) ? $0 : nil }
            if let option { covered.insert(option) }
            let name = entry.track.label.isEmpty ? entry.track.title : entry.track.label
            out.append(PlaybackTrack(index: option.map { mp4[$0].sourceIndex } ?? keptIndexBase + position, type: "Subtitle",
                                     label: name, language: entry.track.language, codec: "webvtt",
                                     isDefault: entry.track.isDefault, forced: entry.track.forced,
                                     hearingImpaired: entry.track.hearingImpaired, external: true,
                                     externalUrl: entry.file.absoluteString))
        }
        for (position, track) in mp4.enumerated() where !covered.contains(position) {
            out.append(PlaybackTrack(index: track.sourceIndex, type: "Subtitle", label: track.label, language: track.language,
                                     codec: track.outputCodec, forced: track.forced, hearingImpaired: track.hearingImpaired,
                                     fileOption: position))
        }
        return out
    }
}

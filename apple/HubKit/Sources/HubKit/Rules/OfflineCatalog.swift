import Foundation

// What Downloads shows, and how a download plays without the hub (#5):
// Android's `OfflineCatalog`, `OfflineQueueLabels`, the picker's choices in
// `OfflineSelectionScreen` and the local plan in `OfflineRepository.playbackPlan`,
// held to Android's test cases in `OfflineTests`.

/// A film, or a series with its downloaded episodes, as one poster.
public struct OfflineCatalogEntry: Equatable, Sendable, Identifiable {
    /// The film's or the series' Jellyfin id.
    public let key: String
    public let title: String
    public let isSeries: Bool
    /// In season and episode order.
    public let rows: [OfflineRow]
    /// The Jellyfin library, or empty when the manifest names none.
    public let library: String

    public var id: String { key }
    /// The poster: the series' for a series, the film's own.
    public var poster: String { rows.first?.manifest.item.poster ?? "" }
}

/// A season of a downloaded series: only the episodes on the device.
public struct OfflineCatalogSeason: Equatable, Sendable, Identifiable {
    public let key: String
    public let number: Int
    public let rows: [OfflineRow]

    public var id: String { key }
    /// "Specials" for season 0, as Library says it.
    public var title: String { number == 0 ? "Specials" : "Season \(number)" }
}

/// The best downloaded episode to go on with, without asking the hub.
public struct OfflineCatalogPlayTarget: Equatable, Sendable {
    public enum Kind: Equatable, Sendable { case resume, next, start }

    public let row: OfflineRow
    public let kind: Kind
    public let positionMillis: Int64
}

/// A watch, kept on this device or reported by the server, in one shape.
public struct OfflineCatalogProgress: Equatable, Sendable {
    public let positionMillis: Int64
    public let durationMillis: Int64
    public let updatedAtMillis: Int64

    public init(positionMillis: Int64, durationMillis: Int64, updatedAtMillis: Int64) {
        self.positionMillis = positionMillis
        self.durationMillis = durationMillis
        self.updatedAtMillis = updatedAtMillis
    }

    public var isComplete: Bool { ResumeRules.isFinished(positionMillis: positionMillis, durationMillis: durationMillis) }

    public var resumePosition: Int64 {
        ResumeRules.resumePosition(positionMillis: positionMillis, durationMillis: durationMillis)
    }

    /// The server's view of a watch; a played item sits at its end.
    public static func fromServer(positionMillis: Int64, durationMillis: Int64, played: Bool, lastPlayedAt: Int64)
        -> OfflineCatalogProgress {
        OfflineCatalogProgress(positionMillis: played ? durationMillis : positionMillis, durationMillis: durationMillis,
                               updatedAtMillis: lastPlayedAt)
    }

    /// A download keeps its own watch unless the server saw a later one:
    /// someone carried on on the TV after downloading. Without a server date
    /// the local watch stands, which is what an older hub gets.
    public static func newer(_ local: OfflineCatalogProgress, _ server: OfflineCatalogProgress?) -> OfflineCatalogProgress {
        if let server, server.updatedAtMillis > local.updatedAtMillis { return server }
        return local
    }
}

/// Downloads shaped for the page, pure so they are tested.
public enum OfflineCatalog {
    public static let fallbackMovies = "Movies"
    public static let fallbackSeries = "Series"

    /// One poster per film or series, A to Z.
    public static func titles(_ completed: [OfflineRow], batchTitles: [String: String] = [:],
                              libraryNames: [String: String] = [:]) -> [OfflineCatalogEntry] {
        let groups = Dictionary(grouping: completed) { row in
            row.manifest.item.seriesId.isEmpty ? row.manifest.item.id : row.manifest.item.seriesId
        }
        return groups.values.map { rows -> OfflineCatalogEntry in
            let ordered = rows.sorted(by: episodeOrder)
            let first = ordered[0]
            let item = first.manifest.item
            let key = item.seriesId.isEmpty ? item.id : item.seriesId
            let title = !item.seriesTitle.isEmpty ? item.seriesTitle : (batchTitles[first.batchId] ?? item.title)
            let library = ordered.lazy.map(\.manifest.library).first { !$0.isEmpty } ?? libraryNames[key] ?? ""
            return OfflineCatalogEntry(key: key, title: title, isSeries: !item.seriesId.isEmpty, rows: ordered,
                                       library: library)
        }.sorted { $0.title.lowercased() < $1.title.lowercased() }
    }

    /// The titles under their libraries, A to Z; a title whose library is not
    /// known yet goes under Movies or Series, last.
    public static func byLibrary(_ entries: [OfflineCatalogEntry]) -> [(library: String, entries: [OfflineCatalogEntry])] {
        let groups = Dictionary(grouping: entries) { entry in
            entry.library.isEmpty ? (entry.isSeries ? fallbackSeries : fallbackMovies) : entry.library
        }
        return groups.map { (library: $0.key, entries: $0.value.sorted { $0.title.lowercased() < $1.title.lowercased() }) }
            .sorted { left, right in
                let leftUnknown = left.entries.allSatisfy { $0.library.isEmpty }
                let rightUnknown = right.entries.allSatisfy { $0.library.isEmpty }
                return leftUnknown == rightUnknown ? left.library.lowercased() < right.library.lowercased() : !leftUnknown
            }
    }

    /// A downloaded series' seasons, from the episodes on the device only.
    public static func seasons(seriesId: String, _ completed: [OfflineRow]) -> [OfflineCatalogSeason] {
        let rows = completed.filter { $0.manifest.item.seriesId == seriesId }
        let groups = Dictionary(grouping: rows) { row in
            row.manifest.item.seasonId.isEmpty ? "season-\(row.manifest.item.seasonNumber)" : row.manifest.item.seasonId
        }
        return groups.map { key, rows in
            OfflineCatalogSeason(key: key, number: rows[0].manifest.item.seasonNumber,
                                 rows: rows.sorted { $0.manifest.item.indexNumber < $1.manifest.item.indexNumber })
        }.sorted { $0.number < $1.number }
    }

    /// The downloaded episode to go on with, by the same rules as playback:
    /// the episode left part-watched most recently, else the first not
    /// finished. Start when nothing has been watched here.
    public static func playTarget(_ rows: [OfflineRow], progress: [String: OfflineCatalogProgress]) -> OfflineCatalogPlayTarget? {
        let ordered = rows.sorted { left, right in
            episodeOrder(left, right) || (!episodeOrder(right, left)
                && left.manifest.item.title.lowercased() < right.manifest.item.title.lowercased())
        }
        let resumed = ordered.compactMap { row -> (row: OfflineRow, position: Int64, at: Int64)? in
            guard let watch = progress[row.itemId], watch.resumePosition > 0 else { return nil }
            return (row, watch.resumePosition, watch.updatedAtMillis)
        }.max { $0.at < $1.at }
        if let resumed { return OfflineCatalogPlayTarget(row: resumed.row, kind: .resume, positionMillis: resumed.position) }
        guard let next = ordered.first(where: { !(progress[$0.itemId]?.isComplete ?? false) }) else { return nil }
        return OfflineCatalogPlayTarget(row: next, kind: progress.isEmpty ? .start : .next, positionMillis: 0)
    }

    private static func episodeOrder(_ left: OfflineRow, _ right: OfflineRow) -> Bool {
        let a = left.manifest.item
        let b = right.manifest.item
        return a.seasonNumber == b.seasonNumber ? a.indexNumber < b.indexNumber : a.seasonNumber < b.seasonNumber
    }
}

/// How a download reads on its row in the manager (Android's
/// `OfflineQueueLabels`): its state as a chip by the title, then one quiet
/// line of figures.
public enum OfflineQueueLabels {
    public enum Tone: Equatable, Sendable { case good, waiting, bad, quiet }

    /// Moving, queued, paused and preparing quiet; waiting amber (it will try
    /// again by itself); failed red; downloaded green.
    public static func chip(_ state: OfflineState) -> (word: String, tone: Tone) {
        switch state {
        case .downloading: ("Downloading", .quiet)
        case .queued: ("Queued", .quiet)
        case .paused: ("Paused", .quiet)
        case .waiting: ("Waiting", .waiting)
        case .failed: ("Failed", .bad)
        case .complete: ("Downloaded", .good)
        case .preparing: ("Preparing", .quiet)
        }
    }

    /// "90.8 MB of 3.9 GB · 2.1 MB/s · 4m left": how much has arrived and,
    /// while it moves, how fast and how long. A finished download is its size;
    /// an Apple download not ready yet is its estimate, "About 7.9 GB", and
    /// while the PC makes it, "Preparing on the PC · 40% · About 7.9 GB".
    public static func figures(_ row: OfflineRow) -> String {
        var parts: [String] = []
        if row.state == .preparing { parts.append(onThePC(row)) }
        if row.isApple && row.etag.isEmpty && row.state != .complete {
            parts.append("About " + Fmt.bytes(row.totalBytes))
        } else if row.state == .complete || row.bytesDownloaded <= 0 {
            parts.append(Fmt.bytes(row.totalBytes))
        } else {
            parts.append("\(Fmt.bytes(row.bytesDownloaded)) of \(Fmt.bytes(row.totalBytes))")
        }
        if row.state == .downloading, row.speedBytesPerSecond > 0 {
            parts.append(Fmt.speed(row.speedBytesPerSecond))
            parts.append("\(Fmt.eta(max(row.totalBytes - row.bytesDownloaded, 0) / row.speedBytesPerSecond)) left")
        }
        return parts.joined(separator: " · ")
    }

    /// Where an Apple download's MP4 is on the PC: "Preparing on the PC · 40%",
    /// or its place in the PC's line, "Next on the PC", "3rd in line on the PC".
    public static func onThePC(_ row: OfflineRow) -> String {
        switch row.hubQueuePosition {
        case 0: "Preparing on the PC · \(row.hubPercent)%"
        case 1: "Next on the PC"
        default: "\(ordinal(row.hubQueuePosition)) in line on the PC"
        }
    }

    /// How full its bar is, 0 to 1: the bytes arrived, or while the PC makes
    /// the MP4, how far the PC is (drawn in a quieter tone).
    public static func fraction(_ row: OfflineRow) -> Double {
        row.state == .preparing ? Double(min(max(row.hubPercent, 0), 100)) / 100 : row.progress
    }

    /// "1st", "2nd", "3rd", "4th", "11th", "22nd".
    static func ordinal(_ number: Int) -> String {
        let suffix: String
        switch (number % 10, number % 100) {
        case (_, 11...13): suffix = "th"
        case (1, _): suffix = "st"
        case (2, _): suffix = "nd"
        case (3, _): suffix = "rd"
        default: suffix = "th"
        }
        return "\(number)\(suffix)"
    }

    /// A batch's line: "3/8 complete · 1.2 GB of 4.8 GB".
    public static func batchLine(_ batch: OfflineBatch) -> String {
        "\(batch.completeCount)/\(batch.jobs.count) complete · \(Fmt.bytes(batch.downloadedBytes)) of \(Fmt.bytes(batch.totalBytes))"
    }
}

/// The picker's choices (Android's `OfflineSelectionScreen`): which episodes
/// can be chosen, the quick choices, and its words.
public enum OfflineSelection {
    /// Episodes in the seasons shown that can be downloaded.
    public static func available(_ seasons: [OfflineSelectionSeason]) -> [OfflineSelectionItem] {
        seasons.flatMap(\.episodes).filter(\.available)
    }

    /// Those not already downloaded or coming.
    public static func selectable(_ seasons: [OfflineSelectionSeason], stored: (String) -> Bool) -> [OfflineSelectionItem] {
        available(seasons).filter { !stored($0.item.id) }
    }

    /// The next `count` episodes to watch: from the one Play would start (or
    /// the first unwatched), skipping watched ones after it.
    public static func next(_ count: Int, among items: [OfflineSelectionItem], playTargetId: String) -> [LibraryItem] {
        let values = items.map(\.item)
        let start = values.firstIndex { $0.id == playTargetId } ?? values.firstIndex { !$0.played } ?? 0
        return Array(values.dropFirst(start).filter { !$0.played || $0.id == playTargetId }.prefix(max(count, 0)))
    }

    /// "3 selected · 1.2 GB".
    public static func counter(_ ids: Set<String>, among items: [OfflineSelectionItem]) -> String {
        "\(ids.count) selected · \(Fmt.bytes(size(ids, among: items)))"
    }

    public static func size(_ ids: Set<String>, among items: [OfflineSelectionItem]) -> Int64 {
        items.filter { ids.contains($0.item.id) }.reduce(0) { $0 + $1.estimatedSizeBytes }
    }

    /// "Download 3 episodes?"
    public static func confirmTitle(_ count: Int) -> String {
        "Download \(count) episode\(count == 1 ? "" : "s")?"
    }

    /// "1.2 GB selected · 20 GB free" over where and what is downloaded: the
    /// original file, or an MP4 the PC makes for this device ("apple").
    public static func confirmDetail(bytes: Int64, free: Int64, source: OfflineSource?, format: String = "") -> String {
        if format == OfflineFormat.apple {
            return "About \(Fmt.bytes(bytes)) selected · \(Fmt.bytes(free)) free\n"
                + "This device · kept out of backups · An MP4 the PC makes for this device"
        }
        let quality = source.map { $0.name.isEmpty ? $0.container.uppercased() : $0.name } ?? ""
        return "\(Fmt.bytes(bytes)) selected · \(Fmt.bytes(free)) free\nThis device · kept out of backups · Original"
            + (quality.isEmpty ? "" : " · \(quality)")
    }

    /// A batch's key, and each item's in it: this device's own, in the
    /// hub's alphabet (`^[A-Za-z0-9._:-]{1,120}$`).
    public static func batchKey(now: Int64, for id: String) -> String {
        "offline-\(now)-" + keyPart(id, 8)
    }

    public static func itemKey(batchKey: String, itemId: String) -> String {
        batchKey + "-" + keyPart(itemId, 12)
    }

    private static func keyPart(_ value: String, _ length: Int) -> String {
        String(value.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(length))
    }
}

/// A download played from the device (Android's `playbackPlan`): the plan
/// the player opens, made here without the hub.
public enum OfflinePlayback {
    /// The prefix of a session played from a download: nothing is sent to the
    /// hub for it, and its watch is kept here (`OfflineStore.rememberPlayback`).
    public static let sessionPrefix = "offline:"

    public static func isOffline(_ sessionId: String) -> Bool { sessionId.hasPrefix(sessionPrefix) }

    /// `row` at `file`, from where it was left here, else where the server had
    /// it when it was downloaded; Start over from the beginning. `siblings`
    /// are the series' other downloads, for Previous and Next; `subtitles`
    /// the subtitle files beside it, by track index.
    public static func plan(_ row: OfflineRow, file: URL, saved: OfflineCatalogProgress?, mode: PlaybackStartMode,
                            siblings: [OfflineRow], subtitles: [Int: URL]) -> PlaybackPrepareResponse {
        let item = row.manifest.item
        let source = row.manifest.source
        let duration = max(Int64(item.runtimeSeconds) * 1_000, saved?.durationMillis ?? 0)
        let watch = saved ?? OfflineCatalogProgress.fromServer(positionMillis: Int64(item.positionSeconds) * 1_000,
                                                               durationMillis: duration, played: item.played,
                                                               lastPlayedAt: row.manifest.lastPlayedAt)
        let position = mode == .restart ? 0 : ResumeRules.resumePosition(positionMillis: watch.positionMillis, durationMillis: duration)
        let audio: [PlaybackTrack]
        let tracks: [PlaybackTrack]
        var selectedSubtitle: Int?
        if let apple = row.manifest.apple, row.isApple {
            // The MP4's own tracks, in its order: the nth audio option is
            // `apple.audio[n]`, the nth subtitle option the nth kept one; none on.
            audio = apple.audio.map { track in
                PlaybackTrack(index: track.sourceIndex, type: "Audio", label: track.label, language: track.language,
                              codec: track.outputCodec, channels: track.outputChannels, isDefault: track.isDefault)
            }
            tracks = apple.keptSubtitles.map { track in
                PlaybackTrack(index: track.sourceIndex, type: "Subtitle", label: track.label, language: track.language,
                              codec: track.outputCodec, forced: track.forced, hearingImpaired: track.hearingImpaired)
            }
        } else {
            audio = source.tracks.filter { $0.type.lowercased() == "audio" }
            let embedded = source.tracks.filter { $0.type.lowercased() == "subtitle" && !$0.external }
            let external = row.manifest.subtitles.compactMap { subtitle -> PlaybackTrack? in
                guard let local = subtitles[subtitle.track.index] else { return nil }
                var track = subtitle.track
                track.external = true
                track.externalUrl = local.absoluteString
                return track
            }
            tracks = embedded + external
            selectedSubtitle = tracks.first(where: \.isDefault)?.index
        }
        let ordered = siblings.filter { !$0.manifest.item.seriesId.isEmpty && $0.manifest.item.seriesId == item.seriesId }
            .sorted { left, right in
                let a = left.manifest.item
                let b = right.manifest.item
                return a.seasonNumber == b.seasonNumber ? a.indexNumber < b.indexNumber : a.seasonNumber < b.seasonNumber
            }
        let index = ordered.firstIndex { $0.itemId == item.id }
        let previous = index.flatMap { $0 > 0 ? ordered[$0 - 1] : nil }
        let next = index.flatMap { $0 + 1 < ordered.count ? ordered[$0 + 1] : nil }
        let apple = row.isApple ? row.manifest.apple : nil
        return PlaybackPrepareResponse(
            sessionId: sessionPrefix + row.id, item: playbackItem(item), positionMillis: position, durationMillis: duration,
            mediaUrl: file.absoluteString, mimeType: apple?.mimeType ?? source.mimeType, playMethod: "Offline",
            bitrate: source.bitrate, width: apple?.video.width ?? 0, height: apple?.video.height ?? 0,
            sources: [PlaybackSource(id: source.id, name: source.name, container: row.manifest.container,
                                     sizeBytes: row.totalBytes, bitrate: source.bitrate)],
            audioTracks: audio, subtitleTracks: tracks, selectedMediaSourceId: source.id,
            selectedAudioIndex: (audio.first(where: \.isDefault) ?? audio.first)?.index,
            selectedSubtitleIndex: selectedSubtitle,
            previousItem: previous.map { playbackItem($0.manifest.item) }, nextItem: next.map { playbackItem($0.manifest.item) })
    }

    /// Which of the file's own options a track is: its place among the plan's
    /// tracks of its kind, which an Apple download lists in the MP4's order.
    /// Nil for no track (subtitles off) or one the plan does not have.
    public static func optionPosition(_ tracks: [PlaybackTrack], index: Int?) -> Int? {
        guard let index, index >= 0 else { return nil }
        return tracks.firstIndex { $0.index == index }
    }

    static func playbackItem(_ item: LibraryItem) -> PlaybackItem {
        PlaybackItem(id: item.id, type: item.type, title: item.title, seriesTitle: item.seriesTitle, seriesId: item.seriesId,
                     seasonId: item.seasonId, seasonNumber: item.seasonNumber, episodeNumber: item.indexNumber)
    }

    /// A key the hub accepts for a watch: the item, when, and a little chance.
    public static func eventKey(itemId: String, now: Int64) -> String {
        let item = String(itemId.filter { $0.isASCII && ($0.isLetter || $0.isNumber) }.prefix(12))
        return "\(item)-\(now)-" + String(UUID().uuidString.filter(\.isHexDigit).prefix(8)).lowercased()
    }
}

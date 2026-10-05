import Foundation

// The request side's wording and rules (#17): Discover's featured title, a
// title's pipeline and facts, the request form, the release list and a
// filmography. Ports of Android's `DiscoverFeaturePolicy`, `PipelineChip`,
// `RequestedTitles`, `RequestFlow` and `ReleasesScreen`, held to their cases.

/// How a pipeline stage's chip looks: Android's `PipelineChip.Tone`.
public enum PipelineTone: Equatable, Sendable {
    case done, active, failed, pending

    public static func of(_ state: String) -> PipelineTone {
        switch state {
        case "done": .done
        case "active": .active
        case "failed", "stuck": .failed
        default: .pending
        }
    }
}

public enum PipelineLines {
    /// The chip's words: the short name, and the percent while its stage runs.
    public static func chip(_ stage: PipelineStage) -> String {
        let name = stage.short.isEmpty ? stage.label : stage.short
        guard PipelineTone.of(stage.state) == .active, stage.progress > 0 else { return name }
        return name + " " + String(format: "%.0f%%", locale: Locale(identifier: "en_US_POSIX"), stage.progress * 100)
    }

    /// What VoiceOver says for a chip.
    public static func spoken(_ stage: PipelineStage) -> String {
        let name = stage.label.isEmpty ? stage.short : stage.label
        let state = switch PipelineTone.of(stage.state) {
        case .done: "done"
        case .active: "under way"
        case .failed: "needs attention"
        case .pending: "not yet"
        }
        return name + ", " + state
    }

    /// The hub's summary is shown only while something moves or is wrong:
    /// with nothing in motion it repeats what five pending chips already say.
    public static func showsSummary(_ pipeline: Pipeline) -> Bool {
        !pipeline.summary.trimmingCharacters(in: .whitespaces).isEmpty
            && pipeline.stages.contains { PipelineTone.of($0.state) != .pending && PipelineTone.of($0.state) != .done }
    }

    /// The summary's colour, 0xAARRGGBB: green once it is in the library, red
    /// when blocked or deleted, amber otherwise.
    public static func summaryColor(availability: String) -> UInt32 {
        switch availability {
        case "available": 0xFF3E_CB80
        case "blocked", "deleted": 0xFFE0_685C
        default: 0xFFF5_C75A
        }
    }

    /// Whether the page should ask again soon: a stage is under way.
    public static func isMoving(_ pipeline: Pipeline) -> Bool {
        pipeline.stages.contains { PipelineTone.of($0.state) == .active }
    }

    /// When to read a title's pipeline again (Android's `PollSchedule` with
    /// `PIPELINE`): every 4 s while a stage is under way, not at all when
    /// nothing moves, and after failures 5, 15, then 30 s.
    public static func nextPoll(moving: Bool, failures: Int) -> Duration? {
        if failures > 0 {
            let backoff = [5, 15, 30][min(failures - 1, 2)]
            return .seconds(max(backoff, 4))
        }
        return moving ? .seconds(4) : nil
    }
}

/// Discover's featured title: the first card of the first row, when it has
/// a picture, a name and something to say. Android also wants 640 dp of
/// width; on Apple the card stacks on a phone instead (the prototype's
/// iPhone layout). Android's `DiscoverFeaturePolicy.mediaFeature`.
public enum DiscoverFeature {
    public static func feature(_ items: [MediaHit]) -> MediaHit? {
        guard let first = items.first,
              !first.media.title.trimmingCharacters(in: .whitespaces).isEmpty,
              !(first.media.backdrop.isEmpty && first.media.poster.isEmpty),
              !first.subtitle.isEmpty || !first.overview.isEmpty || first.media.year > 0 else { return nil }
        return first
    }

    /// The row without the title that is featured above it.
    public static func shelf(_ items: [MediaHit]) -> [MediaHit] {
        feature(items) == nil ? items : Array(items.dropFirst())
    }

    /// "FEATURED · " then where the title is, or that it is not in the library.
    public static func mark(_ availability: String) -> String {
        (Availability.label(availability) ?? "Not in your library").uppercased()
    }

    /// The hub's "2008 · Movie", else the year.
    public static func meta(_ hit: MediaHit) -> String {
        if !hit.subtitle.isEmpty { return hit.subtitle }
        return hit.media.year > 0 ? String(hit.media.year) : ""
    }
}

/// Titles requested on this device, this session: a card shows its new state
/// at once, before the hub's next read says so. Android's `RequestedTitles`.
public struct RequestedTitles: Equatable, Sendable {
    private struct Record: Equatable, Sendable {
        let availability: String
        let requestId: Int
    }

    private var records: [String: Record] = [:]
    public private(set) var revision = 0

    public init() {}

    public mutating func record(key: String, availability: String, requestId: Int) {
        guard !key.isEmpty else { return }
        records[key] = Record(availability: availability.isEmpty ? "requested" : availability, requestId: requestId)
        revision += 1
    }

    /// The hit as it is now: requested here while the hub still says it is not
    /// in the library, and so no longer offering Request. The hub's own word
    /// wins as soon as it has one.
    public func apply(_ hit: MediaHit) -> MediaHit {
        guard let record = records[hit.media.key],
              hit.availability == "not_in_library" || hit.availability == "unknown" || hit.availability.isEmpty else { return hit }
        var out = hit
        out.availability = record.availability
        out.requestId = record.requestId
        out.actions.removeAll { $0 == "request" }
        return out
    }

    public func availability(key: String, hub: String) -> String {
        guard let record = records[key], hub == "not_in_library" || hub == "unknown" || hub.isEmpty else { return hub }
        return record.availability
    }
}

/// A requestable title's page: Android's `MediaDetailScreen` lines.
public enum TitleFacts {
    /// The eyebrow: where the title is, in the accent, or "NOT IN YOUR
    /// LIBRARY" in soft white (`accent` false).
    public static func eyebrow(_ availability: String) -> (text: String, accent: Bool) {
        if let label = Availability.label(availability) { return (label.uppercased(), true) }
        return ("NOT IN YOUR LIBRARY", false)
    }

    /// Year · runtime · seasons · genres · rating, each only when known.
    public static func facts(_ detail: MediaDetail) -> [String] {
        var parts: [String] = []
        if detail.media.year > 0 { parts.append(String(detail.media.year)) }
        if detail.runtimeMinutes > 0 { parts.append("\(detail.runtimeMinutes) min") }
        if detail.seasons > 0 { parts.append(detail.seasons == 1 ? "1 season" : "\(detail.seasons) seasons") }
        let genres = detail.genres.filter { !$0.isEmpty }.joined(separator: ", ")
        if !genres.isEmpty { parts.append(genres) }
        if detail.rating > 0 { parts.append("★ " + String(format: "%.1f", locale: Locale(identifier: "en_US_POSIX"), detail.rating)) }
        return parts
    }

    /// "Season 2 · 23 episodes", "Specials · 1 episode", or the name alone.
    public static func seasonPill(_ season: SeasonOption) -> String {
        let name = season.name.trimmingCharacters(in: .whitespaces).isEmpty ? EpisodeLabel.season(season.number) : season.name
        guard season.episodeCount > 0 else { return name }
        return name + " · " + episodes(season.episodeCount)
    }

    public static func episodes(_ count: Int) -> String { count == 1 ? "1 episode" : "\(count) episodes" }

    /// The status line under the page, when there is no news: where it is.
    public static func statusLabel(_ availability: String) -> String {
        if let label = Availability.label(availability) { return label }
        return availability == "unknown" ? "Status unavailable" : "Not in library"
    }
}

/// The request form's choices and what it sends (Android's `RequestFlow` with
/// `FormModel`): the profile and folder the server marks default, every
/// season until the person picks some, and nothing a request does not need.
public struct RequestDraft: Equatable, Sendable {
    public let options: RequestOptions
    public var profileIndex: Int
    public var folderIndex: Int
    public var allSeasons = true
    public var ticked: Set<Int> = []

    public init(options: RequestOptions) {
        self.options = options
        profileIndex = options.profiles.firstIndex { $0.isDefault } ?? 0
        folderIndex = options.rootFolders.firstIndex { $0.isDefault } ?? 0
    }

    public var isSeries: Bool { options.type == "series" }
    /// The seasons a series can ask for: those with episodes, Specials included.
    public var seasons: [SeasonOption] { options.seasons.filter { $0.episodeCount > 0 } }

    public func heading(fallbackTitle: String) -> String {
        "Request " + (options.title.isEmpty ? fallbackTitle : options.title)
    }

    /// "Sonarr · 7 seasons · 151 episodes": the server, then for a series its size.
    public var subtitle: String {
        var parts = [options.serverName].filter { !$0.isEmpty }
        if isSeries, !seasons.isEmpty {
            parts.append(seasons.count == 1 ? "1 season" : "\(seasons.count) seasons")
            let episodes = seasons.map(\.episodeCount).reduce(0, +)
            if episodes > 0 { parts.append(TitleFacts.episodes(episodes)) }
        }
        return parts.joined(separator: " · ")
    }

    public var profile: RequestOption? { options.profiles.indices.contains(profileIndex) ? options.profiles[profileIndex] : nil }
    public var folder: RootFolderOption? {
        options.rootFolders.indices.contains(folderIndex) ? options.rootFolders[folderIndex] : nil
    }

    /// "209 GB free" under a folder, empty when its space is not known.
    public static func freeSpace(_ folder: RootFolderOption) -> String {
        folder.freeSpaceBytes > 0 ? Fmt.bytes(folder.freeSpaceBytes) + " free" : ""
    }

    public static func folderName(_ folder: RootFolderOption) -> String {
        folder.label.isEmpty ? folder.path : folder.label
    }

    public static func seasonName(_ season: SeasonOption) -> String {
        season.name.trimmingCharacters(in: .whitespaces).isEmpty ? EpisodeLabel.season(season.number) : season.name
    }

    /// "23 episodes · 2008".
    public static func seasonDetail(_ season: SeasonOption) -> String {
        [TitleFacts.episodes(season.episodeCount), season.year > 0 ? String(season.year) : ""]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    public mutating func toggle(season number: Int) {
        if ticked.contains(number) { ticked.remove(number) } else { ticked.insert(number) }
    }

    /// What `POST /v1/requests` gets. A series with no season ticked asks for all.
    public func body() -> CreateRequestBody {
        var seasonsToSend: CreateRequestBody.Seasons?
        if isSeries {
            seasonsToSend = allSeasons || ticked.isEmpty ? .all : .numbers(ticked.sorted())
        }
        return CreateRequestBody(key: options.key, seasons: seasonsToSend, profileId: profile.map(\.id),
                                 rootFolder: folder.map(\.path), serverId: options.serverId)
    }
}

/// The release list's lines (Android's `ReleasesScreen`).
public enum ReleaseLines {
    /// The square on a release's row: "1080p", else the quality's last part.
    public static func tile(_ quality: String) -> String {
        if let match = quality.range(of: #"\d{3,4}p"#, options: [.regularExpression, .caseInsensitive]) {
            return quality[match].lowercased()
        }
        let trimmed = quality.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return "?" }
        return trimmed.split(separator: "-").last.map(String.init) ?? trimmed
    }

    /// "Bluray-1080p · 8.0 GB · 112s/6p · 4210d old · freeleech · English · 1337x".
    public static func figures(_ release: Release) -> String {
        [release.quality, Fmt.bytes(release.sizeBytes), "\(release.seeders)s/\(release.leechers)p",
         release.ageDays > 0 ? "\(release.ageDays)d old" : "", release.freeleech ? "freeleech" : "",
         release.languages.joined(separator: "/"), release.indexer]
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: " · ")
    }

    /// Radarr's or Sonarr's reasons, verbatim.
    public static func rejections(_ release: Release) -> String {
        release.rejections.filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// "52 releases · 3 acceptable", and why when none is.
    public static func status(total: Int, accepted: Int) -> String {
        let line = (total == 1 ? "1 release" : "\(total) releases") + " · \(accepted) acceptable"
        return accepted == 0 && total > 0 ? line + " — every one was refused, see why below" : line
    }

    public static let searching = "Asking every indexer… this takes a few seconds."

    /// Why a release outside the search's season or episode cannot be grabbed.
    public static func blockedTitle(season: Int, episode: Int) -> String {
        episode > 0 ? String(format: "More than S%02dE%02d", season, episode) : "More than Season \(season)"
    }

    public static let blockedDetail = "This release contains media outside the target you selected."

    /// "8.0 GB · Bluray-1080p · 112 seeders", and the first refusal for a rejected one.
    public static func confirmDetail(_ release: Release) -> String {
        let line = [Fmt.bytes(release.sizeBytes), release.quality,
                    release.seeders == 1 ? "1 seeder" : "\(release.seeders) seeders"]
            .filter { !$0.isEmpty }.joined(separator: " · ")
        guard release.rejected, let first = release.rejections.first(where: { !$0.isEmpty }) else { return line }
        return line + "\n" + first
    }

    public static func grabbed(_ reply: GrabReply, release: Release) -> String {
        "Grabbed — " + (reply.title.isEmpty ? release.title : reply.title)
    }
}

/// A season's aired episodes before a release search (Android's
/// `ReleaseTargetsScreen`).
public enum ReleaseTargetLines {
    public static func title(_ target: ReleaseEpisodeTarget) -> String {
        EpisodeLabel.of(season: target.season, episode: target.episode, title: target.title)
    }

    /// "2026-09-12 · 44 min · Downloaded · Not monitored".
    public static func meta(_ target: ReleaseEpisodeTarget) -> String {
        [target.airDate, Fmt.runtime(Int64(target.runtimeMinutes) * 60), target.hasFile ? "Downloaded" : "",
         target.monitored ? "" : "Not monitored"]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    public static func status(aired: Int) -> String {
        aired == 0 ? "No episodes have aired yet · the season search is still available"
            : (aired == 1 ? "1 aired episode" : "\(aired) aired episodes")
    }

    /// The releases page's heading for a season or one episode.
    public static func heading(series: String, seasonTitle: String) -> String { series + " · " + seasonTitle }
    public static func heading(series: String, target: ReleaseEpisodeTarget) -> String { series + " · " + title(target) }
}

/// A performer's page (Android's `PersonScreen`).
public enum PersonLines {
    /// "39 credits · Acting · newest first".
    public static func status(_ person: PersonResponse) -> String {
        let count = person.credits.count == 1 ? "1 credit" : "\(person.credits.count) credits"
        return [count, person.knownFor, person.sortedBy == "popularity" ? "by popularity" : "newest first"]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

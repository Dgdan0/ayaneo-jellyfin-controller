import Foundation

/// The one place the app decides whether a position is a resume point, a
/// finished item, or not really started. A port of Android's
/// `playback/ResumeRules`, which mirrors the hub's `decideWatchPosition` with
/// Jellyfin's default thresholds.
public enum ResumeRules {
    public static let minResumePercent = 5.0
    public static let maxResumePercent = 90.0
    public static let minResumeDurationSeconds: Int64 = 300

    public enum Verdict: Equatable, Sendable { case notStarted, resume, finished }

    public static func judge(positionMillis: Int64, durationMillis: Int64) -> Verdict {
        let position = max(0, positionMillis)
        // With no runtime the hub keeps the position rather than guessing
        // "watched", which would hide the item from Continue watching.
        if durationMillis <= 0 { return position > 0 ? .resume : .notStarted }
        let percent = Double(position) * 100 / Double(durationMillis)
        if percent < minResumePercent { return .notStarted }
        if percent > maxResumePercent || position >= durationMillis { return .finished }
        if durationMillis / 1_000 < minResumeDurationSeconds { return .finished }
        return .resume
    }

    /// The position to resume from, or 0 to start at the beginning.
    public static func resumePosition(positionMillis: Int64, durationMillis: Int64) -> Int64 {
        judge(positionMillis: positionMillis, durationMillis: durationMillis) == .resume ? positionMillis : 0
    }

    public static func isFinished(positionMillis: Int64, durationMillis: Int64) -> Bool {
        judge(positionMillis: positionMillis, durationMillis: durationMillis) == .finished
    }

    /// Watched with a saved position is a rewatch in progress, so the position
    /// wins: progress bar, "40% watched", Resume. The tick shows only when
    /// there is nothing to resume.
    public static func showsWatched(played: Bool, progress: Double) -> Bool {
        played && progress <= 0
    }

    /// "40% watched", "Watched", or nil when there is nothing to say.
    public static func watchLabel(played: Bool, progress: Double) -> String? {
        if progress > 0 { return "\(Int(progress * 100))% watched" }
        return played ? "Watched" : nil
    }
}

/// How an episode is named everywhere. A port of Android's `EpisodeLabel`.
public enum EpisodeLabel {
    /// "S1E4 · Mindy's Back", or whichever half exists.
    public static func of(season: Int, episode: Int, title: String) -> String {
        [code(season: season, episode: episode), title]
            .filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
            .joined(separator: " · ")
    }

    /// Season 0 is where Jellyfin and Sonarr keep specials.
    public static func season(_ number: Int) -> String {
        number == 0 ? "Specials" : "Season \(number)"
    }

    /// "S1E4", or empty when the episode has no number.
    public static func code(season: Int, episode: Int) -> String {
        season > 0 || episode > 0 ? "S\(season)E\(episode)" : ""
    }
}

/// A library's sort: a field and a direction. Android's `SortPreference`.
public struct SortPreference: Hashable, Sendable {
    public var field: String
    public var ascending: Bool

    public init(field: String, ascending: Bool) {
        self.field = field
        self.ascending = ascending
    }

    /// The fields a media library sorts by, in menu order, with their names.
    public static let mediaFields: [(id: String, label: String)] = [
        ("name", "Name"), ("release", "Release date"), ("added", "Date added"), ("year", "Year"),
        ("rating", "Rating"), ("played", "Last played"), ("parental", "Parental rating"),
    ]

    public static func label(for field: String) -> String {
        mediaFields.first { $0.id == field }?.label ?? field.capitalized
    }

    /// A newly chosen field starts in the direction people usually want:
    /// newest and highest first, names from A.
    public static func forField(_ field: String) -> SortPreference {
        SortPreference(field: field,
                       ascending: !["added", "last_read", "release", "year", "rating", "played", "progress"].contains(field))
    }

    public var order: String { ascending ? "asc" : "desc" }

    /// "added:desc", as Android stores it.
    public var encoded: String { field + ":" + order }

    /// A stored sort, or `fallback`'s default direction when the value is
    /// missing or malformed.
    public static func decode(_ raw: String?, fallback: String) -> SortPreference {
        let parts = (raw ?? "").split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 2, !parts[0].isEmpty,
              parts[0].allSatisfy({ ("a"..."z").contains($0) || $0 == "_" }),
              parts[1] == "asc" || parts[1] == "desc"
        else { return forField(fallback) }
        return SortPreference(field: parts[0], ascending: parts[1] == "asc")
    }

    /// What the direction means for this field: "A to Z", "Newest first", "Highest first".
    public var directionLabel: String {
        switch field {
        case "last_read": ascending ? "Least recently read first" : "Most recently read first"
        case "name", "title", "author", "series": ascending ? "A to Z" : "Z to A"
        case "added", "release", "played", "year": ascending ? "Oldest first" : "Newest first"
        default: ascending ? "Lowest first" : "Highest first"
        }
    }
}

/// What the big area at the top of Home says about the selected card.
public struct HeroContent: Equatable, Sendable {
    public var itemId: String
    public var type: String
    public var eyebrow: String
    public var title: String
    public var meta: [String]
    public var progress: Double
    public var progressLabel: String
    public var playLabel: String
    /// Hub-relative; empty when there is no artwork at all.
    public var backdrop: String
    public var canPlay: Bool
    /// The end of `eyebrow` that Glass draws in the accent: the episode code,
    /// or the day on Coming up. Empty when the eyebrow has none.
    public var eyebrowMark: String = ""
    /// The words on Glass's white Play pill: "Resume", "Play S2E1" on Next
    /// up, "Play".
    public var playAction: String = ""
}

/// A port of Android's `screens/home/HomeHero`: built first from the card
/// alone, then again when the item's details add runtime and certification.
public enum HomeHero {
    /// Home's rows in Android's default order (`HomeRows.DEFAULT_ORDER`);
    /// the hub sends Favourites first.
    public static let rowOrder = ["continue", "nextup", "latest", "favourites"]

    /// The hub's rows in Home's order; rows it adds later follow, in its order.
    public static func ordered(_ rows: [HomeRow]) -> [HomeRow] {
        rows.enumerated().sorted { a, b in
            let ra = rowOrder.firstIndex(of: a.element.id) ?? rowOrder.count
            let rb = rowOrder.firstIndex(of: b.element.id) ?? rowOrder.count
            return ra != rb ? ra < rb : a.offset < b.offset
        }.map(\.element)
    }

    /// Continue watching and Next up are concrete episodes and films, shown as
    /// 16:9 stills; every other row is posters.
    public static func isLandscape(rowId: String) -> Bool {
        rowId == "continue" || rowId == "nextup"
    }

    public static func eyebrow(rowId: String, rowTitle: String) -> String {
        switch rowId {
        case "continue": "CONTINUE WATCHING"
        case "nextup": "NEXT UP"
        case "latest": "RECENTLY ADDED"
        case "favourites": "FAVOURITE"
        case "upcoming": "COMING UP"
        default: rowTitle.uppercased()
        }
    }

    /// The series behind an episode card's poster, or nil when the poster is
    /// the episode's own.
    public static func seriesIdFromPoster(_ poster: String, itemId: String) -> String? {
        let prefix = "/v1/img/jf/"
        guard poster.hasPrefix(prefix) else { return nil }
        let rest = poster.dropFirst(prefix.count)
        let id = rest.prefix { $0.isHexDigit && ($0.isNumber || $0.isLowercase) }
        guard id.count == 32, rest.dropFirst(32).hasPrefix("/Primary"), String(id) != itemId else { return nil }
        return String(id)
    }

    public static func from(rowId: String, rowTitle: String, hit: MediaHit, detail: LibraryItem? = nil) -> HeroContent {
        let episode = hit.media.type == "episode"
        // The hub writes an episode's subtitle as "S1E4 · Title", or only the
        // title when it has no numbers; then there is no code, and the title
        // belongs in the facts, once.
        let head = hit.subtitle.components(separatedBy: " · ").first?.trimmingCharacters(in: .whitespaces) ?? ""
        let code = episode && isEpisodeCode(head) ? head : ""
        var episodeTitle = ""
        if episode {
            if let title = detail?.title, !title.trimmingCharacters(in: .whitespaces).isEmpty {
                episodeTitle = title
            } else if code.isEmpty {
                episodeTitle = hit.subtitle.trimmingCharacters(in: .whitespaces)
            } else if let range = hit.subtitle.range(of: " · ") {
                episodeTitle = hit.subtitle[range.upperBound...].trimmingCharacters(in: .whitespaces)
            }
        }
        let runtime = Int64(detail?.runtimeSeconds ?? 0)
        let watching = hit.progress > 0 && !ResumeRules.showsWatched(played: hit.played, progress: hit.progress)
        let year = detail?.year ?? hit.media.year
        let meta = [
            episodeTitle,
            year > 0 ? String(year) : "",
            detail?.officialRating ?? "",
            Fmt.runtime(seconds: runtime),
            hit.rating > 0 ? String(format: "★ %.1f", locale: Locale(identifier: "en_US_POSIX"), hit.rating) : "",
        ].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        let left = watching && runtime > 0
            ? Fmt.runtime(seconds: Int64(Double(runtime) * (1 - hit.progress))) + " left" : ""
        // An episode shows its series' backdrop from the first frame: the
        // card's poster is the series' own, so its id is known before any
        // details arrive.
        let seriesId = episode
            ? (detail?.seriesId.isEmpty == false ? detail?.seriesId
                : seriesIdFromPoster(hit.media.poster, itemId: hit.jellyfinItemId))
            : nil
        let backdrop: String
        if let seriesId {
            backdrop = "/v1/img/jf/\(seriesId)/Backdrop"
        } else if let own = detail?.backdrop, !own.isEmpty {
            backdrop = own
        } else {
            backdrop = hit.media.backdrop.isEmpty ? hit.media.poster : hit.media.backdrop
        }
        let mark = rowId == "upcoming" ? hit.subtitle.uppercased() : code
        // The next episode by name, as the prototype's pill says it: you are
        // about to start something new, and which one is worth seeing.
        let action = watching ? "Resume" : (rowId == "nextup" && !code.isEmpty ? "Play \(code)" : "Play")
        return HeroContent(
            itemId: hit.jellyfinItemId, type: hit.media.type,
            eyebrow: [eyebrow(rowId: rowId, rowTitle: rowTitle), mark].filter { !$0.isEmpty }.joined(separator: " · "),
            title: hit.media.title, meta: meta, progress: watching ? hit.progress : 0, progressLabel: left,
            playLabel: watching ? "Resume" : "Play", backdrop: backdrop, canPlay: !hit.jellyfinItemId.isEmpty,
            eyebrowMark: mark, playAction: action)
    }

    /// What `EpisodeLabel.code` writes: "S1E4".
    static func isEpisodeCode(_ value: String) -> Bool {
        value.range(of: #"^S\d+E\d+$"#, options: .regularExpression) != nil
    }
}

/// The lines under a title's name on its own page. Android's
/// `LibraryDetailScreen` facts and progress lines.
public enum DetailLines {
    /// "S2E3  ·  2009  ·  TV-MA  ·  47 min  ·  ★ 8.1  ·  Drama"
    public static func facts(_ item: LibraryItem) -> String {
        var parts: [String] = []
        if item.type == "episode" {
            parts.append(item.subtitle.isEmpty ? EpisodeLabel.code(season: item.seasonNumber, episode: item.indexNumber)
                                               : item.subtitle)
        }
        if item.year > 0 { parts.append(String(item.year)) }
        if !item.officialRating.isEmpty { parts.append(item.officialRating) }
        if item.runtimeSeconds > 0 { parts.append(Fmt.runtime(seconds: Int64(item.runtimeSeconds))) }
        if item.rating > 0 { parts.append(String(format: "★ %.1f", locale: Locale(identifier: "en_US_POSIX"), item.rating)) }
        parts += item.genres.prefix(3)
        return parts.filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: "  ·  ")
    }

    /// "✓ Watched · ★ Favourite", "40% watched · Continue at 12:34", "9 unwatched".
    public static func state(_ item: LibraryItem) -> String {
        var parts: [String] = []
        if let watch = ResumeRules.watchLabel(played: item.played, progress: item.progress) {
            parts.append(ResumeRules.showsWatched(played: item.played, progress: item.progress) ? "✓ " + watch : watch)
        } else if item.unplayedCount > 0 {
            parts.append("\(item.unplayedCount) unwatched")
        }
        if item.positionSeconds > 0 { parts.append("Continue at " + Fmt.clock(millis: Int64(item.positionSeconds) * 1_000)) }
        if item.favorite { parts.append("★ Favourite") }
        return parts.joined(separator: " · ")
    }

    /// The Details section, in Android's labels and order (`MediaFacts.facts`):
    /// who made it, where, what kind, when, and its original title.
    public static func details(_ item: LibraryItem) -> [(label: String, value: String)] {
        func names(_ type: String) -> String {
            item.people.filter { $0.type == type }.map(\.name).joined(separator: ", ")
        }
        var out: [(label: String, value: String)] = []
        let directors = names("Director"), writers = names("Writer")
        if !directors.isEmpty { out.append(("Directed by", directors)) }
        if !writers.isEmpty { out.append(("Written by", writers)) }
        if !item.studios.isEmpty { out.append((item.studios.count == 1 ? "Studio" : "Studios", item.studios.joined(separator: ", "))) }
        if !item.genres.isEmpty { out.append(("Genres", item.genres.joined(separator: ", "))) }
        let date = day(item.premiereDate)
        if !date.isEmpty { out.append((item.type == "series" ? "First aired" : "Released", date)) }
        if !item.originalTitle.isEmpty, item.originalTitle.caseInsensitiveCompare(item.title) != .orderedSame {
            out.append(("Original title", item.originalTitle))
        }
        return out
    }

    /// The actors and guest stars, in billing order, at most 20.
    public static func cast(_ item: LibraryItem) -> [LibraryPerson] {
        Array(item.people.filter { $0.type == "Actor" || $0.type == "GuestStar" }.prefix(20))
    }

    /// "2008-12-09T00:00:00.0000000Z" as "9 Dec 2008"; empty when unreadable.
    public static func day(_ iso: String) -> String {
        let parts = iso.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3, (1...12).contains(parts[1]), (1...31).contains(parts[2]), parts[0] > 1800 else { return "" }
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        return "\(parts[2]) \(months[parts[1] - 1]) \(parts[0])"
    }

    /// An episode in a season's list: "4. Pilot", or the title alone.
    public static func episodeTitle(_ episode: LibraryItem) -> String {
        episode.indexNumber > 0 ? "\(episode.indexNumber). \(episode.title)" : episode.title
    }

    /// Under an episode in a season's list: "47 min · 40% watched".
    public static func episodeMeta(_ episode: LibraryItem) -> String {
        [Fmt.runtime(seconds: Int64(episode.runtimeSeconds)),
         ResumeRules.watchLabel(played: episode.played, progress: episode.progress) ?? ""]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// A series' Play button: "Resume S1E5" for a part-watched episode,
    /// otherwise "Play S1E5" (Android's `seriesActionLabel`).
    public static func seriesPlayLabel(_ target: SeriesPlayTarget) -> String {
        let code = EpisodeLabel.code(season: target.item.seasonNumber, episode: target.item.indexNumber)
        let suffix = code.isEmpty ? "" : " " + code
        return (target.kind == "resume" ? "Resume" : "Play") + suffix
    }

    /// The Play button: "Resume · 12:34" with a saved position, else "Play".
    public static func playLabel(_ item: LibraryItem) -> String {
        item.positionSeconds > 0 ? "Resume · " + Fmt.clock(millis: Int64(item.positionSeconds) * 1_000) : "Play"
    }
}

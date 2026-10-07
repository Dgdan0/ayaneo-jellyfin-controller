import Foundation

// Keeping the library in order from the app (#34): which of a title's pages
// offer subtitles, a release search or deleting from the server, and the
// words each says. Android's `LibraryDetailScreen` actions, `SubtitleScreen`
// and `MediaRemovalScreen`, as plain functions.

extension HubEndpoints {
    /// The installed subtitle tracks of a film or episode and what Bazarr has
    /// downloaded for it before.
    public static func subtitles(itemId: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/subtitles")
    }

    /// Asks every subtitle provider: it takes a while, and answers a ticket
    /// for each candidate.
    public static func searchSubtitles(itemId: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/subtitles/search", method: .post, body: Data("{}".utf8), slow: true)
    }

    /// Downloads the candidate a search's ticket names. Sent once: a timeout
    /// does not mean it did not happen, and the ticket is spent.
    public static func downloadSubtitle(itemId: String, ticket: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/subtitles/download", method: .post,
                   body: json(SubtitleDownloadBody(ticket: ticket)), slow: true)
    }

    /// Asks Jellyfin to look at this one title again, for a subtitle Bazarr has.
    public static func refreshSubtitles(itemId: String) -> HubRequest {
        HubRequest("/v1/library/items/" + encode(itemId) + "/subtitles/refresh", method: .post, body: Data("{}".utf8))
    }

    /// What deleting a title would take: read-only, and a one-use ticket for
    /// the confirmation (good for five minutes).
    public static func removalPreview(kind: String, id: String) -> HubRequest {
        HubRequest("/v1/media/removal-preview", method: .post, body: json(RemovalRequestBody(kind: kind, id: id)), slow: true)
    }

    /// Deletes what the ticket's preview named. Sent once, never again by
    /// itself: an uncertain answer must not delete twice.
    public static func removeMedia(ticket: String) -> HubRequest {
        HubRequest("/v1/media/remove", method: .post, body: json(RemovalConfirmationBody(ticket: ticket)), slow: true)
    }
}

/// What a title's page offers for keeping the library in order.
public enum LibraryUpkeep {
    /// A film or an episode has a file for Bazarr to find subtitles for.
    public static func offersSubtitles(_ item: LibraryItem) -> Bool {
        item.type == "movie" || item.type == "episode"
    }

    /// A series can have its seasons and episodes searched for releases, to
    /// finish one that is only partly here.
    public static func offersReleases(_ item: LibraryItem) -> Bool { item.type == "series" }

    /// What the search is asked by: the series on TMDB. Nil when the hub could
    /// not name it, or is an older hub that does not say.
    public static func releaseKey(_ item: LibraryItem) -> String? { item.mediaKey.isEmpty ? nil : item.mediaKey }

    /// What to say instead of searching for a series with no TMDB match: it
    /// cannot be linked to Sonarr safely.
    public static let noMatchWords = "This series has no TMDB match, so Sonarr releases cannot be linked safely"

    /// Films, series, seasons and episodes are "video" to the hub.
    public static func offersDeleting(_ item: LibraryItem) -> Bool {
        ["movie", "series", "season", "episode"].contains(item.type)
    }

    /// What a page of upkeep calls the title it is for: an episode under its
    /// series' name ("Last Seen · S1E4 · Gone"), anything else by its own.
    public static func pageTitle(_ item: LibraryItem) -> String {
        guard item.type == "episode", !item.seriesTitle.isEmpty else { return item.title }
        return episodeHeading(series: item.seriesTitle, season: item.seasonNumber, episode: item.indexNumber, title: item.title)
    }

    /// "Last Seen · S1E4 · Gone": an episode's release search heading.
    public static func episodeHeading(series: String, season: Int, episode: Int, title: String) -> String {
        series + " · " + EpisodeLabel.of(season: season, episode: episode, title: title)
    }
}

/// What the subtitle page says (Android's `SubtitleScreen`).
public enum SubtitleLines {
    /// "English" for "en"; an unknown code stands for itself.
    public static func language(_ code: String) -> String {
        let name = Locale(identifier: "en").localizedString(forLanguageCode: code)
        return name.flatMap { $0.isEmpty ? nil : $0 } ?? code
    }

    /// " · Forced" and " · SDH" (for the hard of hearing), as a track's words continue.
    public static func flags(forced: Bool, hi: Bool) -> String {
        (forced ? " · Forced" : "") + (hi ? " · SDH" : "")
    }

    /// "Hebrew · Forced · 92% match · OpenSubtitles".
    public static func candidateTitle(_ candidate: SubtitleCandidate) -> String {
        "\(language(candidate.language))\(flags(forced: candidate.forced, hi: candidate.hi)) · \(Int(candidate.score))% match · \(candidate.provider)"
    }

    /// The release it was made for, or why there is none to show.
    public static func release(_ candidate: SubtitleCandidate) -> String {
        candidate.release.isEmpty ? "Release details unavailable" : candidate.release
    }

    /// "Hebrew · 92% match" over a candidate's own page.
    public static func candidateHeading(_ candidate: SubtitleCandidate) -> String {
        "\(language(candidate.language)) · \(Int(candidate.score))% match"
    }

    /// "Hebrew · SDH · OpenSubtitles".
    public static func downloadDetail(_ candidate: SubtitleCandidate) -> String {
        "\(language(candidate.language))\(flags(forced: candidate.forced, hi: candidate.hi)) · \(candidate.provider)"
    }

    public static func matches(_ candidate: SubtitleCandidate) -> String {
        candidate.matches.isEmpty ? "Not reported" : candidate.matches.joined(separator: ", ")
    }

    public static func mismatches(_ candidate: SubtitleCandidate) -> String {
        candidate.mismatches.isEmpty ? "None reported" : candidate.mismatches.joined(separator: ", ")
    }

    /// A line for a track: "English · Embedded", "Hebrew · SDH · OpenSubtitles".
    public static func recordTitle(_ record: SubtitleRecord) -> String {
        let source = record.embedded ? "Embedded" : (record.provider.isEmpty ? "External" : record.provider)
        return "\(record.language)\(flags(forced: record.forced, hi: record.hi)) · \(source)"
    }

    /// What Bazarr rated it, or that it did not.
    public static func recordScore(_ record: SubtitleRecord) -> String {
        record.score.isEmpty ? "Match score unavailable" : "\(record.score) match"
    }

    /// When it was saved and what it was made for: the rest of a track's row.
    public static func recordDetail(_ record: SubtitleRecord) -> String {
        [record.date, record.description].filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }.joined(separator: " · ")
    }

    public static func installed(_ state: SubtitleState) -> [SubtitleRecord] { state.records.filter(\.installed) }
    public static func history(_ state: SubtitleState) -> [SubtitleRecord] { state.records.filter { !$0.installed } }

    /// "Library · 2 installed subtitle tracks".
    public static func library(installed count: Int) -> String {
        "Library · \(count) installed subtitle track" + (count == 1 ? "" : "s")
    }

    /// The languages a search found, as "English", "Hebrew", sorted by code as Android sorts them.
    public static func languages(_ candidates: [SubtitleCandidate]) -> [String] {
        Array(Set(candidates.map(\.language))).sorted()
    }

    /// The candidates in one language, or all of them.
    public static func filtered(_ candidates: [SubtitleCandidate], language: String?) -> [SubtitleCandidate] {
        guard let language else { return candidates }
        return candidates.filter { $0.language == language }
    }

    public static let readOnly = "This connection has read-only subtitle access."
    public static let nothingFound = "No matching subtitles found for the configured language profile."
    public static let searching = "Searching the subtitle providers. This can take a minute."
    public static let overwrite = "Downloading may replace an external subtitle with the same language and type. Embedded tracks are kept."
    public static let legend = "Match score: how well it fits this release"
    public static let noInstalled = "No indexed subtitle tracks."
    public static let noHistory = "No recorded subtitle downloads."
    public static let downloading = "Downloading the subtitle…"
    public static let refreshing = "Asking Jellyfin to refresh this title's subtitles…"
    public static let refreshed = "Jellyfin is refreshing this title's tracks, and the player will list any new subtitle"

    /// What to tell a person once a download is done: the hub's warning, or
    /// that Jellyfin did not take the refresh, or what is happening.
    public static func downloaded(_ ack: SubtitleDownloadAck) -> String {
        if !ack.warning.isEmpty { return ack.warning }
        if !ack.jellyfinRefreshStarted {
            return "Saved online, but Jellyfin did not accept the subtitle refresh. Try Refresh subtitles later."
        }
        return "Saved · Jellyfin is refreshing this title's tracks, and the player will list it"
    }
}

/// What the deletion page says (Android's `MediaRemovalScreen`): plain about
/// what goes, and that the copies on this device stay.
public enum RemovalLines {
    /// "1 server file", "7 server files".
    public static func files(_ count: Int) -> String { "\(count) server file" + (count == 1 ? "" : "s") }

    /// The question, in the alert's own title.
    public static func confirmTitle(_ preview: RemovalPreview) -> String { "Permanently delete \(preview.title)?" }

    public static func confirmMessage(_ preview: RemovalPreview) -> String {
        "\(files(preview.fileCount)) will be deleted from the media server. This cannot be undone. Copies saved on this device stay."
    }

    public static let keep = "Cancel"
    public static let delete = "Delete server files"
    public static let deleting = "Deleting"
    public static let deleted = "Deleted from the server · copies on this device kept"
    public static let heading = "Delete from server"
    public static let filesIncluded = "Files included"
    public static let loading = "Checking what would be deleted…"
    /// After a failed deletion some files may be gone: the way on is to look again, or leave.
    public static let review = "Review again"
    public static let backToLibrary = "Back to library"
}

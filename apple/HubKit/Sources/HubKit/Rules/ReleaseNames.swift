import Foundation

/// A torrent's release name as words: "dark.matter.2024.s02e06.1080p.web.h264-
/// cakes[EZTVx.to].mkv" is "Dark Matter (2024) · S2E6 · 1080p" (Android's
/// `model/ReleaseNames`, held to its tests).
///
/// A finished transfer has left Sonarr's and Radarr's queues, so the hub has
/// no title for it. Anything without an episode, season or year to anchor on
/// is left as it came: a wrong guess is worse than the raw name.
public enum ReleaseNames {
    public static func readable(_ name: String) -> String {
        var text = name.trimmingCharacters(in: .whitespacesAndNewlines)
        text = text.replacingOccurrences(of: #"\.(mkv|mp4|avi|m4v|ts|webm)$"#, with: "",
                                         options: [.regularExpression, .caseInsensitive])
        text = text.replacingOccurrences(of: #"\[[^\]]*\]"#, with: " ", options: .regularExpression)
        let words = text.replacingOccurrences(of: ".", with: " ").replacingOccurrences(of: "_", with: " ")
            .split(separator: " ").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        guard !words.isEmpty else { return name }
        let anchor: Int
        if let marker = words.firstIndex(where: { episode($0) != nil || season($0) != nil }), marker > 0 {
            anchor = marker
        } else if let year = words.lastIndex(where: { self.year($0) != nil }), year > 0 {
            // The last year, so "2001 A Space Odyssey 1968" keeps its title.
            anchor = year + 1
        } else {
            return name
        }
        var titleWords = Array(words.prefix(anchor))
        // "Dark Matter 2024" names a remake by its year: "Dark Matter (2024)".
        var year: String?
        if titleWords.count > 1, let last = titleWords.last, let found = self.year(last) {
            year = found
            titleWords.removeLast()
        }
        let title = titleWords.map { word in
            word.contains(where: \.isUppercase) ? word : word.prefix(1).uppercased() + word.dropFirst()
        }.joined(separator: " ") + (year.map { " (\($0))" } ?? "")
        let marker = anchor < words.count ? words[anchor] : ""
        let place = episode(marker).map { EpisodeLabel.code(season: $0.season, episode: $0.episode) }
            ?? season(marker).map { EpisodeLabel.season($0) }
        let quality = words.dropFirst(anchor).lazy.compactMap(Self.quality).first
        return [title, place, quality].compactMap { $0 }.joined(separator: " · ")
    }

    /// "s02e06", "S01E07", "s01e01-e02": the season and the first episode.
    private static func episode(_ word: String) -> (season: Int, episode: Int)? {
        guard let match = word.wholeMatch(of: /(?i)s(\d{1,2})e(\d{1,3})(?:-?e\d{1,3})*/),
              let season = Int(match.1), let episode = Int(match.2) else { return nil }
        return (season, episode)
    }

    /// "S01": a season pack.
    private static func season(_ word: String) -> Int? {
        guard let match = word.wholeMatch(of: /(?i)s(\d{1,2})/) else { return nil }
        return Int(match.1)
    }

    /// "1968" or "(2024)".
    private static func year(_ word: String) -> String? {
        guard let match = word.wholeMatch(of: /\(?((?:19|20)\d{2})\)?/) else { return nil }
        return String(match.1)
    }

    /// "1080p", "2160P": lower case.
    private static func quality(_ word: String) -> String? {
        guard let match = word.wholeMatch(of: /(?i)(\d{3,4}p)/) else { return nil }
        return match.1.lowercased()
    }
}

import Foundation

/// How Kavita's reading lists read (#37; Android's `ServerReadingListsScreen`):
/// the lists by title, a list's issues in Kavita's order, numbered, and the
/// words on each row.
public enum ServerReadingLists {
    /// The lists by title, whatever order Kavita gives them in.
    public static func sorted(_ lists: [ServerReadingList]) -> [ServerReadingList] {
        lists.sorted { $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending }
    }

    /// A list's issues in Kavita's reading order; equal orders keep theirs.
    public static func ordered(_ entries: [ServerReadingListEntry]) -> [ServerReadingListEntry] {
        entries.enumerated().sorted { ($0.element.order, $0.offset) < ($1.element.order, $1.offset) }.map(\.element)
    }

    /// Under a list's name: "12 issues".
    public static func listDetail(_ list: ServerReadingList) -> String {
        issues(list.itemCount)
    }

    /// Over the lists: "3 Kavita lists · title order", or that there are none.
    public static func listsStatus(_ count: Int) -> String {
        count == 0 ? "No reading lists are available in Kavita."
            : "\(count) Kavita \(count == 1 ? "list" : "lists") · title order"
    }

    /// Over a list's issues: "12 issues · Kavita reading order", or that it is empty.
    public static func entriesStatus(_ count: Int) -> String {
        count == 0 ? "This reading list is empty." : "\(issues(count)) · Kavita reading order"
    }

    /// An issue's row: "1. Fantastic Four · Issue #1", counted from one.
    public static func entryTitle(_ index: Int, _ entry: ServerReadingListEntry) -> String {
        "\(index + 1). " + [entry.seriesTitle, entry.title].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    /// Under it: "Volume 2 · 24 pages · 40% read". Kavita's loose issues
    /// carry a volume of 0 (or -100000 since 0.8), which is no volume at all.
    public static func entryDetail(_ entry: ServerReadingListEntry) -> String {
        var parts: [String] = []
        let volume = entry.volume.trimmingCharacters(in: .whitespaces)
        if !volume.isEmpty, (Double(volume) ?? 1) > 0 {
            parts.append(Double(volume) != nil ? "Volume " + volume : volume)
        }
        if entry.pageCount > 0 { parts.append("\(entry.pageCount) \(entry.pageCount == 1 ? "page" : "pages")") }
        if let progress = entry.progress {
            if progress.completed {
                parts.append("Read")
            } else if progress.percentage > 0 {
                parts.append(Fmt.readingPercentLabel(progress.percentage) + " read")
            }
        }
        return parts.joined(separator: " · ")
    }

    /// What VoiceOver reads for an issue's row.
    public static func entryLabel(_ index: Int, _ entry: ServerReadingListEntry) -> String {
        let detail = entryDetail(entry)
        return detail.isEmpty ? entryTitle(index, entry) : entryTitle(index, entry) + ", " + detail
    }

    private static func issues(_ count: Int) -> String {
        "\(count) \(count == 1 ? "issue" : "issues")"
    }
}

/// A reading list being read (#37): its issues in order and the one open.
/// The comic reader goes from issue to issue along it, across runs, rather
/// than along the run of the issue it is in.
public struct ReadingListRun: Equatable, Sendable, Hashable {
    public let title: String
    public let entries: [ServerReadingListEntry]
    public private(set) var index: Int

    /// The list `entries` as Kavita orders them, open at `index`; nil for an empty list.
    public init?(title: String, entries: [ServerReadingListEntry], index: Int) {
        guard !entries.isEmpty else { return nil }
        self.title = title
        self.entries = entries
        self.index = min(max(index, 0), entries.count - 1)
    }

    public var current: ServerReadingListEntry { entries[index] }

    /// The issue `delta` along, if the list has one.
    public func neighbour(_ delta: Int) -> ServerReadingListEntry? {
        let target = index + delta
        return entries.indices.contains(target) ? entries[target] : nil
    }

    /// Moved `delta` along; nil past either end.
    public func moved(_ delta: Int) -> ReadingListRun? {
        guard neighbour(delta) != nil else { return nil }
        var next = self
        next.index += delta
        return next
    }

    /// Why there is nothing that way.
    public static func edge(forward: Bool) -> String {
        forward ? "End of reading list" : "Start of reading list"
    }

    /// "3 of 4 in Marvel's first year": where the list is, for the reader's notice.
    public var position: String {
        "\(index + 1) of \(entries.count) in \(title)"
    }
}

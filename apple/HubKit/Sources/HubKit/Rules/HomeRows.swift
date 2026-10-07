import Foundation

/// Which Home rows show, in what order, and what Coming up and a library's row
/// are made of (#35; Android's `screens/home/HomeRows` and
/// `settings/HomeRowSettings`, held to their rules).
///
/// The default is ordered by how soon you would act on a row: what you are in
/// the middle of, what comes next, then what is new, what you keep, and what
/// is coming. Settings › Home rearranges and hides them, and adds a row of the
/// newest titles from any one library ("From Anime"). A row the order does not
/// name (one a newer hub adds) goes at the end rather than disappearing.
public enum HomeRows {
    public static let upcoming = "upcoming"
    public static let builtIn = ["continue", "nextup", "latest", "favourites", upcoming]
    public static let defaultOrder = builtIn
    /// How far ahead Coming up looks.
    public static let upcomingDays = 14
    /// How many of a library's newest titles its row shows.
    public static let libraryRowSize = 20
    private static let libraryPrefix = "library:"

    public static func libraryRowId(_ viewId: String) -> String { libraryPrefix + viewId }

    public static func libraryViewId(_ rowId: String) -> String? {
        rowId.hasPrefix(libraryPrefix) ? String(rowId.dropFirst(libraryPrefix.count)) : nil
    }

    public static func libraryRowTitle(_ libraryName: String) -> String { "From \(libraryName)" }

    public static func builtInTitle(_ rowId: String) -> String {
        switch rowId {
        case "continue": "Continue watching"
        case "nextup": "Next up"
        case "latest": "Recently added"
        case "favourites": "Favourites"
        case upcoming: "Coming up"
        default: rowId
        }
    }

    /// A stored order from an older build gains any built-in row it never knew about.
    public static func complete(_ order: [String]) -> [String] {
        var seen = Set<String>()
        let unique = order.filter { seen.insert($0).inserted }
        return unique + builtIn.filter { !unique.contains($0) }
    }

    /// The rows in the order chosen, the ones not named after them, without
    /// those hidden and without any with nothing in them: a row with no cards
    /// is no row.
    public static func ordered(_ rows: [HomeRow], order: [String] = defaultOrder, hidden: Set<String> = []) -> [HomeRow] {
        var byId: [String: HomeRow] = [:]
        for row in rows where byId[row.id] == nil { byId[row.id] = row }
        let named = order.compactMap { byId[$0] }
        let rest = rows.filter { !order.contains($0.id) }
        return (named + rest).filter { !hidden.contains($0.id) && !$0.items.isEmpty }
    }

    /// The library rows Home should fetch: named in the order and not hidden.
    public static func wantedLibraries(order: [String], hidden: Set<String>) -> [String] {
        order.filter { !hidden.contains($0) }.compactMap(libraryViewId)
    }

    /// After a partial failure, a row the hub could not refresh is kept
    /// rather than dropped; `ordered` puts it back in its place.
    public static func merge(next: [HomeRow], previous: [HomeRow]) -> [HomeRow] {
        next + previous.filter { kept in !next.contains { $0.id == kept.id } }
    }

    /// Coming up: each monitored series or film once, at its next release
    /// that is not already on disk, soonest first. The card's subtitle starts
    /// with the day ("Fri · S2E6"), which is also its corner tag. `today` is a
    /// civil date, "2026-10-07".
    public static func upcoming(_ items: [CalendarItem], today: String) -> HomeRow {
        var seen = Set<String>()
        let next = items.filter { !$0.hasFile }
            .sorted { ($0.date, $0.at, $0.episode) < ($1.date, $1.at, $1.episode) }
            .filter { seen.insert($0.media.key.isEmpty ? $0.id : $0.media.key).inserted }
        let hits: [MediaHit] = next.compactMap { item in
            guard UpcomingPresentation.date(item.date) != nil else { return nil }
            let what = item.media.type == "series"
                ? (EpisodeLabel.code(season: item.season, episode: item.episode).isEmpty
                    ? item.releaseType : EpisodeLabel.code(season: item.season, episode: item.episode))
                : item.releaseType
            return MediaHit(media: item.media,
                            subtitle: [dayLabel(item.date, today: today), what].filter { !$0.isEmpty }.joined(separator: " · "),
                            overview: [item.episodeTitle, item.overview].filter { !$0.isEmpty }.joined(separator: " — "))
        }
        return HomeRow(id: upcoming, title: builtInTitle(upcoming), items: hits)
    }

    /// "Today", "Tomorrow", a weekday this week, otherwise "9 Oct"; a day gone by is "Out now".
    public static func dayLabel(_ day: String, today: String) -> String {
        guard let date = UpcomingPresentation.date(day), let now = UpcomingPresentation.date(today) else { return "" }
        let days = Int((date.timeIntervalSince(now) / 86_400).rounded())
        switch days {
        case 0: return "Today"
        case 1: return "Tomorrow"
        case 2...6: return UpcomingPresentation.dayHeading(day).split(separator: " ").first.map(String.init) ?? ""
        case ..<0: return "Out now"
        default: return UpcomingPresentation.dayHeading(day).split(separator: " ").dropFirst().joined(separator: " ")
        }
    }

    /// The day a Coming up card carries in its corner: the start of its subtitle.
    public static func dayTag(_ subtitle: String) -> String {
        subtitle.components(separatedBy: " · ").first?.trimmingCharacters(in: .whitespaces) ?? ""
    }

    /// The range Coming up asks the hub's calendar for: today and the days ahead.
    public static func upcomingRange(today: String) -> (start: String, end: String) {
        (today, UpcomingPresentation.add(days: upcomingDays, to: today))
    }
}

/// Settings › Home as a value: the order and which rows are hidden, as stored.
public struct HomeLayout: Equatable, Sendable {
    public var order: [String]
    public var hidden: Set<String>

    public init(order: [String] = HomeRows.defaultOrder, hidden: Set<String> = []) {
        self.order = HomeRows.complete(order)
        self.hidden = hidden
    }

    /// Whether Home asks for Coming up.
    public var wantsUpcoming: Bool { order.contains(HomeRows.upcoming) && !hidden.contains(HomeRows.upcoming) }

    /// The libraries whose rows Home fetches.
    public var wantedLibraries: [String] { HomeRows.wantedLibraries(order: order, hidden: hidden) }

    /// What Home must ask for again when it changes, and nothing else: a
    /// reorder or a built-in row hidden changes only how the rows are laid out.
    public var fetchKey: String { (wantsUpcoming ? "upcoming" : "") + "|" + wantedLibraries.joined(separator: ",") }
}

/// A library Settings › Home can add a row of.
public struct HomeLibrary: Equatable, Sendable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = name
    }
}

/// One line of Settings › Home.
public struct HomeSettingsRow: Equatable, Sendable, Identifiable {
    public let id: String
    public let title: String
    /// "The newest titles in that library" for a library's row.
    public let detail: String
    public let shown: Bool
    public let canMoveUp: Bool
    public let canMoveDown: Bool
}

/// What Settings › Home does to the layout, and what it lists (Android's
/// `SettingsScreen.homeRows`, `currentOrder`, `effectiveHidden` and
/// `moveHomeRow`). A library's row Home has never been told about starts
/// hidden: a person adds it by turning it on.
public enum HomeLayoutEditor {
    /// The order as Settings shows it, library rows included.
    static func order(_ layout: HomeLayout, libraries: [HomeLibrary]) -> [String] {
        layout.order + libraries.map { HomeRows.libraryRowId($0.id) }.filter { !layout.order.contains($0) }
    }

    /// Hidden rows, counting a library row Home has never been told about.
    static func hidden(_ layout: HomeLayout, libraries: [HomeLibrary]) -> Set<String> {
        layout.hidden.union(libraries.map { HomeRows.libraryRowId($0.id) }.filter { !layout.order.contains($0) })
    }

    /// The lines of the page: each row with its name and whether it shows. A
    /// library row whose library is not among `libraries` is left out.
    public static func rows(_ layout: HomeLayout, libraries: [HomeLibrary]) -> [HomeSettingsRow] {
        let hidden = hidden(layout, libraries: libraries)
        let names = Dictionary(libraries.map { ($0.id, $0.name) }, uniquingKeysWith: { first, _ in first })
        let entries: [(id: String, title: String, detail: String)] = order(layout, libraries: libraries).compactMap { id in
            if let view = HomeRows.libraryViewId(id) {
                guard let name = names[view] else { return nil }
                return (id, HomeRows.libraryRowTitle(name), "The newest titles in that library")
            }
            return HomeRows.builtIn.contains(id) ? (id, HomeRows.builtInTitle(id), "") : nil
        }
        return entries.enumerated().map { index, entry in
            HomeSettingsRow(id: entry.id, title: entry.title, detail: entry.detail, shown: !hidden.contains(entry.id),
                            canMoveUp: index > 0, canMoveDown: index < entries.count - 1)
        }
    }

    /// Shows or hides a row, saving the order with the library rows now named.
    public static func setShown(_ layout: HomeLayout, libraries: [HomeLibrary], id: String, shown: Bool) -> HomeLayout {
        var hidden = hidden(layout, libraries: libraries)
        if shown { hidden.remove(id) } else { hidden.insert(id) }
        return HomeLayout(order: order(layout, libraries: libraries), hidden: hidden)
    }

    /// Moves a row up or down by `delta` places among the rows listed.
    public static func move(_ layout: HomeLayout, libraries: [HomeLibrary], id: String, by delta: Int) -> HomeLayout {
        let listed = rows(layout, libraries: libraries).map(\.id)
        guard let from = listed.firstIndex(of: id) else { return layout }
        let to = min(max(from + delta, 0), listed.count - 1)
        guard to != from else { return layout }
        // The rows not listed (a library gone) keep their places in the stored order.
        var all = order(layout, libraries: libraries)
        guard let source = all.firstIndex(of: id), let target = all.firstIndex(of: listed[to]) else { return layout }
        let moved = all.remove(at: source)
        all.insert(moved, at: target)
        return HomeLayout(order: all, hidden: hidden(layout, libraries: libraries))
    }
}

/// The layout kept on this device, as Android keeps it: one for the app, as a
/// comma-joined order and set of hidden rows.
public enum HomeRowSettings {
    private static let orderKey = "home.rowOrder"
    private static let hiddenKey = "home.rowsHidden"

    public static func layout(_ defaults: UserDefaults = .standard) -> HomeLayout {
        func list(_ key: String) -> [String]? {
            defaults.string(forKey: key)?.split(separator: ",").map(String.init).filter { !$0.trimmingCharacters(in: .whitespaces).isEmpty }
        }
        return HomeLayout(order: list(orderKey) ?? HomeRows.defaultOrder, hidden: Set(list(hiddenKey) ?? []))
    }

    public static func save(_ layout: HomeLayout, in defaults: UserDefaults = .standard) {
        defaults.set(layout.order.joined(separator: ","), forKey: orderKey)
        defaults.set(layout.hidden.sorted().joined(separator: ","), forKey: hiddenKey)
    }
}

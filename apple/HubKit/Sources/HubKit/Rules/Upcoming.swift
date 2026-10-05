import Foundation

/// Where a release on the calendar is now: Android's `UpcomingPresentation`
/// states, with their chips (`DashboardParts.releaseChip`).
public enum ReleaseState: Equatable, Sendable {
    case inLibrary, aired, missing, soon

    public var label: String {
        switch self {
        case .inLibrary: "In library"
        case .aired: "Aired"
        case .missing: "Missing"
        case .soon: "Soon"
        }
    }

    /// The chip's fill and ink, 0xAARRGGBB.
    public var fill: UInt32 {
        switch self {
        case .inLibrary: 0xE61C_965C
        case .aired: 0xF2CD_8414
        case .missing: 0xFFD8_434A
        case .soon: 0x1FFF_FFFF
        }
    }

    public var ink: UInt32 { self == .soon ? 0xCCFF_FFFF : 0xFFFF_FFFF }
}

/// Releases on the same day of one series' season, shown as one row: the
/// season's episodes that day, or a single release.
public struct UpcomingGroup: Equatable, Sendable, Identifiable {
    public let id: String
    public let items: [CalendarItem]

    public var first: CalendarItem { items[0] }

    /// "S2E6", "Season 1 · 2 episodes", or a film's "Digital release".
    public var label: String {
        guard first.media.type == "series" else { return first.releaseType }
        if items.count > 1 { return "Season \(first.season) · \(items.count) episodes" }
        return EpisodeLabel.code(season: first.season, episode: first.episode)
    }
}

/// The Upcoming page's weeks, days, groups and states: Android's
/// `screens/discover/UpcomingPresentation`, held to its tests. Days are civil
/// dates, "YYYY-MM-DD", as the calendar sends them; weeks run Monday to Sunday.
public enum UpcomingPresentation {
    public struct Range: Equatable, Sendable {
        public let start: String
        /// The day after the last.
        public let endExclusive: String
    }

    // English names and "Sep", whatever the device: en_GB writes "Sept".
    private static let english = Locale(identifier: "en_US_POSIX")
    private static var gregorian: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        calendar.firstWeekday = 2
        return calendar
    }

    /// A civil date at noon UTC, so no zone or summer time moves its day.
    static func date(_ day: String) -> Date? {
        let parts = day.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return gregorian.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2], hour: 12))
    }

    static func day(_ date: Date) -> String {
        let c = gregorian.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    public static func add(days: Int, to day: String) -> String {
        guard let date = date(day), let moved = gregorian.date(byAdding: .day, value: days, to: date) else { return day }
        return self.day(moved)
    }

    /// Today in `zone`, as a civil date.
    public static func today(now: Date, zone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// The week `week` weeks from the one `today` is in, Monday first.
    public static func range(today: String, week: Int) -> Range {
        guard let date = date(today) else { return Range(start: today, endExclusive: today) }
        let weekday = gregorian.component(.weekday, from: date) // 1 is Sunday
        let back = (weekday + 5) % 7 // days since Monday
        let start = add(days: week * 7 - back, to: today)
        return Range(start: start, endExclusive: add(days: 7, to: start))
    }

    public static func days(_ range: Range) -> [String] {
        (0..<7).map { add(days: $0, to: range.start) }
    }

    private static func format(_ day: String, _ pattern: String) -> String {
        guard let date = date(day) else { return day }
        let formatter = DateFormatter()
        formatter.locale = english
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = pattern
        return formatter.string(from: date)
    }

    /// "28 Sep – 4 Oct", "5 – 11 Oct", or with years when they differ.
    public static func rangeLabel(_ range: Range) -> String {
        let end = add(days: -1, to: range.endExclusive)
        if range.start.prefix(4) != end.prefix(4) {
            return format(range.start, "d MMM yyyy") + " – " + format(end, "d MMM yyyy")
        }
        if range.start.prefix(7) == end.prefix(7) {
            return format(range.start, "d") + " – " + format(end, "d MMM")
        }
        return format(range.start, "d MMM") + " – " + format(end, "d MMM")
    }

    public static func weekLabel(today: String, week: Int) -> String {
        let label = rangeLabel(range(today: today, week: week))
        switch week {
        case -1: return "Last week"
        case 0: return "This week · " + label
        case 1: return "Next week · " + label
        default: return label
        }
    }

    /// "Fri 2 Oct".
    public static func dayHeading(_ day: String) -> String { format(day, "EEE d MMM") }

    /// "Today" or "Tomorrow" beside a day's heading.
    public static func dayTag(_ day: String, today: String) -> String? {
        if day == today { return "Today" }
        return day == add(days: 1, to: today) ? "Tomorrow" : nil
    }

    /// A series' episodes on one day and season become one group; a film's
    /// releases and anything without a key stay apart. In order of day, time
    /// and title.
    public static func groups(_ items: [CalendarItem]) -> [UpcomingGroup] {
        var order: [String] = []
        var members: [String: [CalendarItem]] = [:]
        for item in items {
            let key = item.media.type == "series" && !item.media.key.isEmpty
                ? "\(item.date):\(item.media.key):\(item.season)" : item.id
            if members[key] == nil { order.append(key) }
            members[key, default: []].append(item)
        }
        let groups = order.map { key in
            let sorted = members[key, default: []].sorted { ($0.episode, $0.id) < ($1.episode, $1.id) }
            return UpcomingGroup(id: sorted.map(\.id).joined(separator: "|"), items: sorted)
        }
        func earliest(_ group: UpcomingGroup) -> String {
            group.items.map(\.at).filter { !$0.isEmpty }.min() ?? "~"
        }
        return groups.sorted {
            ($0.first.date, earliest($0), $0.first.media.title) < ($1.first.date, earliest($1), $1.first.media.title)
        }
    }

    private static func instant(_ text: String) -> Date? {
        guard !text.isEmpty else { return nil }
        let plain = ISO8601DateFormatter()
        if let date = plain.date(from: text) { return date }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return fractional.date(from: text)
    }

    /// In the library once it has a file; else Soon before it airs, Aired for
    /// a day after, then Missing. A date alone counts from its day's start.
    public static func state(_ item: CalendarItem, now: Date, zone: TimeZone) -> ReleaseState {
        if item.hasFile { return .inLibrary }
        var airs = instant(item.at)
        if airs == nil {
            let parts = item.date.split(separator: "-").compactMap { Int($0) }
            if parts.count == 3 {
                var calendar = Calendar(identifier: .gregorian)
                calendar.timeZone = zone
                airs = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
            }
        }
        guard let airs else { return .soon }
        if airs > now { return .soon }
        return now.timeIntervalSince(airs) < 24 * 3600 ? .aired : .missing
    }

    /// A group is in the library only when all of it is; else it is as its
    /// first missing release is.
    public static func state(_ group: UpcomingGroup, now: Date, zone: TimeZone) -> ReleaseState {
        guard let waiting = group.items.first(where: { !$0.hasFile }) else { return .inLibrary }
        return state(waiting, now: now, zone: zone)
    }

    /// "21:00", "21:00 – 22:00" for several, or "Time not announced".
    public static func timeLabel(_ group: UpcomingGroup, zone: TimeZone) -> String {
        let formatter = DateFormatter()
        formatter.locale = english
        formatter.timeZone = zone
        formatter.dateFormat = "HH:mm"
        var times: [String] = []
        for item in group.items {
            guard let date = instant(item.at) else { continue }
            let time = formatter.string(from: date)
            if !times.contains(time) { times.append(time) }
        }
        switch times.count {
        case 0: return "Time not announced"
        case 1: return times[0]
        default: return times[0] + " – " + times[times.count - 1]
        }
    }

    /// "Friday 2 October · 21:00".
    public static func previewDate(_ group: UpcomingGroup, zone: TimeZone) -> String {
        format(group.first.date, "EEEE d MMMM") + " · " + timeLabel(group, zone: zone)
    }

    /// The group in the preview: the one chosen before, if it is still there;
    /// this week, the first from today on; else the first.
    public static func selected(_ groups: [UpcomingGroup], previous: String?, week: Int, today: String) -> UpcomingGroup? {
        if let previous, let kept = groups.first(where: { $0.id == previous }) { return kept }
        if week == 0, let next = groups.first(where: { $0.first.date >= today }) { return next }
        return groups.first
    }

    /// "12 releases".
    public static func count(_ groups: [UpcomingGroup]) -> String {
        let releases = groups.map(\.items.count).reduce(0, +)
        return releases == 1 ? "1 release" : "\(releases) releases"
    }
}

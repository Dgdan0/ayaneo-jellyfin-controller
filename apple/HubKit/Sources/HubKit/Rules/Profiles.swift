import Foundation

/// How a Jellyfin profile looks in the Glass chrome: which one is this
/// device's, its avatar letter and its colour (GLASS_PLAN.md, "Navigation per
/// device"). The colours are the prototype's, in its order.
public enum Profiles {
    public static let colors: [UInt32] = [0xFF8B_7BFF, 0xFF2C_C4AD, 0xFFF2_A541, 0xFFFF_6B7D]

    /// Alphabetical, so each profile keeps its place, and with it its colour,
    /// whatever order the hub lists them in.
    public static func ordered(_ users: [HubUser]) -> [HubUser] {
        users.sorted { a, b in
            let order = a.name.compare(b.name, options: [.caseInsensitive, .diacriticInsensitive])
            return order == .orderedSame ? a.id < b.id : order == .orderedAscending
        }
    }

    /// The avatar colour of `userId`: its place in `ordered`, the colours
    /// repeating after the fourth. Nil for a profile not in the list.
    public static func color(of userId: String, in users: [HubUser]) -> UInt32? {
        guard let index = ordered(users).firstIndex(where: { $0.id == userId }) else { return nil }
        return colors[index % colors.count]
    }

    /// The letter on the avatar: the name's first character, capitalised.
    public static func initial(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).first.map { String($0).uppercased() } ?? ""
    }

    /// The profile this device watches as: its own choice while the hub still
    /// lists it, else the hub's default (`selected`), as the profile picker
    /// has always decided.
    public static func current(_ users: [HubUser], chosen: String) -> HubUser? {
        if !chosen.isEmpty, let mine = users.first(where: { $0.id == chosen }) { return mine }
        return users.first(where: \.selected)
    }
}

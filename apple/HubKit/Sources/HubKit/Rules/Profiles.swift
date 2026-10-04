import Foundation

/// A Jellyfin profile as Glass draws it: which one is this device's, and its
/// initial on a colour of its own. A port of Android's
/// `screens/home/ProfileAvatar`, held to its test cases, so a profile has the
/// same colour on the Pocket and on Apple devices.
///
/// Jellyfin gives profiles no colour, so one is chosen the same way every
/// time: the profiles in alphabetical order take the palette in order. Four
/// profiles always get four different colours, which hashing each id could not
/// promise, and a household gets the same colours whatever order the hub
/// lists it in.
public enum Profiles {
    /// The prototype's four, in its order, then four more for a bigger household.
    public static let palette: [UInt32] = [
        0xFF8B_7BFF, 0xFF2C_C4AD, 0xFFF2_A541, 0xFFFF_6B7D,
        0xFF5A_A9FF, 0xFFB8_E06A, 0xFFE5_8BE0, 0xFFFF_D166,
    ]

    /// The profiles in the order their colours are given: by name trimmed and
    /// in lower case, then by id, each id once (the first kept). Names compare
    /// by UTF-16 code unit, as Kotlin's strings do.
    public static func ordered(_ users: [HubUser]) -> [HubUser] {
        var seen = Set<String>()
        let unique = users.filter { seen.insert($0.id).inserted }
        return unique.sorted { a, b in
            let ka = sortName(a.name), kb = sortName(b.name)
            if ka != kb { return ka.utf16.lexicographicallyPrecedes(kb.utf16) }
            return a.id.utf16.lexicographicallyPrecedes(b.id.utf16)
        }
    }

    /// Each profile's colour by its id, worked out from the whole set.
    public static func colors(_ users: [HubUser]) -> [String: UInt32] {
        var out: [String: UInt32] = [:]
        for (index, user) in ordered(users).enumerated() {
            out[user.id] = palette[index % palette.count]
        }
        return out
    }

    /// One profile's colour; nil for a profile not in `users`.
    public static func color(of userId: String, in users: [HubUser]) -> UInt32? {
        colors(users)[userId]
    }

    /// The letter on the avatar: the name's first letter or digit in capitals,
    /// else its first character, and "?" for a name with nothing in it.
    public static func initial(_ name: String) -> String {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = trimmed.unicodeScalars.first else { return "?" }
        if let letter = trimmed.unicodeScalars.first(where: isLetterOrDigit) {
            return String(letter).uppercased()
        }
        return String(first)
    }

    /// The profile this device watches as: its own choice while the hub still
    /// lists it, else the hub's default (`selected`), as the profile picker
    /// has always decided.
    public static func current(_ users: [HubUser], chosen: String) -> HubUser? {
        if !chosen.isEmpty, let mine = users.first(where: { $0.id == chosen }) { return mine }
        return users.first(where: \.selected)
    }

    private static func sortName(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    /// Java's `Character.isLetterOrDigit`: a letter of any case or kind, or a
    /// decimal digit. Marks and other numbers are not.
    private static func isLetterOrDigit(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter, .decimalNumber:
            true
        default:
            false
        }
    }
}

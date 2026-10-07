import Foundation

/// Which accent each side of the app wears, kept per hub and per Jellyfin
/// profile (#38; Android's `settings/DomainPreferences` and `PreferenceScope`,
/// held to `AppearancePolicyTest`). A person's Media teal and Books gold are
/// theirs on this hub: another profile, or another hub, starts from the
/// defaults again.
extension AccentPreset {
    /// The first palette's names, so a choice made before the pastels keeps
    /// its nearest colour.
    private static let retired: [String: AccentPreset] = [
        "blue": .sky, "indigo": .lavender, "violet": .lilac, "coral": .peach,
        "amber": .gold, "olive": .sage, "green": .mint, "cyan": .teal,
    ]

    /// The preset a stored id names: a current one, a retired one's nearest,
    /// or `fallback` for anything else.
    public static func fromStored(_ id: String?, fallback: AccentPreset = .teal) -> AccentPreset {
        guard let id else { return fallback }
        return AccentPreset(rawValue: id) ?? retired[id] ?? fallback
    }
}

public enum PreferenceScope {
    /// A hub, a profile and a side as one key. The address is compared as a
    /// person means it: any case in the scheme and host, no trailing slash,
    /// and the scheme's own port written or not.
    public static func key(hub: String, profile: String, side: AppSide) -> String {
        "\(address(hub))|\(profile.trimmingCharacters(in: .whitespacesAndNewlines))|\(side.rawValue)"
    }

    static func address(_ hub: String) -> String {
        let trimmed = hub.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let parts = URLComponents(string: trimmed), let scheme = parts.scheme?.lowercased(), let host = parts.host?.lowercased()
        else { return trimmed.trimmingSlashes() }
        let port = parts.port.flatMap { port -> Int? in
            (scheme == "https" && port == 443) || (scheme == "http" && port == 80) ? nil : port
        }
        return "\(scheme)://\(host)\(port.map { ":\($0)" } ?? "")\(parts.path.trimmingSlashes())"
    }
}

private extension String {
    func trimmingSlashes() -> String {
        var text = Substring(self)
        while text.hasSuffix("/") { text = text.dropLast() }
        return String(text)
    }
}

/// The chosen accents on this device.
public enum AccentSettings {
    private static func storageKey(hub: String, profile: String, side: AppSide) -> String {
        "accent." + PreferenceScope.key(hub: hub, profile: profile, side: side)
    }

    public static func accent(for side: AppSide, hub: String, profile: String, in defaults: UserDefaults = .standard) -> AccentPreset {
        .fromStored(defaults.string(forKey: storageKey(hub: hub, profile: profile, side: side)), fallback: .defaultFor(side))
    }

    public static func set(_ preset: AccentPreset, for side: AppSide, hub: String, profile: String,
                           in defaults: UserDefaults = .standard) {
        defaults.set(preset.rawValue, forKey: storageKey(hub: hub, profile: profile, side: side))
    }

    /// The words beside the swatches: what the colour is used for on each side.
    public static func hint(for side: AppSide) -> String {
        switch side {
        case .media: "Play buttons, progress and the chosen tab while you watch"
        case .books: "The same places while you read, so books feel like their own space"
        }
    }

    public static func title(for side: AppSide) -> String {
        side == .media ? "Movies and TV" : "Books"
    }

    /// A sample button's label, as the side's own main action says it.
    public static func sample(for side: AppSide) -> String {
        side == .media ? "Play" : "Continue reading"
    }
}

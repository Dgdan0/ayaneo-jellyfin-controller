import Foundation

/// The four colours the Glass look tints with, as 0xAARRGGBB. Mirrors
/// Android's `ui/glass/ArtworkPalette`.
public struct ArtworkPalette: Equatable, Hashable, Sendable {
    public var dominant: UInt32
    public var dark: UInt32
    public var vivid: UInt32
    public var light: UInt32

    public init(dominant: UInt32, dark: UInt32, vivid: UInt32, light: UInt32) {
        self.dominant = dominant
        self.dark = dark
        self.vivid = vivid
        self.light = light
    }

    /// Before the colours arrive, and for artwork that has none: the Glass base.
    public static let neutral = ArtworkPalette(dominant: 0xFF2A_3140, dark: 0xFF0B_0D12, vivid: 0xFF8A_97AD, light: 0xFFE6_EAF0)

    /// Nil when the hub sent something that is not four colours.
    public init?(_ set: ArtworkColorSet) {
        guard let dominant = GlassColors.parse(set.dominant), let dark = GlassColors.parse(set.dark),
              let vivid = GlassColors.parse(set.vivid), let light = GlassColors.parse(set.light) else { return nil }
        self.init(dominant: dominant, dark: dark, vivid: vivid, light: light)
    }

    /// "#rrggbb" for each colour, in the order the device file keeps them.
    public var hexes: [String] { [dominant, dark, vivid, light].map(GlassColors.hex) }
}

/// Colour arithmetic for Glass. A port of Android's `ui/glass/GlassColors`,
/// held to the same numbers by the tests, so both apps tint a panel alike.
///
/// Panels are real material on Apple. These tints are for Reduce transparency
/// and for drawing that must match the Pocket exactly.
public enum GlassColors {
    /// The ground every tinted panel is mixed into: the prototype's #12141C.
    public static let panelBase: UInt32 = 0xFF12_141C
    /// How much of the artwork's colour a panel takes.
    public static let panelTint = 0.30
    /// A panel's opacity over the ambient layer: 80%.
    public static let panelAlpha: UInt32 = 0xCC
    /// The hairline round a panel and the light along its top edge.
    public static let edge: UInt32 = 0x2BFF_FFFF
    public static let highlight: UInt32 = 0x26FF_FFFF
    /// A sheet's or a dialog's opacity: 95% (`sheet`).
    public static let sheetAlpha: UInt32 = 0xF2
    /// Words and icons on a white pill: the chosen tab, the white Play or
    /// Resume button, a lit round button. Never the page colour, which in
    /// Glass paints nothing. The prototype's #0B0D12.
    public static let ink: UInt32 = 0xFF0B_0D12
    /// A notification count on its button.
    public static let badge: UInt32 = 0xFFFF_5A5F

    public static func parse(_ hex: String) -> UInt32? {
        guard hex.count == 7, hex.first == "#", let rgb = UInt32(hex.dropFirst(), radix: 16) else { return nil }
        return 0xFF00_0000 | rgb
    }

    public static func hex(_ c: UInt32) -> String { String(format: "#%06x", c & 0xFF_FFFF) }

    public static func alpha(_ c: UInt32) -> UInt32 { c >> 24 }
    public static func red(_ c: UInt32) -> UInt32 { (c >> 16) & 0xFF }
    public static func green(_ c: UInt32) -> UInt32 { (c >> 8) & 0xFF }
    public static func blue(_ c: UInt32) -> UInt32 { c & 0xFF }

    public static func withAlpha(_ c: UInt32, _ a: UInt32) -> UInt32 { (min(a, 255) << 24) | (c & 0xFF_FFFF) }

    /// `t` of the way from `a` to `b`, every channel including alpha. Rounds
    /// half up, as Kotlin's `Math.round` does, so both apps land on one value.
    public static func mix(_ a: UInt32, _ b: UInt32, _ t: Double) -> UInt32 {
        let k = max(0, min(1, t))
        func ch(_ x: UInt32, _ y: UInt32) -> UInt32 {
            UInt32((Double(x) + (Double(y) - Double(x)) * k + 0.5).rounded(.down))
        }
        return (ch(alpha(a), alpha(b)) << 24) | (ch(red(a), red(b)) << 16) | (ch(green(a), green(b)) << 8) | ch(blue(a), blue(b))
    }

    /// A panel over the ambient layer: the base, tinted by the artwork, at 80%.
    public static func panel(_ p: ArtworkPalette) -> UInt32 { withAlpha(mix(panelBase, p.dominant, panelTint), panelAlpha) }

    /// The bars along the top and bottom: a touch more solid than a panel.
    public static func bar(_ p: ArtworkPalette) -> UInt32 { withAlpha(mix(panelBase, p.dominant, panelTint * 0.9), 0xD6) }

    /// A side sheet or a dialog (the account sheet, a menu, the request
    /// form): the panel's tint, nearly solid. A panel can be see-through
    /// because what is under it is the already blurred page; a sheet opens
    /// over a screen's own words, and those showing through fight its own.
    public static func sheet(_ p: ArtworkPalette) -> UInt32 { withAlpha(panel(p), sheetAlpha) }

    /// WCAG relative luminance, 0 for black to 1 for white.
    public static func luminance(_ c: UInt32) -> Double {
        func lin(_ v: UInt32) -> Double {
            let s = Double(v) / 255
            return s <= 0.03928 ? s / 12.92 : pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * lin(red(c)) + 0.7152 * lin(green(c)) + 0.0722 * lin(blue(c))
    }

    /// WCAG contrast ratio of two opaque colours, from 1 to 21.
    public static func contrast(_ a: UInt32, _ b: UInt32) -> Double {
        let la = luminance(a), lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /// What a translucent `top` looks like over an opaque `under`.
    public static func over(_ top: UInt32, _ under: UInt32) -> UInt32 {
        withAlpha(mix(under, withAlpha(top, 0xFF), Double(alpha(top)) / 255), 0xFF)
    }
}

/// The picture behind a hub image path, as the hub keys an artwork's colours
/// (`artworkColorKey` in `hub/internal/api/artwork_colors.go`): a Jellyfin
/// image by its item, type and tag, a TMDB image by its file, the others by
/// their path. So one picture asked for at two widths ("w=360", "w342") is
/// one entry. Anything that is not a hub image path stands for itself.
public enum ArtworkColorKey {
    public static func of(_ src: String) -> String {
        guard let parts = URLComponents(string: src), parts.scheme == nil, parts.host == nil,
              parts.path.hasPrefix("/v1/img/") else { return src }
        let rest = String(parts.path.dropFirst("/v1/img/".count))
        let pieces = rest.split(separator: "/", omittingEmptySubsequences: false)
        switch pieces.first.map(String.init) ?? "" {
        case "jf" where pieces.count == 3:
            // The tag names the picture; the width does not.
            return rest + "/" + (parts.queryItems?.first { $0.name == "tag" }?.value ?? "")
        case "tmdb" where pieces.count == 3:
            // The file names the picture; the size does not.
            return "tmdb/" + pieces[2]
        case "arr" where pieces.count >= 2, "reading" where pieces.count >= 2:
            return rest
        default:
            return src
        }
    }
}

/// What the app knows about each artwork's colours, and what to ask the hub
/// next. A port of Android's `ui/glass/ArtworkColorBook`, held to the same
/// rules by the tests: sixty per request, pending asked again after 3, 10 and
/// 30 seconds and then left, missing never asked again, the least recently
/// used dropped first. Unlike Android's, it keeps a picture once whatever
/// width it is shown at (`ArtworkColorKey`), as the hub does, so a poster's
/// colours also tint its larger copy without asking again.
public struct ArtworkColorBook: Sendable {
    public static let maxPerRequest = 60

    private let capacity: Int
    private let retryAfter: [TimeInterval]
    /// By picture (`ArtworkColorKey`).
    private var known: [String: ArtworkPalette] = [:]
    /// Least recently used first.
    private var order: [String] = []
    private var missing: Set<String> = []
    /// By picture, with the path to ask the hub with.
    private var waiting: [String: (attempts: Int, dueAt: TimeInterval, src: String)] = [:]
    private var asking: Set<String> = []

    public init(capacity: Int = 2000, retryAfter: [TimeInterval] = [3, 10, 30]) {
        self.capacity = capacity
        self.retryAfter = retryAfter
    }

    /// Known colours for `src`, at any width; using them makes them recent.
    public mutating func palette(_ src: String) -> ArtworkPalette? {
        let key = ArtworkColorKey.of(src)
        guard let p = known[key] else { return nil }
        touch(key)
        return p
    }

    public func isMissing(_ src: String) -> Bool { missing.contains(ArtworkColorKey.of(src)) }

    /// Whether `src` has colours, without making them recent.
    public func knows(_ src: String) -> Bool { known[ArtworkColorKey.of(src)] != nil }

    /// Which of `sources` to ask for now, at most `limit`, one path per
    /// picture. They count as being asked until `answered` or `failed`.
    public mutating func toAsk(_ sources: [String], now: TimeInterval, limit: Int = maxPerRequest) -> [String] {
        var out: [String] = []
        var outKeys: Set<String> = []
        for src in sources {
            if out.count >= limit { break }
            guard !src.isEmpty else { continue }
            let key = ArtworkColorKey.of(src)
            if known[key] != nil || missing.contains(key) || asking.contains(key) || outKeys.contains(key) { continue }
            if let retry = waiting[key], retry.dueAt > now { continue }
            out.append(src)
            outKeys.insert(key)
        }
        asking.formUnion(outKeys)
        return out
    }

    /// Records the hub's answer to `asked`, whose keys it echoes exactly, and
    /// returns the sources that now have colours. Anything in neither list is
    /// treated as pending.
    @discardableResult
    public mutating func answered(_ asked: [String], colors: [String: ArtworkPalette], missing missingNow: [String], now: TimeInterval) -> [String] {
        var got: [String] = []
        let gone = Set(missingNow)
        for src in asked {
            let key = ArtworkColorKey.of(src)
            asking.remove(key)
            if let palette = colors[src] {
                remember(key, palette)
                waiting[key] = nil
                got.append(src)
            } else if gone.contains(src) {
                missing.insert(key)
                waiting[key] = nil
            } else {
                later(key, src: src, now: now)
            }
        }
        return got
    }

    /// The request itself failed: everything in it waits and is asked again.
    public mutating func failed(_ asked: [String], now: TimeInterval) {
        for src in asked {
            let key = ArtworkColorKey.of(src)
            asking.remove(key)
            later(key, src: src, now: now)
        }
    }

    /// Paths whose wait is over and are not being asked, one per picture.
    public func due(now: TimeInterval) -> [String] {
        waiting.filter { $0.value.dueAt <= now && !asking.contains($0.key) }.map(\.value.src).sorted()
    }

    /// When the next waiting source falls due, or nil when nothing waits.
    public var nextDueAt: TimeInterval? { waiting.values.map(\.dueAt).min() }

    /// Each picture's key and colours, least recently used first, for the
    /// device's file.
    public var snapshot: [(String, ArtworkPalette)] { order.compactMap { key in known[key].map { (key, $0) } } }

    /// Colours kept from an earlier run, by picture or (from an older file) by
    /// path; anything learnt since wins.
    public mutating func restore(_ entries: [(String, ArtworkPalette)]) {
        for (src, palette) in entries where !src.isEmpty {
            let key = ArtworkColorKey.of(src)
            if known[key] == nil { remember(key, palette) }
        }
    }

    private mutating func remember(_ key: String, _ palette: ArtworkPalette) {
        if known[key] == nil { order.append(key) } else { touch(key) }
        known[key] = palette
        missing.remove(key)
        while order.count > capacity {
            known[order.removeFirst()] = nil
        }
    }

    private mutating func touch(_ key: String) {
        if let i = order.firstIndex(of: key) {
            order.remove(at: i)
            order.append(key)
        }
    }

    private mutating func later(_ key: String, src: String, now: TimeInterval) {
        let retry = waiting[key] ?? (attempts: 0, dueAt: now, src: src)
        if retry.attempts >= retryAfter.count {
            // Pending through every wait: stop asking this session, as for missing.
            waiting[key] = nil
            missing.insert(key)
            return
        }
        waiting[key] = (attempts: retry.attempts + 1, dueAt: now + retryAfter[retry.attempts], src: src)
    }
}

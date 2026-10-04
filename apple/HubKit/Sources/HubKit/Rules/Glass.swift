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

/// What the app knows about each artwork's colours, and what to ask the hub
/// next. A port of Android's `ui/glass/ArtworkColorBook`, held to the same
/// rules by the tests: sixty per request, pending asked again after 3, 10 and
/// 30 seconds and then left, missing never asked again, the least recently
/// used dropped first.
public struct ArtworkColorBook: Sendable {
    public static let maxPerRequest = 60

    private let capacity: Int
    private let retryAfter: [TimeInterval]
    private var known: [String: ArtworkPalette] = [:]
    /// Least recently used first.
    private var order: [String] = []
    private var missing: Set<String> = []
    private var waiting: [String: (attempts: Int, dueAt: TimeInterval)] = [:]
    private var asking: Set<String> = []

    public init(capacity: Int = 2000, retryAfter: [TimeInterval] = [3, 10, 30]) {
        self.capacity = capacity
        self.retryAfter = retryAfter
    }

    /// Known colours for `src`; using them makes them recent.
    public mutating func palette(_ src: String) -> ArtworkPalette? {
        guard let p = known[src] else { return nil }
        touch(src)
        return p
    }

    public func isMissing(_ src: String) -> Bool { missing.contains(src) }

    /// Whether `src` has colours, without making them recent.
    public func knows(_ src: String) -> Bool { known[src] != nil }

    /// Which of `sources` to ask for now, at most `limit`. They count as being
    /// asked until `answered` or `failed`.
    public mutating func toAsk(_ sources: [String], now: TimeInterval, limit: Int = maxPerRequest) -> [String] {
        var out: [String] = []
        for src in sources {
            if out.count >= limit { break }
            if src.isEmpty || known[src] != nil || missing.contains(src) || asking.contains(src) || out.contains(src) { continue }
            if let retry = waiting[src], retry.dueAt > now { continue }
            out.append(src)
        }
        asking.formUnion(out)
        return out
    }

    /// Records the hub's answer to `asked` and returns the sources that now
    /// have colours. Anything in neither list is treated as pending.
    @discardableResult
    public mutating func answered(_ asked: [String], colors: [String: ArtworkPalette], missing missingNow: [String], now: TimeInterval) -> [String] {
        var got: [String] = []
        let gone = Set(missingNow)
        for src in asked {
            asking.remove(src)
            if let palette = colors[src] {
                remember(src, palette)
                waiting[src] = nil
                got.append(src)
            } else if gone.contains(src) {
                missing.insert(src)
                waiting[src] = nil
            } else {
                later(src, now: now)
            }
        }
        return got
    }

    /// The request itself failed: everything in it waits and is asked again.
    public mutating func failed(_ asked: [String], now: TimeInterval) {
        for src in asked {
            asking.remove(src)
            later(src, now: now)
        }
    }

    /// Sources whose wait is over and are not being asked.
    public func due(now: TimeInterval) -> [String] {
        waiting.filter { $0.value.dueAt <= now && !asking.contains($0.key) }.keys.sorted()
    }

    /// When the next waiting source falls due, or nil when nothing waits.
    public var nextDueAt: TimeInterval? { waiting.values.map(\.dueAt).min() }

    /// Least recently used first, for the device's file.
    public var snapshot: [(String, ArtworkPalette)] { order.compactMap { src in known[src].map { (src, $0) } } }

    /// Colours kept from an earlier run; anything learnt since wins.
    public mutating func restore(_ entries: [(String, ArtworkPalette)]) {
        for (src, palette) in entries where !src.isEmpty && known[src] == nil { remember(src, palette) }
    }

    private mutating func remember(_ src: String, _ palette: ArtworkPalette) {
        if known[src] == nil { order.append(src) } else { touch(src) }
        known[src] = palette
        missing.remove(src)
        while order.count > capacity {
            known[order.removeFirst()] = nil
        }
    }

    private mutating func touch(_ src: String) {
        if let i = order.firstIndex(of: src) {
            order.remove(at: i)
            order.append(src)
        }
    }

    private mutating func later(_ src: String, now: TimeInterval) {
        let retry = waiting[src] ?? (attempts: 0, dueAt: now)
        if retry.attempts >= retryAfter.count {
            // Pending through every wait: stop asking this session, as for missing.
            waiting[src] = nil
            missing.insert(src)
            return
        }
        waiting[src] = (attempts: retry.attempts + 1, dueAt: now + retryAfter[retry.attempts])
    }
}

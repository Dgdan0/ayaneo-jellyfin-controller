import Foundation

/// Comfort in a long session (#37; Android's `ScreenComfort`, #16 X3): how
/// bright and how warm the app draws a reader, and whether the screen stays
/// on while narration plays. Kept for every reader and every book
/// (`ComfortStore`): dim a comic at night and the next book opens just as dim.
/// (A black page is the Dark theme's now, #47.)
///
/// Drawn, never the device's own backlight: black laid over what the reader
/// shows (`dimAlpha`) and a warm colour multiplied into it (`warmColor`),
/// which turns white amber and leaves black black, so a black page stays
/// black at any warmth.
public struct ScreenComfort: Equatable, Codable, Sendable {
    /// 1 is as drawn; `minBrightness` is the dimmest a reader goes.
    public var brightness: Double
    /// 0 is as drawn; 1 is candlelight.
    public var warmth: Double
    /// The screen stays on while a book's narration plays.
    public var awakeWhileNarrating: Bool

    public static let minBrightness = 0.1
    /// How dark the dimmest is: the player's measure, which lets a little of the page through.
    public static let maxDim = 0.85
    public static let white: UInt32 = 0xFFFF_FFFF
    /// White at full warmth, about 2,800 K: the warm end of a reading lamp.
    public static let candle: UInt32 = 0xFFFF_B46B

    public init(brightness: Double = 1, warmth: Double = 0, awakeWhileNarrating: Bool = true) {
        self.brightness = brightness
        self.warmth = warmth
        self.awakeWhileNarrating = awakeWhileNarrating
    }

    public var dimAlpha: Double { Self.dimAlpha(brightness) }
    public var warmColor: UInt32 { Self.warmColor(warmth) }

    /// Nothing to draw over the page.
    public var drawsNothing: Bool { dimAlpha <= 0 && warmColor == Self.white }

    public func keepsScreenOn(narrating: Bool) -> Bool { narrating && awakeWhileNarrating }

    /// The black laid over the page: none at full brightness, `maxDim` at none.
    public static func dimAlpha(_ brightness: Double) -> Double { (1 - min(max(brightness, 0), 1)) * maxDim }

    /// What white becomes at `warmth`, multiplied into the page: from white to `candle`.
    public static func warmColor(_ warmth: Double) -> UInt32 {
        let k = min(max(warmth, 0), 1)
        func channel(_ shift: UInt32) -> UInt32 {
            let to = Double((candle >> shift) & 0xFF)
            return UInt32((255 + (to - 255) * k).rounded())
        }
        return 0xFF00_0000 | channel(16) << 16 | channel(8) << 8 | channel(0)
    }

    /// "60%".
    public static func brightnessLabel(_ brightness: Double) -> String { "\(Int((min(max(brightness, 0), 1) * 100).rounded()))%" }

    /// "Off", or how warm: "40%".
    public static func warmthLabel(_ warmth: Double) -> String {
        warmth <= 0.001 ? "Off" : "\(Int((min(max(warmth, 0), 1) * 100).rounded()))%"
    }
}

/// Where Comfort is kept: one for every reader and every book.
public struct ComfortStore: @unchecked Sendable {
    static let key = "reader.comfort"
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> ScreenComfort {
        guard let data = defaults.data(forKey: Self.key), var comfort = try? JSONDecoder().decode(ScreenComfort.self, from: data) else {
            return ScreenComfort()
        }
        comfort.brightness = min(max(comfort.brightness, ScreenComfort.minBrightness), 1)
        comfort.warmth = min(max(comfort.warmth, 0), 1)
        return comfort
    }

    public func save(_ comfort: ScreenComfort) {
        if let data = try? JSONEncoder().encode(comfort) { defaults.set(data, forKey: Self.key) }
    }

    /// Whether a device kept the black page Comfort had until #47, and gone:
    /// the stored Comfort is written again without it. Dark is that page now.
    static func takeBlackPage(_ defaults: UserDefaults) -> Bool {
        guard let data = defaults.data(forKey: key),
              var stored = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let had = stored.removeValue(forKey: "blackPage") as? Bool else { return false }
        if let rewritten = try? JSONSerialization.data(withJSONObject: stored) { defaults.set(rewritten, forKey: key) }
        return had
    }
}

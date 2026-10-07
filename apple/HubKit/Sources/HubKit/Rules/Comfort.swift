import Foundation

/// Comfort in a long session (#37; Android's `ScreenComfort`, #16 X3): how
/// bright and how warm the app draws a reader, whether a book's page is pure
/// black, and whether the screen stays on while narration plays. Kept for
/// every reader and every book (`ComfortStore`): dim a comic at night and the
/// next book opens just as dim.
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
    /// A book's page in black, its words a soft warm grey.
    public var blackPage: Bool
    /// The screen stays on while a book's narration plays.
    public var awakeWhileNarrating: Bool

    public static let minBrightness = 0.1
    /// How dark the dimmest is: the player's measure, which lets a little of the page through.
    public static let maxDim = 0.85
    public static let white: UInt32 = 0xFFFF_FFFF
    /// White at full warmth, about 2,800 K: the warm end of a reading lamp.
    public static let candle: UInt32 = 0xFFFF_B46B
    /// The black page and its words: not white, which glares on black in the dark.
    public static let blackPageColor: UInt32 = 0xFF00_0000
    public static let blackPageText: UInt32 = 0xFFC9_C3B6

    public init(brightness: Double = 1, warmth: Double = 0, blackPage: Bool = false, awakeWhileNarrating: Bool = true) {
        self.brightness = brightness
        self.warmth = warmth
        self.blackPage = blackPage
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

    /// The black page's line in the sheet.
    public static func blackPageDetail(_ on: Bool) -> String {
        on ? "On · for the dark, and an OLED's black" : "Off · the page colour in Appearance"
    }
}

/// Where Comfort is kept: one for every reader and every book.
public struct ComfortStore: @unchecked Sendable {
    private let key = "reader.comfort"
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> ScreenComfort {
        guard let data = defaults.data(forKey: key), var comfort = try? JSONDecoder().decode(ScreenComfort.self, from: data) else {
            return ScreenComfort()
        }
        comfort.brightness = min(max(comfort.brightness, ScreenComfort.minBrightness), 1)
        comfort.warmth = min(max(comfort.warmth, 0), 1)
        return comfort
    }

    public func save(_ comfort: ScreenComfort) {
        if let data = try? JSONEncoder().encode(comfort) { defaults.set(data, forKey: key) }
    }
}

extension EpubRendering {
    /// The page as Comfort says (#37): a black page in Night's layout, its
    /// words a soft warm grey; else as Appearance chose.
    public func comforted(_ comfort: ScreenComfort) -> EpubRendering {
        guard comfort.blackPage else { return self }
        var next = self
        next.theme = "dark"
        next.background = EpubPagePalette.hex(ScreenComfort.blackPageColor)
        next.text = EpubPagePalette.hex(ScreenComfort.blackPageText)
        return next
    }
}

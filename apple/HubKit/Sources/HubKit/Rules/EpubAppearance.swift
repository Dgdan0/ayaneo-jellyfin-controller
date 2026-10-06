import Foundation

// How a book looks (#25, phase 4): Android's `reader/EpubReaderState.kt`,
// `EpubAppearanceStore` and `EpubPagePalette` (#18), held to their test cases
// in `BookReaderRulesTests`. One appearance for every book, applied live and
// kept with each change.

public enum EpubTheme: String, CaseIterable, Sendable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case sepia = "SEPIA"
    case dark = "DARK"
    case blue = "BLUE"
}

public enum EpubColumns: String, CaseIterable, Sendable {
    case auto = "AUTO"
    case one = "ONE"
    case two = "TWO"
}

public struct EpubReaderPreferences: Equatable, Sendable {
    public var theme: EpubTheme
    /// "publisher", "serif" or "sans-serif".
    public var fontFamily: String
    public var fontScale: Double
    public var lineHeight: Double
    public var pageMargins: Double
    public var columns: EpubColumns
    public var scroll: Bool
    public var publisherStyles: Bool
    /// "start" or "justify".
    public var textAlignment: String
    public var onePagePerScreen: Bool

    public init(theme: EpubTheme = .sepia, fontFamily: String = "publisher", fontScale: Double = 1,
                lineHeight: Double = 1.25, pageMargins: Double = 1, columns: EpubColumns = .auto, scroll: Bool = false,
                publisherStyles: Bool = true, textAlignment: String = "start", onePagePerScreen: Bool = false) {
        self.theme = theme
        self.fontFamily = fontFamily
        self.fontScale = fontScale
        self.lineHeight = lineHeight
        self.pageMargins = pageMargins
        self.columns = columns
        self.scroll = scroll
        self.publisherStyles = publisherStyles
        self.textAlignment = textAlignment
        self.onePagePerScreen = onePagePerScreen
    }

    /// Scrolling for real: one page per screen turns pages whatever Scroll says.
    public var scrolls: Bool { scroll && !onePagePerScreen }
}

/// Columns, scrolling and one page per screen, which rule each other out.
public enum EpubLayoutPolicy {
    /// Automatic columns read two side by side from this width, in points.
    public static let autoTwoColumnMinWidth = 840.0

    public static func columnCount(_ preferences: EpubReaderPreferences, viewportWidth: Double,
                                   publicationAllowsSpreads: Bool) -> Int {
        if preferences.onePagePerScreen || preferences.scroll || !publicationAllowsSpreads { return 1 }
        switch preferences.columns {
        case .one: return 1
        case .two: return 2
        case .auto: return viewportWidth >= autoTwoColumnMinWidth ? 2 : 1
        }
    }

    public static func selectColumns(_ value: EpubReaderPreferences, _ columns: EpubColumns) -> EpubReaderPreferences {
        var next = value
        next.columns = columns
        if columns == .two { next.scroll = false }
        next.onePagePerScreen = false
        return next
    }

    public static func selectScroll(_ value: EpubReaderPreferences, _ enabled: Bool) -> EpubReaderPreferences {
        var next = value
        next.scroll = enabled
        if enabled && value.columns == .two { next.columns = .auto }
        if enabled { next.onePagePerScreen = false }
        return next
    }

    public static func selectOnePage(_ value: EpubReaderPreferences, _ enabled: Bool) -> EpubReaderPreferences {
        var next = value
        next.onePagePerScreen = enabled
        if enabled {
            next.columns = .one
            next.scroll = false
        }
        return next
    }
}

public enum EpubChromePolicy {
    /// A tap in the middle third shows or hides the menu; the sides are the
    /// book's own (Readium turns the page). With the menu open, any tap on
    /// the page closes it.
    public static func handlesTap(_ horizontalFraction: Double, controlsVisible: Bool) -> Bool {
        controlsVisible || (0.3...0.7).contains(horizontalFraction)
    }
}

/// Appearance is applied live and committed with each change. Closing keeps the last setting.
public struct EpubPreferenceState: Sendable {
    public private(set) var visible: EpubReaderPreferences
    public private(set) var saved: EpubReaderPreferences
    public private(set) var dirty = false

    public init(_ initial: EpubReaderPreferences) {
        visible = initial
        saved = initial
    }

    public mutating func preview(_ value: EpubReaderPreferences) { visible = value }

    public mutating func cancel() { visible = saved }

    public mutating func commit() -> Bool {
        if visible == saved { return false }
        saved = visible
        dirty = true
        return true
    }

    public mutating func markPersisted() { dirty = false }
}

/// A theme's page and ink, as ARGB.
public enum EpubPagePalette {
    public static func of(_ theme: EpubTheme) -> (page: UInt32, ink: UInt32)? {
        switch theme {
        case .light: (0xFFFB_FAF6, 0xFF28_2B29)
        case .sepia: (0xFFEF_E2C6, 0xFF3E_3526)
        case .dark: (0xFF20_2020, 0xFFDF_DFD8)
        case .blue: (0xFF1D_303D, 0xFFDC_E6E8)
        case .system: nil
        }
    }

    /// "#FBFAF6": how Readium takes a colour.
    public static func hex(_ argb: UInt32) -> String {
        String(format: "#%06X", argb & 0xFF_FFFF)
    }
}

/// What the book is drawn with, as Readium's preferences want it: Android's
/// `readiumPreferences`, decided here so it is tested, and turned into
/// Readium's own types by the reader.
public struct EpubRendering: Equatable, Sendable {
    /// "light", "sepia" or "dark".
    public let theme: String
    public let background: String
    public let text: String
    public let columns: EpubColumns
    /// "serif", "sans-serif", or nil for the publisher's.
    public let fontFamily: String?
    public let fontSize: Double
    public let lineHeight: Double
    public let pageMargins: Double
    public let publisherStyles: Bool
    public let scroll: Bool
    /// "start" or "justify".
    public let textAlign: String

    /// System colours follow the device: Paper by day, Night after dark.
    public init(_ value: EpubReaderPreferences, systemDark: Bool) {
        let theme: EpubTheme = value.theme == .system ? (systemDark ? .dark : .light) : value.theme
        self.theme = switch theme {
        case .light, .system: "light"
        case .sepia: "sepia"
        case .dark, .blue: "dark"
        }
        let palette = EpubPagePalette.of(theme) ?? EpubPagePalette.of(.light)!
        background = EpubPagePalette.hex(palette.page)
        text = EpubPagePalette.hex(palette.ink)
        columns = value.onePagePerScreen ? .one : value.columns
        fontFamily = value.fontFamily == "serif" || value.fontFamily == "sans-serif" || value.fontFamily == "monospace"
            ? value.fontFamily : nil
        fontSize = value.fontScale
        lineHeight = value.lineHeight
        pageMargins = value.pageMargins
        publisherStyles = value.publisherStyles
        scroll = value.scrolls
        textAlign = value.textAlignment == "justify" ? "justify" : "start"
    }
}

/// The Appearance sheet's choices (Android's `EpubAppearancePanel`): what
/// each tile and row sets, so the sheet only draws them.
public enum EpubAppearance {
    public static let typefaces: [(id: String, label: String)] = [
        ("publisher", "Publisher"), ("serif", "Serif"), ("sans-serif", "Sans"),
    ]
    public static let themes: [(theme: EpubTheme, label: String)] = [
        (.light, "Paper"), (.sepia, "Sepia"), (.dark, "Night"), (.blue, "Blue"),
    ]
    public static let margins: [(amount: Double, label: String)] = [(0.5, "Narrow"), (1, "Balanced"), (1.7, "Wide")]
    public static let spacing: [(amount: Double, label: String)] = [(1.1, "Tight"), (1.5, "Relaxed"), (1.9, "Open")]
    public static let fontScales: ClosedRange<Double> = 0.7...2
    public static let fontStep = 0.1

    /// "Publisher" leaves the book's own styles on; another face takes them off.
    public static func typeface(_ value: EpubReaderPreferences, _ id: String) -> EpubReaderPreferences {
        var next = value
        next.fontFamily = id
        next.publisherStyles = id == "publisher"
        return next
    }

    /// A tenth larger or smaller, from 70% to 200%.
    public static func fontSize(_ value: EpubReaderPreferences, steps: Int) -> EpubReaderPreferences {
        var next = value
        let tenths = (value.fontScale * 10).rounded() + Double(steps)
        next.fontScale = min(max(tenths / 10, fontScales.lowerBound), fontScales.upperBound)
        return next
    }

    /// "120%".
    public static func fontSizeLabel(_ scale: Double) -> String { "\(Int((scale * 100).rounded()))%" }

    public static func lineSpacing(_ value: EpubReaderPreferences, _ amount: Double) -> EpubReaderPreferences {
        var next = value
        next.lineHeight = amount
        next.publisherStyles = false
        return next
    }

    public static func justified(_ value: EpubReaderPreferences) -> EpubReaderPreferences {
        var next = value
        next.textAlignment = value.textAlignment == "justify" ? "start" : "justify"
        next.publisherStyles = false
        return next
    }

    /// Whether `amount` is the one chosen: margins and spacing are kept as numbers.
    public static func same(_ left: Double, _ right: Double) -> Bool { abs(left - right) < 0.01 }
}

/// Shared by every book, kept on this device under Android's names.
public enum EpubAppearanceStore {
    static let prefix = "epub."

    public static func load(_ defaults: UserDefaults) -> EpubReaderPreferences {
        func string(_ key: String) -> String? { defaults.string(forKey: prefix + key) }
        func number(_ key: String, _ fallback: Double, _ range: ClosedRange<Double>) -> Double {
            guard defaults.object(forKey: prefix + key) != nil else { return fallback }
            let value = defaults.double(forKey: prefix + key)
            return value.isNaN ? fallback : min(max(value, range.lowerBound), range.upperBound)
        }
        func flag(_ key: String, _ fallback: Bool) -> Bool {
            defaults.object(forKey: prefix + key) == nil ? fallback : defaults.bool(forKey: prefix + key)
        }
        let value = EpubReaderPreferences(
            theme: string("theme").flatMap(EpubTheme.init(rawValue:)) ?? .sepia,
            fontFamily: string("fontFamily") ?? "publisher",
            fontScale: number("fontScale", 1, 0.7...2),
            lineHeight: number("lineHeight", 1.25, 1...2),
            pageMargins: number("pageMargins", 1, 0.5...2),
            columns: string("columns").flatMap(EpubColumns.init(rawValue:)) ?? .auto,
            scroll: flag("scroll", false),
            publisherStyles: flag("publisherStyles", true),
            textAlignment: string("textAlignment") ?? "start",
            onePagePerScreen: flag("onePagePerScreen", false))
        return value.onePagePerScreen ? EpubLayoutPolicy.selectOnePage(value, true) : value
    }

    public static func save(_ value: EpubReaderPreferences, to defaults: UserDefaults) {
        defaults.set(value.theme.rawValue, forKey: prefix + "theme")
        defaults.set(value.fontFamily, forKey: prefix + "fontFamily")
        defaults.set(value.fontScale, forKey: prefix + "fontScale")
        defaults.set(value.lineHeight, forKey: prefix + "lineHeight")
        defaults.set(value.pageMargins, forKey: prefix + "pageMargins")
        defaults.set(value.columns.rawValue, forKey: prefix + "columns")
        defaults.set(value.publisherStyles, forKey: prefix + "publisherStyles")
        defaults.set(value.scroll, forKey: prefix + "scroll")
        defaults.set(value.textAlignment, forKey: prefix + "textAlignment")
        defaults.set(value.onePagePerScreen, forKey: prefix + "onePagePerScreen")
    }
}

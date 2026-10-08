import Foundation

// How a book looks (#25, phase 4): Android's `reader/EpubReaderState.kt`,
// `EpubAppearanceStore` and `EpubPagePalette` (#18), held to their test cases
// in `BookReaderRulesTests`. One appearance for every book, applied live and
// kept with each change.

public enum EpubTheme: String, CaseIterable, Sendable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case sepia = "SEPIA"
    /// The grey page, shown as Dim (#47): its stored name from before Dark existed.
    case dark = "DARK"
    /// A true-black page, shown as Dark (#47), Kindle's.
    case black = "BLACK"
    case blue = "BLUE"
}

public enum EpubColumns: String, CaseIterable, Sendable {
    case auto = "AUTO"
    case one = "ONE"
    case two = "TWO"
}

public struct EpubReaderPreferences: Equatable, Sendable {
    public var theme: EpubTheme
    /// A typeface's id (`EpubTypefaces`): "publisher" is the book's own.
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
    /// Words broken at the line's end, so justified lines keep even spaces.
    public var hyphens: Bool

    /// The text size a new device starts at (#47): Literata at 130% reads as
    /// the owner's Kindle does on a tablet; a phone's smaller screen at 120%.
    public static let tabletScale = 1.3
    public static let phoneScale = 1.2

    /// The reader's own typography to begin with (#42, from the owner's Kindle
    /// screenshots, #47): Literata, justified, hyphenated, a line and a half
    /// apart, the book's own styling off. Publisher styling brings the book's
    /// look back.
    public init(theme: EpubTheme = .sepia, fontFamily: String = EpubTypefaces.standard, fontScale: Double = tabletScale,
                lineHeight: Double = 1.5, pageMargins: Double = 1, columns: EpubColumns = .auto, scroll: Bool = false,
                publisherStyles: Bool = false, textAlignment: String = "justify", onePagePerScreen: Bool = false,
                hyphens: Bool = true) {
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
        self.hyphens = hyphens
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
        // Kindle's Sepia and Dark, measured (#47); Dim is the old grey with softer words.
        case .sepia: (0xFFFC_F0D9, 0xFF5A_4931)
        case .dark: (0xFF20_2020, 0xFFC8_C8C2)
        case .black: (0xFF00_0000, 0xFFAF_AFAF)
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
    /// "light", "sepia" or "dark" (Readium's own three).
    public var theme: String
    public var background: String
    public var text: String
    public let columns: EpubColumns
    /// The face's CSS family name ("Literata", "Charter"), or nil for the book's own.
    public let fontFamily: String?
    public let fontSize: Double
    public let lineHeight: Double
    public let pageMargins: Double
    public let publisherStyles: Bool
    public let scroll: Bool
    /// "start" or "justify".
    public let textAlign: String
    public let hyphens: Bool

    /// System colours follow the device: Paper by day, Dark after dark (#47).
    public init(_ value: EpubReaderPreferences, systemDark: Bool) {
        let theme: EpubTheme = value.theme == .system ? (systemDark ? .black : .light) : value.theme
        self.theme = switch theme {
        case .light, .system: "light"
        case .sepia: "sepia"
        case .dark, .black, .blue: "dark"
        }
        let palette = EpubPagePalette.of(theme) ?? EpubPagePalette.of(.light)!
        background = EpubPagePalette.hex(palette.page)
        text = EpubPagePalette.hex(palette.ink)
        columns = value.onePagePerScreen ? .one : value.columns
        fontFamily = EpubTypefaces.family(value.fontFamily)
        fontSize = value.fontScale
        lineHeight = value.lineHeight
        pageMargins = value.pageMargins
        publisherStyles = value.publisherStyles
        scroll = value.scrolls
        textAlign = value.textAlignment == "justify" ? "justify" : "start"
        hyphens = value.hyphens
    }
}

/// The Appearance sheet's choices (Android's `EpubAppearancePanel`): what
/// each tile and row sets, so the sheet only draws them.
public enum EpubAppearance {
    public static let themes: [(theme: EpubTheme, label: String)] = [
        (.light, "Paper"), (.sepia, "Sepia"), (.dark, "Dim"), (.black, "Dark"), (.blue, "Blue"),
    ]
    /// Each is a width of text: `EpubGeometry.outerMargin` says how many points.
    public static let margins: [(amount: Double, label: String)] = [(0.5, "Narrow"), (1, "Balanced"), (1.7, "Wide")]
    /// Kindle's three (#47); 1.5 is the default and, with Literata, Kindle's own spacing.
    public static let spacing: [(amount: Double, label: String)] = [(1.3, "Tight"), (1.5, "Relaxed"), (1.8, "Open")]
    public static let fontScales: ClosedRange<Double> = 0.7...2
    public static let fontStep = 0.1

    /// "Original" leaves the book's own styles on; another face takes them off.
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

    /// Reset text style (#42, #47): the reader's own typography as a new
    /// device starts with it (Literata, justified, hyphenated, its line
    /// spacing, the book's styling off), for a look kept from before. The
    /// size, theme, margins and columns stay as they are, and Page info is not
    /// a preference.
    public static func resetTextStyle(_ value: EpubReaderPreferences) -> EpubReaderPreferences {
        let start = EpubReaderPreferences()
        var next = value
        next.fontFamily = start.fontFamily
        next.publisherStyles = start.publisherStyles
        next.textAlignment = start.textAlignment
        next.hyphens = start.hyphens
        next.lineHeight = start.lineHeight
        return next
    }

    /// What Reset text style sets, from the defaults: "Literata, justified, hyphenated, 1.5 spacing".
    public static var resetTextStyleDetail: String {
        let start = EpubReaderPreferences()
        var parts = [EpubTypefaces.label(start.fontFamily), start.textAlignment == "justify" ? "justified" : "aligned left"]
        if start.hyphens { parts.append("hyphenated") }
        parts.append(String(format: "%g spacing", start.lineHeight))
        return parts.joined(separator: ", ")
    }

    /// Hyphenation on or off; like justified text, it is the reader's own typography.
    public static func hyphenated(_ value: EpubReaderPreferences) -> EpubReaderPreferences {
        var next = value
        next.hyphens.toggle()
        next.publisherStyles = false
        return next
    }

    /// Whether `amount` is the one chosen: margins and spacing are kept as numbers.
    public static func same(_ left: Double, _ right: Double) -> Bool { abs(left - right) < 0.01 }
}

/// Shared by every book, kept on this device under Android's names.
public enum EpubAppearanceStore {
    static let prefix = "epub."
    /// Set once the Kindle look (#47) has been brought to a device.
    static let kindleKey = prefix + "kindle"

    /// What a device that kept a look from before gets once, as the update
    /// brings the reader's new look (#47):
    /// - Literata, with the book's own styling off, as the typeface (the owner
    ///   asked for every device to switch);
    /// - Dark for a device that had Comfort's black page, which is gone;
    /// - Kindle's two nearer spacings for the two it dropped (1.1 and 1.9).
    /// The rest it kept stays as it was. A device that never changed its look
    /// has nothing stored and starts with the new defaults.
    public static func migrate(_ defaults: UserDefaults) {
        guard defaults.object(forKey: kindleKey) == nil else { return }
        defaults.set(true, forKey: kindleKey)
        if ComfortStore.takeBlackPage(defaults) { defaults.set(EpubTheme.black.rawValue, forKey: prefix + "theme") }
        guard defaults.object(forKey: prefix + "fontFamily") != nil else { return }
        defaults.set(EpubTypefaces.standard, forKey: prefix + "fontFamily")
        defaults.set(false, forKey: prefix + "publisherStyles")
        if defaults.object(forKey: prefix + "lineHeight") != nil {
            let spacing = defaults.double(forKey: prefix + "lineHeight")
            if abs(spacing - 1.1) < 0.01 { defaults.set(1.3, forKey: prefix + "lineHeight") }
            if abs(spacing - 1.9) < 0.01 { defaults.set(1.8, forKey: prefix + "lineHeight") }
        }
    }

    /// `startingScale` is where a device that never chose a size begins: a tablet's 130%, a phone's 120%.
    public static func load(_ defaults: UserDefaults, startingScale: Double = EpubReaderPreferences.tabletScale)
        -> EpubReaderPreferences {
        migrate(defaults)
        func string(_ key: String) -> String? { defaults.string(forKey: prefix + key) }
        func number(_ key: String, _ fallback: Double, _ range: ClosedRange<Double>) -> Double {
            guard defaults.object(forKey: prefix + key) != nil else { return fallback }
            let value = defaults.double(forKey: prefix + key)
            return value.isNaN ? fallback : min(max(value, range.lowerBound), range.upperBound)
        }
        func flag(_ key: String, _ fallback: Bool) -> Bool {
            defaults.object(forKey: prefix + key) == nil ? fallback : defaults.bool(forKey: prefix + key)
        }
        // A look kept before (#42) keeps what it had: the defaults are for a
        // device that never changed it, and such a look had no hyphenation.
        let kept = defaults.object(forKey: prefix + "publisherStyles") != nil
        let start = EpubReaderPreferences(fontScale: startingScale)
        let value = EpubReaderPreferences(
            theme: string("theme").flatMap(EpubTheme.init(rawValue:)) ?? start.theme,
            fontFamily: string("fontFamily") ?? start.fontFamily,
            fontScale: number("fontScale", start.fontScale, 0.7...2),
            lineHeight: number("lineHeight", start.lineHeight, 1...2),
            pageMargins: number("pageMargins", start.pageMargins, 0.5...2),
            columns: string("columns").flatMap(EpubColumns.init(rawValue:)) ?? start.columns,
            scroll: flag("scroll", start.scroll),
            publisherStyles: flag("publisherStyles", start.publisherStyles),
            textAlignment: string("textAlignment") ?? start.textAlignment,
            onePagePerScreen: flag("onePagePerScreen", start.onePagePerScreen),
            hyphens: flag("hyphens", kept ? false : start.hyphens))
        return value.onePagePerScreen ? EpubLayoutPolicy.selectOnePage(value, true) : value
    }

    public static func save(_ value: EpubReaderPreferences, to defaults: UserDefaults) {
        // What is saved is the new look's: it is not brought forward again.
        defaults.set(true, forKey: kindleKey)
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
        defaults.set(value.hyphens, forKey: prefix + "hyphens")
    }
}

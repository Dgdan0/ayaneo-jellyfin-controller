import Foundation

/// The two sides of the app: Movies & TV, and Books. Android's `ContentMode`,
/// under another name because SwiftUI has a `ContentMode` of its own.
public enum AppSide: String, CaseIterable, Sendable {
    case media, books

    public var label: String {
        switch self {
        case .media: "Media"
        case .books: "Books"
        }
    }
}

/// The accents a person can pick, as Android's `settings/AccentPreset`: the
/// same ids and the same colours, so both apps tint alike. Glass is always
/// dark, so only the dark-theme pastels are here. The accent colours progress,
/// eyebrows, UP NEXT and the chosen Media/Books segment, never a whole panel.
public enum AccentPreset: String, CaseIterable, Sendable {
    case teal, mint, sky, lavender, lilac, rose, peach, butter, gold, sage

    public var label: String { rawValue.prefix(1).uppercased() + rawValue.dropFirst() }

    public var color: UInt32 {
        switch self {
        case .teal: 0xFF3D_DBC6
        case .mint: 0xFF8E_E3CF
        case .sky: 0xFFA5_C8FF
        case .lavender: 0xFFC7_B8FF
        case .lilac: 0xFFE3_B8F5
        case .rose: 0xFFFF_B4C6
        case .peach: 0xFFFF_C6A5
        case .butter: 0xFFF6_E3A1
        case .gold: 0xFFE9_C46A
        case .sage: 0xFFBF_D8B0
        }
    }

    /// Text and icons drawn on the accent: a deep shade of it, a fifth of each
    /// channel, truncated as Android's `ink(dark = true)` does.
    public var ink: UInt32 {
        func channel(_ shift: UInt32) -> UInt32 { UInt32(Double((color >> shift) & 0xFF) * 0.2) }
        return 0xFF00_0000 | channel(16) << 16 | channel(8) << 8 | channel(0)
    }

    /// Books are gold until chosen otherwise, so reading reads as its own space.
    public static func defaultFor(_ mode: AppSide) -> AccentPreset {
        mode == .books ? .gold : .teal
    }
}

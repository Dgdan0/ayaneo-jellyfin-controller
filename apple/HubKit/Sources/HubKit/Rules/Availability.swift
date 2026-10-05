import Foundation

/// What a title's availability chip says, and in which colour (CLAUDE.md,
/// "Badges and card geometry"): one owner for Discover's rows, search, a
/// filmography and a title's page.
public enum Availability {
    public enum Tone: Equatable, Sendable {
        /// Green: you have it.
        case available
        /// Ultra Violet: some of it. Not green, because half a series is not "you have this".
        case partial
        /// Amber: asked for, on its way, or downloading.
        case pending
        /// Red: blocked or deleted.
        case failed
    }

    /// The chip's words, or nil for a title with no chip: not in the library,
    /// or not known (a filmography's credits carry no availability, and a wrong
    /// green chip is worse than none).
    public static func label(_ state: String) -> String? {
        switch state {
        case "available": "In library"
        case "partially_available": "Partial"
        case "requested": "Requested"
        // "Processing" described the software, not the thing asked for.
        case "processing": "On the way"
        case "downloading": "Downloading"
        case "blocked": "Blocked"
        case "deleted": "Deleted"
        default: nil
        }
    }

    public static func tone(_ state: String) -> Tone? {
        switch state {
        case "available": .available
        case "partially_available": .partial
        case "requested", "processing", "downloading": .pending
        case "blocked", "deleted": .failed
        default: nil
        }
    }

    /// Whether the title, or some of it, is in the Jellyfin library.
    public static func inLibrary(_ state: String) -> Bool {
        state == "available" || state == "partially_available"
    }

    /// The chip's fill on artwork, 0xAARRGGBB: the Pocket's Glass chip, the
    /// dark palette's badge colours at 94%, so both apps' chips match.
    public static func fill(_ tone: Tone) -> UInt32 {
        switch tone {
        case .available: 0xF03E_CB80
        case .partial: 0xF07B_34C4
        case .pending: 0xF0E0_AE4A
        case .failed: 0xF0E0_685C
        }
    }

    /// The words on the chip: near-black on the light fills, white on violet.
    public static func ink(_ tone: Tone) -> UInt32 {
        tone == .partial ? 0xFFFF_FFFF : 0xFF11_1116
    }
}

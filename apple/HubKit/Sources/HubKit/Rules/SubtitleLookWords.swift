import Foundation

/// What Settings › Subtitles and the player's look sheet say about how subtitles
/// look (#38): one set of words, so a choice reads the same wherever it is
/// made, and both write the same stored look (`SubtitleLook`).
public enum SubtitleLookWords {
    public static func style(_ style: SubtitleStyle) -> String { PlayerLabels.subtitleStyle(style) }

    /// The short line under a style's name, in the player's sheet and in Settings.
    public static func styleDetail(_ style: SubtitleStyle) -> String {
        switch style {
        case .outline: "White words with a black edge"
        case .box: "White words on a dark box"
        }
    }

    /// Settings' longer hint under the style choice.
    public static func styleHint(_ style: SubtitleStyle) -> String {
        switch style {
        case .outline: "White text with a black edge and nothing behind it, the way a video player draws subtitles. Clean over most scenes."
        case .box: "White text on a dark box. Easiest to read over snow and bright skies."
        }
    }

    public static func size(_ size: SubtitleSize) -> String { PlayerLabels.subtitleSize(size) }

    public static let liftTitle = "Above the controls"
    public static let liftDetail = "Moves up while the timeline shows"

    /// The sample line and picture the Settings preview draws.
    public static let previewLine = "I've always been able to see ghosts."
    public static let previewTitle = "BLEACH"

    /// How tall the controls are over the preview, as a share of its height
    /// (Android's 64 of 176).
    public static let previewControlsShare = 64.0 / 176.0

    /// "Show controls" / "Hide controls": the preview's own toggle.
    public static func controlsToggle(shown: Bool) -> String { shown ? "Hide controls" : "Show controls" }

    /// Where the preview's words sit, as a share of its height, with or
    /// without its controls drawn over the foot of the picture.
    public static func previewPlacement(_ look: SubtitleLook, controlsShown: Bool) -> Double {
        PlaybackEnhancements.subtitlePlacement(look, covered: controlsShown ? previewControlsShare : 0, height: 1)
    }

    /// How large the words are drawn on a screen whose short side is
    /// `shortSide`: the player's, and the preview's at the real size.
    public static func fontSize(_ look: SubtitleLook, shortSide: Double) -> Double {
        max(12, shortSide * look.size.textFraction)
    }
}

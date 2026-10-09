#if os(iOS)
import HubKit
import SwiftUI

/// The read-along highlight's page in Appearance (#66): its parts. What each
/// choice makes is HubKit's (`ReadAlongHighlightStyle`); these draw it.
enum HighlightLabels {
    /// "None", "40%", "As strong as the word".
    static func trail(_ percent: Int) -> String {
        switch percent {
        case ...0: "None"
        case 100...: "As strong as the word"
        default: "\(percent)%"
        }
    }

    static func theme(_ theme: EpubTheme) -> String {
        EpubAppearance.themes.first { $0.theme == theme }?.label ?? "Paper"
    }
}

/// A page colour as a tab: its page with the word's wash on it, and its name.
struct HighlightThemeTab: View {
    let theme: EpubTheme
    let style: ReadAlongHighlightStyle
    let selected: Bool
    let action: () -> Void

    var body: some View {
        let palette = EpubPagePalette.of(theme) ?? (0xFFFB_FAF6, 0xFF28_2B29)
        Button(action: action) {
            HStack(spacing: 8) {
                ZStack {
                    Circle().fill(Color(argb: palette.page))
                    Circle().fill(Color(argb: style.word(theme, page: palette.page, ink: palette.ink))).padding(5)
                }
                .frame(width: 22, height: 22)
                .overlay(Circle().stroke(.white.opacity(0.35), lineWidth: 1))
                Text(HighlightLabels.theme(theme)).font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
            }
            .padding(.leading, 9)
            .padding(.trailing, 15)
            .padding(.vertical, 7)
            .foregroundStyle(selected ? Color.glassInk : .white)
            .background(selected ? AnyShapeStyle(.white) : AnyShapeStyle(.white.opacity(0.1)), in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(HighlightLabels.theme(theme)), \(style.colour.label)")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("highlight-theme-\(theme.rawValue)")
    }
}

/// A sentence read along on `theme`'s page, style A: the words already read
/// in the trail, the word being read in the word's wash, the rest as it is.
struct HighlightPreview: View {
    let theme: EpubTheme
    let style: ReadAlongHighlightStyle

    static let words = ["The", "lake", "was", "flat", "and", "black,", "and", "the", "city", "leaned", "over", "it", "like", "a", "reader."]
    /// The word being read.
    static let reading = 8

    var body: some View {
        let palette = EpubPagePalette.of(theme) ?? (0xFFFB_FAF6, 0xFF28_2B29)
        let word = Color(argb: style.word(theme, page: palette.page, ink: palette.ink))
        let trail = style.trail(theme, page: palette.page, ink: palette.ink).map { Color(argb: $0) }
        Text(sentence(word: word, trail: trail))
            .font(.system(size: 19, design: .serif))
            .foregroundStyle(Color(argb: palette.ink))
            .lineSpacing(6)
            .padding(.horizontal, 18)
            .padding(.vertical, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(argb: palette.page), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("A sentence read along: \(style.colour.label), trail \(HighlightLabels.trail(style.trail))")
            .accessibilityIdentifier("highlight-preview")
    }

    private func sentence(word: Color, trail: Color?) -> AttributedString {
        var out = AttributedString()
        for (index, text) in Self.words.enumerated() {
            var piece = AttributedString(text)
            if index == Self.reading {
                piece.backgroundColor = word
            } else if index < Self.reading, let trail {
                piece.backgroundColor = trail
            }
            out += piece
            guard index < Self.words.count - 1 else { break }
            var space = AttributedString(" ")
            // The trail runs on through the spaces between its words, up to the word read.
            if index < Self.reading, let trail { space.backgroundColor = trail }
            out += space
        }
        return out
    }
}

/// One of the eight colours, its ring when chosen, "Default" under the page's own.
struct HighlightSwatch: View {
    let colour: ReadAlongHighlightStyle.Colour
    let theme: EpubTheme
    let selected: Bool
    let standard: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Circle()
                    .fill(Color(argb: colour.rgb))
                    .frame(width: 30, height: 30)
                    .padding(3)
                    .overlay(Circle().stroke(.white, lineWidth: selected ? 2.5 : 0))
                Text(standard ? "Default" : " ")
                    .font(HubType.body(10.5, weight: .semibold, relativeTo: .caption2))
                    .foregroundStyle(.white.opacity(0.7))
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(colour.label + (standard ? ", default" : ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("highlight-colour-\(colour.rawValue)")
    }
}

/// The trail, 0% (none: the word alone) to 100% (as strong as the word), in fives.
struct HighlightTrailSlider: View {
    let trail: Int
    let choose: (Int) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Slider(value: Binding(get: { Double(trail) }, set: { choose(ReadAlongHighlightStyle.stepped(Int($0.rounded()))) }),
                   in: 0...100, step: Double(ReadAlongHighlightStyle.trailStep))
                .tint(.white)
                .accessibilityLabel("Trail")
                .accessibilityValue(HighlightLabels.trail(trail))
                .accessibilityIdentifier("highlight-trail")
            HStack {
                Text("None")
                Spacer()
                Text(HighlightLabels.trail(trail)).foregroundStyle(.white)
                Spacer()
                Text("As strong as the word")
            }
            .font(HubType.body(12, relativeTo: .caption))
            .foregroundStyle(.white.opacity(0.62))
            .accessibilityHidden(true)
        }
        .padding(.horizontal, 4)
    }
}
#endif

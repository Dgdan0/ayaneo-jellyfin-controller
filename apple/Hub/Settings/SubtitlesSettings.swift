import HubKit
import SwiftUI

extension EnvironmentValues {
    /// The window Settings is in, whose short side stands for the screen the
    /// player will fill: Settings › Subtitles draws its words at that size.
    @Entry var settingsWindow = CGSize(width: 390, height: 844)
}

/// Settings › Subtitles (#38; Android's `SettingsScreen.subtitles`): how
/// subtitles look in every video, with a picture to try it on. The preview
/// draws the words with the player's own `SubtitleLine`, at the size and place
/// the player would, with or without its controls over the foot of the
/// picture. The style, size and lift are the player's look sheet's too: both
/// read and write `PlaybackMemory.look`, in `SubtitleLookWords`.
struct SubtitlesSettings: View {
    @Environment(\.settingsWindow) private var window
    @Environment(\.glassAccent) private var accent

    @State private var look = PlaybackMemory.look()
    @State private var controls = true

    static let previewHeight: CGFloat = 176

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            preview
            card("Look", detail: SubtitleLookWords.styleHint(look.style)) {
                HStack(spacing: 8) {
                    ForEach(SubtitleStyle.allCases, id: \.self) { style in
                        ChoicePill(title: SubtitleLookWords.style(style), selected: look.style == style,
                                   pad: "style-\(style.rawValue)") {
                            change { $0.style = style }
                        }
                        .accessibilityIdentifier("style-\(style.rawValue)")
                    }
                }
            }
            card("Size") {
                HStack(spacing: 8) {
                    ForEach(SubtitleSize.allCases, id: \.self) { size in
                        ChoicePill(title: SubtitleLookWords.size(size), selected: look.size == size,
                                   pad: "size-\(size.rawValue)") {
                            change { $0.size = size }
                        }
                        .accessibilityIdentifier("size-\(size.rawValue)")
                    }
                }
            }
            Toggle(isOn: Binding(get: { look.liftWithControls }, set: { lifted in change { $0.liftWithControls = lifted } })) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(SubtitleLookWords.liftTitle)
                        .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                        .foregroundStyle(.white)
                    Text("On: lines jump above the timeline so it never covers them. Off: they stay put.")
                        .font(HubType.body(13.5, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.64))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(accent.tint)
            .padding(.horizontal, 18)
            .padding(.vertical, 14)
            .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .accessibilityIdentifier("subtitle-lift")
            .padFocusableBehind("subtitle-lift", ring: .rounded(20)) { change { $0.liftWithControls.toggle() } }
        }
    }

    private func change(_ edit: (inout SubtitleLook) -> Void) {
        edit(&look)
        PlaybackMemory.save(look)
    }

    // MARK: The preview

    private var preview: some View {
        let height = Self.previewHeight
        let size = SubtitleLookWords.fontSize(look, shortSide: Double(min(window.width, window.height)))
        let share = SubtitleLookWords.previewPlacement(look, controlsShown: controls)
        return ZStack(alignment: .bottom) {
            LinearGradient(colors: [Color(red: 64 / 255, green: 26 / 255, blue: 28 / 255), Color(red: 24 / 255, green: 34 / 255, blue: 52 / 255)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            Text(SubtitleLookWords.previewTitle)
                .font(HubType.heading(64, weight: .heavy, relativeTo: .largeTitle))
                .foregroundStyle(.white.opacity(0.08))
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            if controls {
                ZStack(alignment: .bottomLeading) {
                    LinearGradient(colors: [.clear, .black.opacity(0.86)], startPoint: .top, endPoint: .bottom)
                    Capsule().fill(.white.opacity(0.28)).frame(height: 4).padding(.horizontal, 14).padding(.bottom, 16)
                    Capsule().fill(accent.tint).frame(width: 150, height: 4).padding(.leading, 14).padding(.bottom, 16)
                }
                .frame(height: height * SubtitleLookWords.previewControlsShare)
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
            SubtitleLine(text: SubtitleLookWords.previewLine, style: look.style, fontSize: CGFloat(size))
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 12)
                .padding(.bottom, height * share)
                .frame(maxHeight: .infinity, alignment: .bottom)
                .accessibilityLabel("Preview: \(SubtitleLookWords.previewLine)")
                .accessibilityIdentifier("subtitle-preview")
        }
        .frame(height: height)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(alignment: .topTrailing) {
            Button {
                controls.toggle()
            } label: {
                Text(SubtitleLookWords.controlsToggle(shown: controls))
                    .font(HubType.body(12, weight: .semibold, relativeTo: .caption))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 11)
                    .frame(minHeight: 30)
                    .background(.black.opacity(0.5), in: Capsule())
                    .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .padFocusable("preview-controls", ring: .capsule) { controls.toggle() }
            .padding(8)
            .accessibilityIdentifier("preview-controls")
        }
        // The overlay's label belongs to the picture, not to the whole of it.
        .accessibilityElement(children: .contain)
    }

    private func card<Content: View>(_ title: String, detail: String = "", @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                .foregroundStyle(.white)
            content()
            if !detail.isEmpty {
                Text(detail)
                    .font(HubType.body(13.5, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.64))
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("subtitle-hint")
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

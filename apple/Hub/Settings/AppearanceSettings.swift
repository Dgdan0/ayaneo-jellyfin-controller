import HubKit
import SwiftUI

/// Settings › Appearance (#38; Android's `SettingsScreen.palette`): one accent
/// for Movies and TV and one for Books, from the ten presets. The colour is the
/// Play button, the progress bars, the eyebrows and the chosen side in the bar;
/// it never fills a whole panel. A sample under the swatches shows it, and the
/// whole app takes it at once.
struct AppearanceSettings: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            AccentCard(side: .media)
            AccentCard(side: .books)
        }
    }
}

/// One side's accent: its name and the colour chosen, what the colour is for,
/// the swatches, and a Play button and a bar drawn in it.
struct AccentCard: View {
    let side: AppSide
    @Environment(AccentModel.self) private var accents

    var body: some View {
        let chosen = accents.accent(side)
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(AccentSettings.title(for: side))
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                Text(chosen.label)
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
                    .accessibilityIdentifier("accent-name-\(side.rawValue)")
            }
            Text(AccentSettings.hint(for: side))
                .font(HubType.body(13.5, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.64))
                .fixedSize(horizontal: false, vertical: true)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 44, maximum: 52), spacing: 10)], alignment: .leading, spacing: 10) {
                ForEach(AccentPreset.allCases, id: \.self) { preset in
                    swatch(preset, chosen: chosen)
                }
            }
            HStack(spacing: 12) {
                Text(AccentSettings.sample(for: side))
                    .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(chosen.inkColor)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                    .background(chosen.tint, in: Capsule())
                    .accessibilityHidden(true)
                Capsule().fill(.white.opacity(0.14))
                    .frame(width: 120, height: 5)
                    .overlay(alignment: .leading) { Capsule().fill(chosen.tint).frame(width: 66, height: 5) }
                    .accessibilityHidden(true)
            }
            .padding(.top, 2)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("accent-card-\(side.rawValue)")
    }

    private func swatch(_ preset: AccentPreset, chosen: AccentPreset) -> some View {
        let on = preset == chosen
        return Button {
            accents.choose(preset, for: side)
        } label: {
            ZStack {
                Circle().fill(preset.tint)
                if on {
                    Image(systemName: "checkmark")
                        .font(.system(size: 15, weight: .heavy))
                        .foregroundStyle(preset.inkColor)
                }
            }
            .frame(width: 40, height: 40)
            // The chosen one stands in a white ring, as a focused card does.
            .overlay { Circle().strokeBorder(.white, lineWidth: on ? 2.5 : 0).padding(-4) }
            .frame(width: 48, height: 48)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(preset.label)
        .accessibilityAddTraits(on ? .isSelected : [])
        .accessibilityIdentifier("swatch-\(side.rawValue)-\(preset.rawValue)")
        .padFocusable("swatch-\(side.rawValue)-\(preset.rawValue)", ring: .circle) { accents.choose(preset, for: side) }
        #if os(iOS)
        .hoverEffect(.lift)
        #endif
    }
}

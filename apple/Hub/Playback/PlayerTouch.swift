import HubKit
import SwiftUI

/// What the picture's gestures show for a moment (#24), as the Pocket's
/// player does: a double tap's seek at its side, and a brightness or volume
/// drag's level as a small bar.
struct PlayerSeekShown: Equatable {
    let text: String
    let side: PlayerGestures.Side
}

struct PlayerLevelShown: Equatable {
    enum Kind: Equatable {
        case brightness, volume

        var label: String { self == .brightness ? "Brightness" : "Volume" }
        var systemImage: String { self == .brightness ? "sun.max.fill" : "speaker.wave.2.fill" }
    }

    let kind: Kind
    let value: Double
    let side: PlayerGestures.Side
}

/// "−0:10 · 12:34" in a glass capsule, at the side that was tapped.
struct PlayerSeekBubble: View {
    let shown: PlayerSeekShown
    let inset: CGFloat

    var body: some View {
        Text(shown.text)
            .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
            .monospacedDigit()
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .glassPanel(Capsule())
            .padding(.horizontal, inset)
            .frame(maxWidth: .infinity, maxHeight: .infinity,
                   alignment: shown.side == .left ? .leading : .trailing)
            .allowsHitTesting(false)
            .accessibilityAddTraits(.updatesFrequently)
    }
}

/// The level a drag sets: its percent and name over a short upright bar in
/// the accent, at the side being dragged (Android's `PlayerLevelView`).
struct PlayerLevelBar: View {
    let shown: PlayerLevelShown
    let inset: CGFloat
    @Environment(\.glassAccent) private var accent

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: shown.kind.systemImage)
                .font(.system(size: 15, weight: .semibold))
            Text(PlayerGestures.percent(shown.value))
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                .monospacedDigit()
            GeometryReader { bar in
                ZStack(alignment: .bottom) {
                    Capsule().fill(.white.opacity(0.35))
                    Capsule()
                        .fill(accent.tint)
                        .frame(height: max(8, bar.size.height * shown.value))
                }
                .frame(width: 8)
                .frame(maxWidth: .infinity)
            }
            .frame(height: 120)
        }
        .padding(.vertical, 14)
        .frame(width: 64)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .padding(.horizontal, inset)
        .frame(maxWidth: .infinity, maxHeight: .infinity,
               alignment: shown.side == .left ? .leading : .trailing)
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(shown.kind.label), \(PlayerGestures.percent(shown.value))")
        .accessibilityIdentifier("player-level")
    }
}

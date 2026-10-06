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

/// Where a scrub lands (#24): from a drag across the picture or along the
/// timeline. `deltaMillis` is how far the drag across moved it, nil from the
/// timeline; `share` is the point along the timeline it lands on.
struct PlayerScrubShown: Equatable {
    let targetMillis: Int64
    let deltaMillis: Int64?
    let share: Double
}

/// The scrub's preview, as the Pocket's: the hub's frame there, the time it
/// lands on and, from a drag across the picture, how far that is in the
/// accent ("+1:20"). Until the first frame comes it is the time alone, and
/// each frame stays until the next has come, so a drag never flickers to
/// an empty box.
struct PlayerScrubPreview: View {
    let shown: PlayerScrubShown
    /// The session's frame route (`previewUrl`); empty when it has none.
    let previewUrl: String
    let compact: Bool
    @Environment(AppModel.self) private var model
    @Environment(\.glassAccent) private var accent
    @State private var frame: DecodedArtwork?

    static func width(compact: Bool) -> CGFloat { compact ? 156 : 188 }

    private var bucket: Int64 { PlaybackEnhancements.frameMillis(shown.targetMillis) }

    var body: some View {
        let width = Self.width(compact: compact)
        VStack(spacing: 7) {
            if let frame {
                Image(decorative: frame.image, scale: 1)
                    .resizable()
                    .scaledToFill()
                    .frame(width: width - 20, height: (width - 20) * 9 / 16)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .transition(.opacity)
            }
            HStack(spacing: 10) {
                Text(Fmt.clock(shown.targetMillis))
                    .font(HubType.body(compact ? 16 : 17, weight: .bold, relativeTo: .headline))
                if let delta = shown.deltaMillis {
                    Text(PlayerLabels.signedTime(delta))
                        .font(HubType.body(13, weight: .semibold, relativeTo: .subheadline))
                        .foregroundStyle(accent.tint)
                }
            }
            .monospacedDigit()
            .lineLimit(1)
        }
        .padding(10)
        .frame(width: width)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(PlayerGestures.scrubLabel(targetMillis: shown.targetMillis, deltaMillis: shown.deltaMillis))
        .accessibilityIdentifier("player-scrub")
        .accessibilityAddTraits(.updatesFrequently)
        .task(id: bucket) { await load() }
    }

    private func load() async {
        guard !previewUrl.isEmpty else { return }
        // Asked for once the drag rests a moment (Android's 180 ms): a drag
        // crosses many of the hub's five-second frames, and it extracts each
        // one it is asked for.
        try? await Task.sleep(for: .milliseconds(180))
        guard !Task.isCancelled else { return }
        let path = HubEndpoints.playbackPreview(previewUrl, positionMillis: bucket)
        // A frame that cannot be had leaves the last one there.
        guard let loaded = await loadArtwork(model.hub, request: path, maxPixels: 360), !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.15)) { frame = loaded }
    }
}

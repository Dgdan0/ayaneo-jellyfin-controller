import HubKit
import SwiftUI

// The pieces of the player's Glass chrome, at the prototype's sizes: `.pl-chip`,
// `.pl-skip`, `.pl-big`, `.pl-line` and Android's `UpNextCardView`.

/// A glass pill in the player's top bar (`.pl-chip`): Audio & subtitles,
/// Chapters, This video.
struct PlayerChip: View {
    let systemImage: String?
    let title: String
    var compact = false
    var action: () -> Void = {}

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: compact ? 14 : 15, weight: .semibold))
                }
                Text(title)
            }
            .font(HubType.chrome(compact ? 12.5 : 13.5, weight: .semibold))
            .lineLimit(1)
            .padding(.horizontal, compact ? 12 : 14)
            .padding(.vertical, compact ? 8 : 9)
            .glassPanel(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// A round glass step either side of Play (`.pl-skip`): an episode back or
/// on, or "−10" and "+10" in words.
struct PlayerSkipButton: View {
    let systemImage: String?
    let text: String?
    let label: String
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Group {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: size * 0.36, weight: .semibold))
                } else if let text {
                    Text(text).font(HubType.chrome(size * 0.23, weight: .bold))
                }
            }
            .frame(width: size, height: size)
            .glassPanel(Circle())
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// The white disc in the middle (`.pl-big`): Play or Pause in dark ink, a
/// spinner while the picture waits for the network.
struct PlayerPlayDisc: View {
    let playing: Bool
    let buffering: Bool
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(.white)
                if buffering {
                    ProgressView().tint(Color.glassInk)
                } else {
                    Image(systemName: playing ? "pause.fill" : "play.fill")
                        .font(.system(size: size * 0.4, weight: .bold))
                        .foregroundStyle(Color.glassInk)
                        // The triangle's weight sits left of its box.
                        .offset(x: playing ? 0 : size * 0.03)
                }
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(playing ? "Pause" : "Play")
        #if os(iOS)
        .hoverEffect(.lift)
        #endif
    }
}

/// The timeline (`.pl-line`): what has loaded in faint white, what has played
/// in white, and a thumb with a soft ring. Dragging moves only the thumb and
/// the time under it; letting go seeks there, so a stream is not asked for
/// every position on the way.
struct PlayerTimeline: View {
    let positionMillis: Int64
    let durationMillis: Int64
    let bufferedMillis: Int64
    @Binding var scrub: Double?
    let seek: (Int64) -> Void
    /// VoiceOver's swipe up and down: ten seconds either way.
    let adjust: (Int64) -> Void

    var body: some View {
        GeometryReader { geometry in
            let width = max(1, geometry.size.width)
            let played = scrub ?? share(positionMillis)
            let loaded = max(played, share(bufferedMillis))
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.22)).frame(height: 6)
                Capsule().fill(.white.opacity(0.3)).frame(width: width * loaded, height: 6)
                Capsule().fill(.white).frame(width: width * played, height: 6)
                Circle()
                    .fill(.white)
                    .frame(width: 16, height: 16)
                    .background { Circle().fill(.white.opacity(0.25)).padding(-4) }
                    .offset(x: width * played - 8)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { value in
                    guard durationMillis > 0 else { return }
                    scrub = min(max(value.location.x / width, 0), 1)
                }
                .onEnded { value in
                    guard durationMillis > 0 else { return }
                    let share = min(max(value.location.x / width, 0), 1)
                    seek(Int64(share * Double(durationMillis)))
                    scrub = nil
                })
        }
        .frame(height: 22)
        .accessibilityElement()
        .accessibilityLabel("Position")
        .accessibilityValue("\(Fmt.clock(positionMillis)) of \(Fmt.clock(durationMillis))")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: adjust(10_000)
            case .decrement: adjust(-10_000)
            @unknown default: break
            }
        }
    }

    private func share(_ millis: Int64) -> Double {
        durationMillis > 0 ? min(max(Double(millis) / Double(durationMillis), 0), 1) : 0
    }
}

/// Near the end of an episode (Android `UpNextCardView`): its still, "UP NEXT ·
/// S1E6", its name and series, a bar that fills until it starts by itself, and
/// Play now or Watch credits.
struct UpNextCardView: View {
    let card: UpNextCard
    var compact = false
    let playNow: () -> Void
    let watchCredits: () -> Void
    @Environment(\.glassAccent) private var accent

    private var kicker: String {
        ["UP NEXT", EpisodeLabel.code(season: card.item.seasonNumber, episode: card.item.episodeNumber)]
            .filter { !$0.isEmpty }.joined(separator: " · ")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                ArtworkView(path: "/v1/img/jf/\(card.item.id)/Primary", width: 360, placeholder: Color.white.opacity(0.08))
                    .frame(width: compact ? 112 : 128, height: compact ? 63 : 72)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 3) {
                    Text(kicker)
                        .font(HubType.body(11.5, weight: .bold, relativeTo: .caption))
                        .tracking(1.2)
                        .foregroundStyle(accent.tint)
                    Text(card.item.title.isEmpty ? card.item.displayTitle : card.item.title)
                        .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                        .lineLimit(2)
                    if !card.item.seriesTitle.isEmpty {
                        Text(card.item.seriesTitle)
                            .font(HubType.body(11.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.78))
                    }
                }
                .lineLimit(1)
            }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(.white.opacity(0.15))
                    Capsule().fill(accent.tint).frame(width: geometry.size.width * card.fraction)
                }
            }
            .frame(height: 4)
            .padding(.top, 12)
            .accessibilityHidden(true)
            Text("Starts automatically when the bar fills")
                .font(HubType.body(11.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.67))
                .padding(.top, 5)
            HStack(spacing: 8) {
                Button(action: playNow) {
                    Label("Play now", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryPillStyle())
                Button(action: watchCredits) {
                    Text("Watch credits").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassPillStyle())
            }
            .lineLimit(1)
            .padding(.top, 10)
        }
        .padding(12)
        // Over a moving picture the card is nearly solid, as on the Pocket.
        .background(Color(argb: 0xF00E_1219), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(0.1), lineWidth: 1)
        }
        .shadow(color: .black.opacity(0.45), radius: 18, y: 10)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Up next: \(card.item.displayTitle)")
    }
}

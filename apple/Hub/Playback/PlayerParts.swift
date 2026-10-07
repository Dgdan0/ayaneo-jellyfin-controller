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
/// in white, a notch where each chapter starts, and a thumb with a soft ring.
/// Dragging moves only the thumb, the time under it and the player's preview
/// of the frame there (`PlayerScrubPreview`); letting go seeks there, so a
/// stream is not asked for every position on the way.
struct PlayerTimeline: View {
    let positionMillis: Int64
    let durationMillis: Int64
    let bufferedMillis: Int64
    /// Where chapters start after the first, as shares of the length.
    var marks: [Double] = []
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
                ChapterNotches(marks: marks).fill(.black.opacity(0.55)).frame(height: 6)
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
        .accessibilityIdentifier("player-timeline")
        .accessibilityValue("\(Fmt.clock(positionMillis)) of \(Fmt.clock(durationMillis))")
        .accessibilityAdjustableAction { direction in
            switch direction {
            // VoiceOver's swipes go as far as the jump buttons (Settings › Playback).
            case .increment: adjust(Int64(ListeningSettings.seekSeconds) * 1_000)
            case .decrement: adjust(-Int64(ListeningSettings.seekSeconds) * 1_000)
            @unknown default: break
            }
        }
    }

    private func share(_ millis: Int64) -> Double {
        durationMillis > 0 ? min(max(Double(millis) / Double(durationMillis), 0), 1) : 0
    }
}

/// Near the end of an episode (Android `UpNextCardView`, in Glass): its
/// still, "UP NEXT · S1E6", its name and series, a bar that fills until it
/// starts by itself, and Play now or Watch credits.
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
            // A phone's card leaves it out: the bar says as much, and the
            // card must fit beside the middle row on a phone turned sideways.
            if !compact {
                Text("Starts automatically when the bar fills")
                    .font(HubType.body(11.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.67))
                    .padding(.top, 5)
            }
            HStack(spacing: 8) {
                Button(action: playNow) {
                    Label("Play now", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassPillStyle())
                Button(action: watchCredits) {
                    Text("Watch credits").frame(maxWidth: .infinity)
                }
                .buttonStyle(GlassPillStyle())
            }
            .lineLimit(1)
            // A phone's card is 300 points wide: the words shrink a little
            // rather than lose their end to "…".
            .minimumScaleFactor(0.8)
            .padding(.top, 10)
        }
        .padding(14)
        // Glass, as the timeline's bar under it is (the prototype's panels;
        // the Pocket's card is a dark solid one).
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Up next: \(card.item.displayTitle)")
        .accessibilityHint("Starts automatically when the bar fills")
    }
}

/// Subtitles the app draws (`PlaybackChoices.drawnSubtitle`), in the look
/// Settings chose: white words with a black edge, the way mpv draws them, or
/// white on a dark box (Android's `SubtitleLooks`).
///
/// No ForEach: the words change while the chrome fades, and SwiftUI lays out
/// some animation frames on its own thread, where a ForEach's main-actor
/// closure traps under Swift 6 (`PlayerSheet`).
///
/// Their size is a share of the screen's short side, which is its height in
/// landscape as on Android, so a phone turned upright keeps the same size of
/// words. Turned landscape they sit a share of the height up from the bottom,
/// lifted above the timeline while it shows if the look asks for that; held
/// upright, where the picture is a band across the middle, just under it.
struct SubtitleOverlay: View {
    let lines: [String]
    let look: SubtitleLook
    let size: CGSize
    /// Where the picture is drawn.
    let picture: CGRect
    /// How much of the bottom the controls cover, 0 while they are hidden.
    let covered: CGFloat

    var body: some View {
        let fontSize = max(12, min(size.width, size.height) * look.size.textFraction)
        // One text for the cue, its lines broken where the file breaks them.
        let words = lines.flatMap { $0.components(separatedBy: "\n") }.filter { !$0.isEmpty }.joined(separator: "\n")
        let text = SubtitleLine(text: words, style: look.style, fontSize: fontSize)
            .frame(maxWidth: size.width * 0.9)
        let below = size.height - picture.maxY
        Group {
            if size.height > size.width, below > fontSize * 4 {
                text
                    .padding(.top, picture.maxY + fontSize * 0.6)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            } else {
                let share = PlaybackEnhancements.subtitlePlacement(look, covered: Double(covered), height: Double(size.height))
                text
                    .padding(.bottom, size.height * share)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
            }
        }
        .allowsHitTesting(false)
        // They change several times a minute; VoiceOver would chase them.
        .accessibilityHidden(true)
    }
}

/// One line of drawn subtitles.
struct SubtitleLine: View {
    let text: String
    let style: SubtitleStyle
    let fontSize: CGFloat

    var body: some View {
        // A medium weight: a regular one thins out under the black edge.
        let words = Text(text)
            .font(.system(size: fontSize, weight: .medium))
            .multilineTextAlignment(.center)
        switch style {
        case .outline:
            // Eight black copies round the white one: written out rather than
            // a ForEach (see `SubtitleOverlay`).
            let edge = max(1.2, fontSize * 0.055)
            let corner = edge * 0.71
            ZStack {
                words.foregroundStyle(.black).offset(x: edge, y: 0)
                words.foregroundStyle(.black).offset(x: -edge, y: 0)
                words.foregroundStyle(.black).offset(x: 0, y: edge)
                words.foregroundStyle(.black).offset(x: 0, y: -edge)
                words.foregroundStyle(.black).offset(x: corner, y: corner)
                words.foregroundStyle(.black).offset(x: -corner, y: corner)
                words.foregroundStyle(.black).offset(x: corner, y: -corner)
                words.foregroundStyle(.black).offset(x: -corner, y: -corner)
                words.foregroundStyle(.white)
            }
        case .box:
            words.foregroundStyle(.white)
                .padding(.horizontal, fontSize * 0.28)
                .padding(.vertical, fontSize * 0.08)
                .background(Color.black.opacity(185.0 / 255))
        }
    }
}

/// Where the picture is drawn in a frame of `size`: fitted, letterboxed.
func letterboxed(_ picture: CGSize, in size: CGSize) -> CGRect {
    guard picture.width > 0, picture.height > 0, size.width > 0, size.height > 0 else {
        return CGRect(origin: .zero, size: size)
    }
    let scale = min(size.width / picture.width, size.height / picture.height)
    let width = picture.width * scale, height = picture.height * scale
    return CGRect(x: (size.width - width) / 2, y: (size.height - height) / 2, width: width, height: height)
}

/// The notches where chapters start, as a shape: a shape is drawn off the
/// main thread safely, where a ForEach of rectangles would not be.
struct ChapterNotches: Shape {
    let marks: [Double]

    func path(in rect: CGRect) -> Path {
        var path = Path()
        for mark in marks where mark > 0 && mark < 1 {
            path.addRect(CGRect(x: rect.minX + rect.width * mark - 1, y: rect.minY, width: 2, height: rect.height))
        }
        return path
    }
}

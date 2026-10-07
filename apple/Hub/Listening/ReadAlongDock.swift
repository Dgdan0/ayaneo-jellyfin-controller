import HubKit
import SwiftUI

/// The dock's controls, in the order a controller moves through them.
enum ReadAlongControl: CaseIterable, Hashable {
    case back, play, forward, speed, follow
}

/// The read-along player (#16, X7; Android's `ReadAlongDock`): the
/// prototype's glass dock, which is the book reader's lower bar while it
/// reads along (#21) and shows and hides with the menu. A line for how far
/// through this part of the narration; "Read along · Following" over "4:13 of
/// 27:05 · part 2 of 8"; −10, the white Play and +10 in the middle; then the
/// speed and the way back to the sentence being read. Glass tinted by the
/// cover, in the menu's corners, with the pad's ring on the control it is on.
struct ReadAlongDock: View {
    let narration: NarrationModel
    let layout: ComicReaderLayout
    /// Where the page stands with the voice while it plays: "Following",
    /// "Reading", "Alignment unavailable" (`ReadAlongFollow`); empty when paused.
    var follow = ""
    /// The control a game controller is on, for its ring.
    var focused: ReadAlongControl?
    /// Play and pause; the narration's own toggle unless the reader says otherwise.
    var onPlay: (() -> Void)?
    let onFollow: () -> Void

    private var seekSeconds: Int { ListeningSettings.seekSeconds }

    var body: some View {
        let position = narration.position
        VStack(spacing: 4) {
            line(ReadAlongDockText.fraction(position, narration.timeline))
                .padding(.horizontal, 4)
            HStack(spacing: layout.phone ? 6 : 8) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(ReadAlongDockText.heading(follow: narration.playing ? follow : ""))
                        .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                    Text(ReadAlongDockText.time(position, narration.timeline))
                        .font(HubType.body(11.5, relativeTo: .caption2))
                        .monospacedDigit()
                        .foregroundStyle(.white.opacity(0.72))
                        .accessibilityIdentifier("readalong-time")
                }
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: layout.phone ? 8 : 10) {
                    PlayerSkipButton(systemImage: AudiobookView.jumpSymbol(seekSeconds, forward: false), text: nil,
                                     label: "Back \(seekSeconds) seconds", size: layout.round) {
                        narration.jump(by: -Int64(seekSeconds) * 1_000)
                    }
                    .overlay { ring(.back) }
                    .accessibilityIdentifier("readalong-back")
                    PlayerPlayDisc(playing: narration.playing, buffering: false, size: layout.round + 8) {
                        if let onPlay { onPlay() } else { narration.toggle() }
                    }
                        .accessibilityLabel(narration.playing ? "Pause narration" : "Play narration")
                        .overlay { ring(.play) }
                        .accessibilityIdentifier("readalong-play")
                    PlayerSkipButton(systemImage: AudiobookView.jumpSymbol(seekSeconds, forward: true), text: nil,
                                     label: "Forward \(seekSeconds) seconds", size: layout.round) {
                        narration.jump(by: Int64(seekSeconds) * 1_000)
                    }
                    .overlay { ring(.forward) }
                    .accessibilityIdentifier("readalong-forward")
                }

                HStack(spacing: 4) {
                    speed
                    GlassRoundButton(systemImage: "text.line.first.and.arrowtriangle.forward",
                                     label: "Return to narrated sentence", size: layout.round, action: onFollow)
                        .overlay { ring(.follow) }
                        .accessibilityIdentifier("readalong-follow")
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
        .padding(.horizontal, 10)
        .padding(.top, 8)
        .padding(.bottom, 6)
        .glassPanel(RoundedRectangle(cornerRadius: layout.corner, style: .continuous))
        // A container, so its controls keep their own identifiers.
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("readalong-dock")
    }

    /// The speeds, the book's marked: the audiobook's own.
    private var speed: some View {
        Menu {
            ForEach(Listening.speeds, id: \.self) { value in
                Button {
                    narration.setSpeed(value)
                } label: {
                    if abs(value - narration.speed) < 0.01 {
                        Label(PlayerLabels.rate(value), systemImage: "checkmark")
                    } else {
                        Text(PlayerLabels.rate(value))
                    }
                }
            }
        } label: {
            Text(PlayerLabels.rate(narration.speed))
                .monospacedDigit()
        }
        .menuStyle(.button)
        .buttonStyle(GlassControlStyle())
        .accessibilityLabel("Change narration speed")
        .accessibilityValue(PlayerLabels.rate(narration.speed))
        .overlay { ring(.speed) }
        .accessibilityIdentifier("readalong-speed")
    }

    /// How far through the part playing: a white line on a faint track.
    private func line(_ fraction: Double) -> some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.25))
                Capsule().fill(.white).frame(width: max(4, geometry.size.width * min(max(fraction, 0), 1)))
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func ring(_ control: ReadAlongControl) -> some View {
        if focused == control {
            Capsule().strokeBorder(.white, lineWidth: 2.5).padding(-4).allowsHitTesting(false)
        }
    }
}

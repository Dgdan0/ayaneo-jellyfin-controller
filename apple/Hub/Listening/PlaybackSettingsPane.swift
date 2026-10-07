import HubKit
import SwiftUI

/// Settings › Playback (#25 phase 2, #33; Android's `PlaybackSettings`): how
/// far the jump buttons go, 5, 10, 15 or 30 seconds (a video's −/+, arrows and
/// double tap, the audiobook's −/+ and the lock screen's skip buttons take
/// it); when the next episode's card comes; and whether intros skip themselves.
struct PlaybackSettingsPane: View {
    @State private var seconds = ListeningSettings.seekSeconds
    @State private var timing = PlaybackSettings.nextTiming
    @State private var autoSkip = PlaybackSettings.autoSkipIntro
    @Environment(\.glassAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            card(title: "Jump",
                 detail: "How far back and forward go: a video's buttons, arrows and double tap, and an audiobook's buttons and lock screen.") {
                pills(ListeningSettings.seekChoices, selected: seconds, label: { "\($0) seconds" }, id: { "seek-\($0)" }) { choice in
                    seconds = choice
                    ListeningSettings.seekSeconds = choice
                }
            }
            card(title: "Next episode",
                 detail: "When the card for the next episode comes near the end of one, counting down to play it.") {
                pills(NextEpisodeTiming.allCases, selected: timing, label: { $0.label }, id: { "next-\($0.rawValue)" }) { choice in
                    timing = choice
                    PlaybackSettings.nextTiming = choice
                }
            }
            card(title: "Intros", detail: nil) {
                Toggle(isOn: Binding(get: { autoSkip }, set: { on in
                    autoSkip = on
                    PlaybackSettings.autoSkipIntro = on
                })) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Skip intros automatically")
                            .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                            .foregroundStyle(.white)
                        Text("Intros and recaps skip themselves, once in each episode. Off, a Skip intro button comes instead.")
                            .font(HubType.body(13, relativeTo: .footnote))
                            .foregroundStyle(.white.opacity(0.64))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .tint(accent.tint)
                .accessibilityIdentifier("auto-skip-intro")
            }
        }
    }

    private func card<Content: View>(title: String, detail: String?, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                .foregroundStyle(.white)
            if let detail {
                Text(detail)
                    .font(HubType.body(13.5, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.64))
                    .fixedSize(horizontal: false, vertical: true)
            }
            content()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func pills<Choice: Hashable>(_ choices: [Choice], selected: Choice, label: @escaping (Choice) -> String,
                                         id: @escaping (Choice) -> String, choose: @escaping (Choice) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(choices, id: \.self) { choice in
                    ChoicePill(title: label(choice), selected: choice == selected) { choose(choice) }
                        .accessibilityIdentifier(id(choice))
                }
            }
        }
        .scrollClipDisabled()
    }
}

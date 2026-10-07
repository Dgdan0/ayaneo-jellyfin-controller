import HubKit
import SwiftUI

/// Settings › Playback (#25 phase 2): how far the jump buttons go, 5, 10, 15
/// or 30 seconds (Android's `PlaybackSettings.seekSeconds`): a video's −/+,
/// arrows and double tap, the audiobook's −/+ and the lock screen's skip
/// buttons take it.
struct PlaybackSettingsPane: View {
    @State private var seconds = ListeningSettings.seekSeconds

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Jump")
                .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                .foregroundStyle(.white)
            Text("How far back and forward go: a video's buttons, arrows and double tap, and an audiobook's buttons and lock screen.")
                .font(HubType.body(13.5, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.64))
                .fixedSize(horizontal: false, vertical: true)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ListeningSettings.seekChoices, id: \.self) { choice in
                        ChoicePill(title: "\(choice) seconds", selected: choice == seconds) {
                            seconds = choice
                            ListeningSettings.seekSeconds = choice
                        }
                        .accessibilityIdentifier("seek-\(choice)")
                    }
                }
            }
            .scrollClipDisabled()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

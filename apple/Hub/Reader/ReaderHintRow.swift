import HubKit
import SwiftUI

/// What the controller's main keys do now, in a reader's controls
/// (`ReaderPadMap.hints`): each chip is a button too, doing what its key
/// does. Where the words do not fit (an iPhone upright), the keys' caps stand
/// alone, their words kept for VoiceOver, rather than every word cut short.
struct ReaderHintRow: View {
    let hints: [ReaderHint]
    let send: (PadAction) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            row(words: true)
            row(words: false)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 7)
        .glassPanel(Capsule())
    }

    private func row(words: Bool) -> some View {
        HStack(spacing: words ? 14 : 18) {
            ForEach(hints, id: \.glyph) { hint in
                Button { send(hint.action) } label: {
                    HStack(spacing: 5) {
                        Text(hint.glyph).font(HubType.chrome(13, weight: .bold))
                        if words { Text(hint.label).font(HubType.chrome(12, weight: .semibold)) }
                    }
                    .lineLimit(1)
                    .fixedSize()
                }
                .buttonStyle(.plain)
                .foregroundStyle(.white.opacity(0.85))
                .accessibilityLabel(hint.label)
            }
        }
    }
}

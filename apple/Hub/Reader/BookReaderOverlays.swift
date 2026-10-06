#if os(iOS)
import HubKit
import SwiftUI

/// Opening the book, or why it could not be: Try again and Back; and when its
/// place could not be read, the beginning, which writes nothing, or another try.
struct BookReaderStatus: View {
    let reader: BookReaderModel
    let leave: () -> Void

    var body: some View {
        switch reader.phase {
        case .opening(let words):
            VStack(spacing: 14) {
                ProgressView().controlSize(.large).tint(.white)
                Text(words)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .allowsHitTesting(false)
        case .failed(let message):
            card(heading: "This book could not be opened", message: message) {
                Button("Back", action: leave).buttonStyle(GlassPillStyle())
                Button("Try again") { reader.retry() }.buttonStyle(GlassPillStyle())
            }
        case .choosing(let prompt):
            // Another device moved the place, or the hub could not say where it is.
            card(heading: prompt.title, message: prompt.message) {
                Button("Back", action: leave).buttonStyle(GlassPillStyle())
                ForEach(Array(prompt.choices.enumerated()), id: \.element.id) { index, choice in
                    Button {
                        reader.choose(choice.id)
                    } label: {
                        VStack(spacing: 2) {
                            Text(choice.label)
                            if !choice.detail.isEmpty {
                                Text(choice.detail)
                                    .font(HubType.body(12, relativeTo: .caption))
                                    .opacity(0.75)
                            }
                        }
                    }
                    .buttonStyle(PrimaryPillStyle(accent: index == 0 ? .gold : nil))
                    .accessibilityIdentifier("book-choice-" + choice.id)
                }
            }
        case .reading:
            EmptyView()
        }
    }

    private func card<Buttons: View>(heading: String, message: String,
                                     @ViewBuilder buttons: () -> Buttons) -> some View {
        VStack(spacing: 12) {
            Text(heading)
                .font(HubType.heading(22, weight: .heavy, relativeTo: .title3))
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("book-status-heading")
            Text(message)
                .font(HubType.body(14, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.center)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { buttons() }
                VStack(spacing: 10) { buttons() }
            }
            .padding(.top, 4)
        }
        .padding(24)
        .frame(maxWidth: 460)
        .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, 20)
    }
}

/// A footnote as a card over the page (#18, E5), instead of a jump away from
/// the sentence that cites it: the note's words in the glass of the reader's
/// sheets, with Go to the note and Close. Ⓑ or a tap beside the card closes
/// it and the page is where it was; going to the note leaves "Return to
/// previous place" in the menu.
struct FootnoteCard: View {
    let text: String
    let compact: Bool
    let follow: () -> Void
    let close: () -> Void

    var body: some View {
        ZStack {
            Color.black.opacity(0.35)
                .contentShape(Rectangle())
                .onTapGesture(perform: close)
                .accessibilityLabel("Close the note")
                .accessibilityAddTraits(.isButton)
            VStack(alignment: .leading, spacing: 12) {
                Text("Note")
                    .font(HubType.heading(18, weight: .heavy, relativeTo: .headline))
                    .accessibilityAddTraits(.isHeader)
                ScrollView {
                    Text(text)
                        .font(HubType.body(15.5, relativeTo: .body))
                        .lineSpacing(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .accessibilityIdentifier("book-footnote")
                }
                .scrollIndicators(.hidden)
                .scrollBounceBehavior(.basedOnSize)
                .frame(maxHeight: compact ? 300 : 360)
                .fixedSize(horizontal: false, vertical: true)
                HStack(spacing: 10) {
                    Button("Go to the note", action: follow).buttonStyle(PrimaryPillStyle(accent: .gold))
                    Button("Close", action: close).buttonStyle(GlassPillStyle())
                }
            }
            .padding(20)
            .frame(maxWidth: 520)
            .background {
                // Solid enough to read over lines of text, which must not show through it (#20).
                RoundedRectangle(cornerRadius: 22, style: .continuous).fill(Color(argb: 0xF21A_1C26))
            }
            .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
            .padding(.horizontal, 18)
            .accessibilityElement(children: .contain)
            .accessibilityAddTraits(.isModal)
        }
    }
}
#endif

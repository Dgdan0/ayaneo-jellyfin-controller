import HubKit
import SwiftUI

/// The card at the end of an issue (#16, C6): "End of Fantastic Four #51"
/// over "Next: #52", and what the keys do there as buttons: Continue to the
/// next issue (Ⓐ), Stay on the last page (Ⓑ), Leave (Select).
struct ComicEndCard: View {
    let card: ComicReaderModel.EndCard
    let compact: Bool
    let next: () -> Void
    let stay: () -> Void
    let leave: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            Text(card.heading)
                .font(HubType.heading(compact ? 18 : 20, weight: .heavy, relativeTo: .title3))
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .accessibilityIdentifier("comic-end-heading")
            Text(card.next)
                .font(HubType.body(13.5, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.78))
                .multilineTextAlignment(.center)
                .lineLimit(2)
            HStack(spacing: 10) {
                if card.canContinue {
                    Button("Continue", action: next).buttonStyle(PrimaryPillStyle(accent: .gold))
                }
                Button("Stay", action: stay).buttonStyle(GlassPillStyle())
                Button("Leave", action: leave).buttonStyle(GlassPillStyle())
            }
            .padding(.top, 10)
        }
        .padding(.horizontal, 22)
        .padding(.vertical, 18)
        .frame(maxWidth: 440)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

/// Opening an issue, or why it could not be: Try again and Back.
struct ComicReaderStatus: View {
    let reader: ComicReaderModel
    let leave: () -> Void

    var body: some View {
        switch reader.phase {
        case .opening:
            VStack(spacing: 14) {
                ProgressView().controlSize(.large).tint(.white)
                Text(reader.issueName.isEmpty ? "Opening \(reader.heading)…" : "Opening \(reader.heading), \(reader.issueName)…")
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .allowsHitTesting(false)
        case .failed(let message):
            VStack(spacing: 12) {
                Text("This issue could not be opened")
                    .font(HubType.heading(22, weight: .heavy, relativeTo: .title3))
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.75))
                    .multilineTextAlignment(.center)
                HStack(spacing: 10) {
                    Button("Back", action: leave).buttonStyle(GlassPillStyle())
                    Button("Try again") { reader.retry() }.buttonStyle(GlassPillStyle())
                }
                .padding(.top, 4)
            }
            .padding(24)
            .frame(maxWidth: 420)
            .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, 20)
        case .reading:
            if let page = reader.waitingFor {
                let first = reader.units.units.indices.contains(page) ? (reader.units.units[page].reading.first ?? page) : page
                ComicPill(text: "Loading page \(first + 1)…", spinning: true)
                    .frame(maxHeight: .infinity, alignment: reader.shown == nil ? .center : .top)
                    .padding(.top, 70)
            }
        }
    }
}

/// A quiet glass pill over the page: "Part 2 of 3", "Loading page 5…".
struct ComicPill: View {
    let text: String
    var spinning = false

    var body: some View {
        HStack(spacing: 8) {
            if spinning { ProgressView().controlSize(.small).tint(.white) }
            Text(text)
                .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                .monospacedDigit()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .glassPanel(Capsule())
        .allowsHitTesting(false)
        .accessibilityAddTraits(.updatesFrequently)
    }
}

/// The small map of the page in a corner (#16, C2): the page at its own
/// shape, the steps down it as faint bands, the part on screen outlined in
/// white. A tap on a band goes to that step.
struct ComicPageMapView: View {
    let reader: ComicReaderModel

    var body: some View {
        if let frame = reader.shownFrame {
            let aspect = frame.width > 0 ? frame.height / frame.width : 1.5
            let width: CGFloat = 60
            let height = min(120, width * aspect)
            let pageWidth = height / aspect
            let steps = reader.view.fit == .thirds && !reader.zoom.active ? frame.steps.map(frame.content.onPage) : []
            let visible = frame.visible(reader.camera)
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 3).fill(.white.opacity(0.28))
                RoundedRectangle(cornerRadius: 3).strokeBorder(.white.opacity(0.6), lineWidth: 1)
                ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                    if index != reader.state.viewportIndex {
                        band(step, width: pageWidth, height: height).stroke(.white.opacity(0.35), lineWidth: 1)
                    }
                }
                band(visible, width: pageWidth, height: height).fill(.white.opacity(0.24))
                band(visible, width: pageWidth, height: height).stroke(.white, lineWidth: 2)
            }
            .frame(width: pageWidth, height: height)
            .contentShape(Rectangle())
            .onTapGesture(coordinateSpace: .local) { point in
                if let step = ComicPageMap.step(at: point.y / height, steps: steps) { reader.pickStep(step) }
            }
            .accessibilityHidden(true)
        }
    }

    private func band(_ part: NormalizedViewport, width: CGFloat, height: CGFloat) -> Path {
        Path(CGRect(x: width * min(1, max(0, part.left)), y: height * min(1, max(0, part.top)),
                    width: width * (min(1, max(0, part.right)) - min(1, max(0, part.left))),
                    height: height * (min(1, max(0, part.bottom)) - min(1, max(0, part.top)))))
    }
}

/// The scrubber's preview: the page under its thumb, from the hub's
/// thumbnail, with its number, over the matching point of the track.
struct ComicScrubPreview: View {
    let path: String
    let page: Int
    let width: CGFloat

    var body: some View {
        VStack(spacing: 6) {
            ComicThumb(path: path)
                .frame(width: width - 16, height: (width - 16) * PageGrid.thumbAspect)
                .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            Text("Page \(page + 1)")
                .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                .monospacedDigit()
        }
        .padding(8)
        .frame(width: width)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .allowsHitTesting(false)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Page \(page + 1)")
        .accessibilityIdentifier("comic-scrub")
    }
}

import HubKit
import Observation
import SwiftUI
#if os(iOS)
import UIKit
#endif

/// Comfort for every reader (#37; Android's `ComfortSheet` and
/// `ComfortLayerView`): one setting, kept for every reader and every book,
/// changed from any of them and shown at once.
@MainActor
@Observable
final class ReaderComfort {
    static let shared = ReaderComfort()

    private(set) var value: ScreenComfort
    @ObservationIgnored private let store = ComfortStore()

    private init() {
        value = store.load()
    }

    func set(_ next: ScreenComfort) {
        guard next != value else { return }
        value = next
        store.save(next)
    }

    /// The screen stays awake while a book's narration plays, as asked (iOS).
    func keepAwake(narrating: Bool) {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = value.keepsScreenOn(narrating: narrating)
        #endif
    }

    /// A reader going away gives the screen back its own sleep.
    func letSleep() {
        #if os(iOS)
        UIApplication.shared.isIdleTimerDisabled = false
        #endif
    }
}

/// Comfort drawn over a whole reader, its page and its controls, as a
/// backlight would: the warmth multiplied into what is under it, which turns
/// white amber and leaves black black, then the dim as black laid over.
/// Touches and VoiceOver pass through it.
struct ComfortLayer: View {
    let comfort: ScreenComfort

    var body: some View {
        if !comfort.drawsNothing {
            ZStack {
                if comfort.warmColor != ScreenComfort.white {
                    Color(argb: comfort.warmColor).blendMode(.multiply)
                }
                Color.black.opacity(comfort.dimAlpha)
            }
            .ignoresSafeArea()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }
}

/// Brightness on a line, fixed at the foot of a book's Appearance (#47) as
/// Kindle's is: a small sun, the slider, a large sun and how bright. It is
/// Comfort's one brightness, for every reader.
struct BrightnessBar: View {
    /// The line a controller is on, ringed.
    var ringed = false
    @State private var comfort = ReaderComfort.shared

    var body: some View {
        let value = comfort.value
        let label = ScreenComfort.brightnessLabel(value.brightness)
        HStack(spacing: 10) {
            Image(systemName: "sun.min").font(.system(size: 15)).foregroundStyle(.white.opacity(0.7))
            Slider(value: Binding(get: { value.brightness }, set: { next in
                var changed = value
                changed.brightness = (next * 20).rounded() / 20
                comfort.set(changed)
            }), in: ScreenComfort.minBrightness...1)
                .tint(.white)
                .accessibilityLabel("Brightness")
                .accessibilityValue(label)
                .accessibilityIdentifier("comfort-brightness")
            Image(systemName: "sun.max").font(.system(size: 19)).foregroundStyle(.white.opacity(0.7))
            Text(label)
                .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                .monospacedDigit()
                .foregroundStyle(.white.opacity(0.62))
                .frame(width: 40, alignment: .trailing)
                .accessibilityHidden(true)
        }
        .padding(.top, 10)
        .readerRing(ringed)
    }
}

/// The Comfort controls, the same in every reader: brightness and warmth,
/// and for a book the screen kept on while narrating.
struct ComfortControls: View {
    /// A book's page: the screen kept awake is offered, and the brightness is
    /// the foot of the sheet's own (`BrightnessBar`).
    let book: Bool
    /// Its own heading, where it follows other options (the comic reader's);
    /// a tab or a sheet called Comfort already says so.
    var heading = false
    /// The line a controller is on, ringed (#25: `ComfortLine`, `SheetWalk`).
    var ring: ComfortLine?
    @State private var comfort = ReaderComfort.shared

    var body: some View {
        let value = comfort.value
        if heading { SheetLabel(text: "Comfort") }
        SheetGroup {
            if !book {
                slider("Brightness", value.brightness, ScreenComfort.minBrightness...1, ScreenComfort.brightnessLabel(value.brightness),
                       id: "comfort-brightness") { next in
                    var changed = value
                    changed.brightness = next
                    comfort.set(changed)
                }
                .readerRing(ring == .brightness)
                .id(Self.id(.brightness))
            }
            slider("Warmth", value.warmth, 0...1, ScreenComfort.warmthLabel(value.warmth), id: "comfort-warmth") { next in
                var changed = value
                changed.warmth = next
                comfort.set(changed)
            }
            .sheetDivider()
            .readerRing(ring == .warmth)
            .id(Self.id(.warmth))
        }
        if book {
            SheetGroup {
                SheetRow(title: "Keep the screen on while narrating", value: value.awakeWhileNarrating ? "On" : "Off") {
                    comfort.set(ComfortLine.awake.press(value))
                }
                .accessibilityIdentifier("comfort-awake")
                .readerRing(ring == .awake)
                .id(Self.id(.awake))
            }
        }
        SheetNote(text: "Dims and warms this app's page, not the screen's own light. Every reader opens this way until you change it.")
    }

    /// Where a sheet scrolls to bring `line` into view.
    static func id(_ line: ComfortLine) -> String { "comfort-\(line)" }

    private func slider(_ title: String, _ value: Double, _ range: ClosedRange<Double>, _ label: String, id: String,
                        _ change: @escaping (Double) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title).font(HubType.body(15, weight: .semibold, relativeTo: .body))
                Spacer()
                Text(label)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.62))
            }
            Slider(value: Binding(get: { value }, set: { change(($0 * 20).rounded() / 20) }), in: range)
                .tint(.white)
                .accessibilityLabel(title)
                .accessibilityValue(label)
                .accessibilityIdentifier(id)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
    }
}

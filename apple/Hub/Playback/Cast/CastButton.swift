import HubKit
import SwiftUI
#if canImport(GoogleCast) && os(iOS)
@preconcurrency import GoogleCast
#endif

/// The Cast button beside AirPlay (#44): Google's own, which looks for TVs
/// on its first tap and offers them, in the player's glass circle; with the
/// demo hub, a button that connects to the stand-in TV. Lit while casting.
struct CastButton: View {
    let size: CGFloat
    @Environment(\.glassAccent) private var accent

    var body: some View {
        let center = CastCenter.shared
        let label = CastPresentation.buttonLabel(center.connection)
        ZStack {
            Circle().fill(.clear).glassPanel(Circle())
            if center.stands {
                Button { center.toggleStandIn() } label: {
                    Image(systemName: "tv.and.mediabox")
                        .font(.system(size: size * 0.38, weight: .semibold))
                        .foregroundStyle(CastPresentation.lit(center.connection) ? accent.tint : .white)
                        .frame(width: size, height: size)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
            } else {
                #if canImport(GoogleCast) && os(iOS)
                GoogleCastButton(lit: CastPresentation.lit(center.connection), accent: UIColor(accent.tint))
                    .frame(width: size * 0.62, height: size * 0.62)
                #endif
            }
        }
        .frame(width: size, height: size)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("player-cast")
        .accessibilityAction { center.presentChooser() }
    }
}

#if canImport(GoogleCast) && os(iOS)
/// Google's Cast button: its icon shows connecting and connected, and a tap
/// opens Google's list of TVs, or the connected TV's Stop casting.
private struct GoogleCastButton: UIViewRepresentable {
    let lit: Bool
    let accent: UIColor

    func makeUIView(context: Context) -> GCKUICastButton {
        let button = GCKUICastButton(frame: CGRect(x: 0, y: 0, width: 28, height: 28))
        button.tintColor = .white
        return button
    }

    func updateUIView(_ button: GCKUICastButton, context: Context) {
        button.tintColor = lit ? accent : .white
    }
}
#endif

import HubKit
import SwiftUI

/// The Android app's palette (`ui/Theme.kt`), as asset colours with light and
/// dark variants, so both clients read as one product.
extension Color {
    static let surface = Color("Surface")
    static let card = Color("Card")
    static let cardPressed = Color("CardPressed")
    static let ink = Color("Ink")
    static let muted = Color("Muted")
    static let strip = Color("Strip")
    static let placeholder = Color("Placeholder")
    static let available = Color("Available")
    static let partial = Color("Partial")
    static let pending = Color("Pending")
    static let failed = Color("Failed")
    static let dangerText = Color("DangerText")
    static let accentInk = Color("AccentInk")
    static let hubMark = Color("HubMark")

    static func tone(_ tone: ServiceRow.Tone) -> Color {
        switch tone {
        case .available: .available
        case .pending: .pending
        case .muted: .muted
        case .danger: .failed
        }
    }

    static func status(_ tone: StatusTone) -> Color {
        switch tone {
        case .normal: .muted
        case .warning: .pending
        case .error: .dangerText
        }
    }
}

/// Figtree for body text and Bricolage Grotesque for headings, as on Android
/// (`ui/Type`). Both scale with Dynamic Type through `relativeTo`.
enum HubType {
    static func body(_ size: CGFloat = 16, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name = switch weight {
        case .medium: "Figtree-Medium"
        case .semibold: "Figtree-SemiBold"
        case .bold, .heavy, .black: "Figtree-Bold"
        default: "Figtree-Regular"
        }
        return .custom(name, size: size, relativeTo: style)
    }

    static func heading(_ size: CGFloat = 28, weight: Font.Weight = .bold, relativeTo style: Font.TextStyle = .largeTitle) -> Font {
        let name = switch weight {
        case .semibold: "BricolageGrotesque-SemiBold"
        case .heavy, .black: "BricolageGrotesque-ExtraBold"
        default: "BricolageGrotesque-Bold"
        }
        return .custom(name, size: size, relativeTo: style)
    }
}

/// The app's mark (the pipeline feeding a play head) on its teal square,
/// drawn from the same geometry as the Android launcher icon.
struct HubMark: View {
    var size: CGFloat = 40

    var body: some View {
        Canvas { context, canvas in
            let k = canvas.width / 72 // the adaptive icon's 72-unit safe zone, offset by 18
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: (x - 18) * k, y: (y - 18) * k) }
            for y in [38.0, 50, 62] {
                context.fill(Path(CGRect(origin: p(26, y), size: CGSize(width: 16 * k, height: 7 * k))), with: .color(.white))
            }
            var triangle = Path()
            triangle.move(to: p(52, 32))
            triangle.addLine(to: p(82, 54))
            triangle.addLine(to: p(52, 76))
            triangle.closeSubpath()
            context.fill(triangle, with: .color(.white))
        }
        .frame(width: size, height: size)
        .background(Color.hubMark, in: RoundedRectangle(cornerRadius: size * 0.26, style: .continuous))
        .accessibilityHidden(true)
    }
}

/// A screen's status line: the words in their tone, and Try again when the
/// failure is one retrying can fix.
struct StatusLine: View {
    let message: StatusMessage
    var retry: (() -> Void)?

    var body: some View {
        if !message.text.isEmpty {
            HStack(spacing: 12) {
                Text(message.text)
                    .font(HubType.body(15, relativeTo: .subheadline))
                    .foregroundStyle(Color.status(message.tone))
                    .fixedSize(horizontal: false, vertical: true)
                if message.offersRetry, let retry {
                    Button("Try again", action: retry)
                        .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                        .buttonStyle(.borderless)
                }
            }
            .accessibilityElement(children: .combine)
        }
    }
}

import SwiftUI

/// What "on this device" looks like (#48): an accent disc with a down arrow
/// in it. Never a tick: a tick already means watched (`WatchBadge`), and the
/// two side by side in the accent read as nonsense. The card's corner, the
/// round download button, the season button, a menu's rows and a downloaded
/// series' page all use this one; `symbol` is for a `Label` that has no room for the view.
struct DownloadedMark: View {
    /// The mark as a system image, for menus and labels.
    static let symbol = "arrow.down.circle.fill"

    @Environment(\.glassAccent) private var accent
    var size: CGFloat = 28

    var body: some View {
        Image(systemName: Self.symbol)
            .symbolRenderingMode(.palette)
            .foregroundStyle(.white, accent.tint)
            .font(.system(size: size, weight: .semibold))
            .frame(width: size, height: size)
            .shadow(color: .black.opacity(0.3), radius: 3, y: 1)
            .accessibilityHidden(true)
    }
}

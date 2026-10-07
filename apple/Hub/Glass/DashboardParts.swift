import HubKit
import SwiftUI

// What the dashboards are made of (Android's `ui/DashboardParts`): Activity, the
// server monitor, Services and Notifications. A card, a disk, a quiet line and a
// bar are in `Activity/ActivityView`, which drew them first; these are the
// others the later dashboards share.

/// A small round status mark before a name, coloured by `DashboardTone`.
struct StatusDot: View {
    let tone: ServiceRow.Tone
    var size: CGFloat = 8

    var body: some View {
        Circle()
            .fill(Color.tone(tone))
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A figure on its own glass card: a quiet label, the value large, then a line
/// or a bar. CPU, memory, uptime (`DashboardParts.stat`).
struct StatCard: View {
    let figure: ServerMonitorPresentation.Figure
    @Environment(\.glassAccent) private var accent

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            GlassLabel(text: figure.label)
            Text(figure.value)
                .font(HubType.heading(24, weight: .bold, relativeTo: .title2))
                .foregroundStyle(figure.warning ? Color.dangerText : .white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .padding(.top, 2)
            if !figure.detail.isEmpty {
                Text(figure.detail)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.64))
                    .lineLimit(1)
            }
            if let fraction = figure.fraction {
                TransferBar(fraction: fraction, tint: figure.warning ? Color.failed : accent.tint)
                    .padding(.top, 5)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(figure.spoken)
        .accessibilityIdentifier("figure-\(figure.label)")
    }
}

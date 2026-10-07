import HubKit
import SwiftUI

/// Settings › Notifications (#36; Android's `NotificationSettingsScreen`): how
/// many recent entries each service's column loads. The Notifications page and
/// the bell take the new lengths on their next read.
struct NotificationsSettingsPane: View {
    @State private var limits = NotificationSettings.limits()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text("History")
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Text("Choose how many recent entries each service loads. More entries use a little more data when Notifications refreshes. What you have seen is kept separately.")
                    .font(HubType.body(13.5, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.64))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 4)
            ForEach(NotificationSettings.services, id: \.self) { service in
                limitCard(service)
            }
        }
    }

    private func limitCard(_ service: String) -> some View {
        let current = limits.limit(for: service) ?? 0
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text(ServiceNames.display(service))
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                Text("\(current) entries")
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(NotificationSettings.choices, id: \.self) { choice in
                        ChoicePill(title: "\(choice) entries", selected: choice == current) {
                            NotificationSettings.setLimit(choice, for: service)
                            limits = NotificationSettings.limits()
                        }
                        .accessibilityIdentifier("limit-\(service)-\(choice)")
                    }
                }
            }
            .scrollClipDisabled()
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("\(ServiceNames.display(service)) history, \(current) entries")
    }
}

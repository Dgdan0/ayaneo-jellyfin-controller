import HubKit
import SwiftUI

/// Settings › Home (#35; Android's `SettingsScreen.homeRows`): the rows Home
/// shows, each on or off and moved up or down, and a row of the newest titles
/// from any library ("From Anime"), off until it is turned on. Home changes
/// the next time it is looked at.
struct HomeSettingsPane: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassAccent) private var accent
    @State private var layout = HomeLayoutModel.shared
    @State private var status = StatusMessage("")

    var body: some View {
        let rows = layout.rows
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Home rows")
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Text("Turn a row on or off and move it up or down. A row with nothing in it is left out of Home, and a library can have a row of its newest titles.")
                    .font(HubType.body(13.5, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.64))
                    .fixedSize(horizontal: false, vertical: true)
                StatusLine(message: status) { Task { await loadLibraries() } }
            }
            .padding(.horizontal, 4)
            VStack(spacing: 0) {
                ForEach(Array(rows.enumerated()), id: \.element.id) { index, row in
                    line(row)
                    if index < rows.count - 1 {
                        Rectangle().fill(.white.opacity(0.08)).frame(height: 1).padding(.leading, 16)
                    }
                }
            }
            .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .task(id: model.userId) { await loadLibraries() }
    }

    private func line(_ row: HomeSettingsRow) -> some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(row.shown ? .white : .white.opacity(0.6))
                if !row.detail.isEmpty {
                    Text(row.detail)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.58))
                }
            }
            Spacer(minLength: 6)
            moveButton("chevron.up", "Move \(row.title) up", enabled: row.canMoveUp) { layout.move(row.id, by: -1) }
                .accessibilityIdentifier("home-up-\(row.id)")
                .padFocusable("home-up-\(row.id)", ring: .circle) { if row.canMoveUp { layout.move(row.id, by: -1) } }
            moveButton("chevron.down", "Move \(row.title) down", enabled: row.canMoveDown) { layout.move(row.id, by: 1) }
                .accessibilityIdentifier("home-down-\(row.id)")
                .padFocusable("home-down-\(row.id)", ring: .circle) { if row.canMoveDown { layout.move(row.id, by: 1) } }
            Toggle(row.title, isOn: Binding(get: { row.shown }, set: { layout.setShown(row.id, $0) }))
                .labelsHidden()
                .tint(accent.tint)
                .accessibilityLabel(row.title)
                .accessibilityIdentifier("home-row-\(row.id)")
                .padFocusable("home-row-\(row.id)", ring: .capsule) { layout.setShown(row.id, !row.shown) }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Move up") { if row.canMoveUp { layout.move(row.id, by: -1) } }
        .accessibilityAction(named: "Move down") { if row.canMoveDown { layout.move(row.id, by: 1) } }
    }

    private func moveButton(_ symbol: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .bold))
                .foregroundStyle(.white.opacity(enabled ? 0.9 : 0.25))
                .frame(width: 36, height: 36)
                .glassPanel(Circle())
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    /// The libraries a row can be added for, from the hub; Settings still lists the
    /// built-in rows when it cannot say.
    private func loadLibraries() async {
        do {
            let response = try await model.hub.fetch(HubEndpoints.library, as: LibraryResponse.self)
            layout.setLibraries(response.views.map { HomeLibrary(id: $0.id, name: $0.name) })
            status = StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed("Libraries could not be listed: " + error.message, kind: error.kind,
                                       hasData: !layout.libraries.isEmpty)
        }
    }
}

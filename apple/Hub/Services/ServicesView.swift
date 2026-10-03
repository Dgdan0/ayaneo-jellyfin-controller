import HubKit
import SwiftUI

/// The hub, the server monitor and every service with its live state: the
/// first proof that the whole stack is reachable from this device. Android's
/// Manage screen (`screens/manage/ManageScreen`).
struct ServicesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    @State private var health: HealthResponse?
    @State private var status = StatusMessage("")
    @State private var loading = false
    @State private var failed = false
    @State private var scanning: String?
    @State private var editingConnection = false

    private var rows: [ServiceRow] {
        failed && health == nil
            ? ServiceRows.failedRows(address: model.address)
            : ServiceRows.rows(health: health, address: model.address)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                StatusLine(message: status) { Task { await load() } }
                    .padding(.horizontal, 4)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 320, maximum: 640), spacing: 14)], spacing: 14) {
                    ForEach(rows) { row in
                        ServiceCard(row: row, scanning: scanning == row.id, open: { open(row) },
                                    scan: { Task { await scan(row) } })
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .background(Color.surface)
        .navigationTitle("Services")
        .refreshable { await load() }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    Task { await load() }
                } label: {
                    Label("Refresh", systemImage: "arrow.clockwise")
                }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(loading)
            }
        }
        .task { await load() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await load() } }
        }
        .sheet(isPresented: $editingConnection) {
            NavigationStack { HubConnectionView(onConnected: { Task { await load() } }) }
                #if os(macOS)
                .frame(minWidth: 520, minHeight: 460)
                #endif
        }
        .navigationDestination(for: String.self) { destination in
            if destination == "monitor" {
                ComingNextView(title: "Server monitor", systemImage: "cpu",
                               detail: "CPU, memory, disk space, containers and current playback.")
            }
        }
    }

    private func load() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        status = StatusText.loading("services", refreshing: health != nil)
        do {
            let value = try await model.hub.fetch(HubEndpoints.health, as: HealthResponse.self)
            health = value
            failed = false
            status = ServiceRows.summary(ServiceRows.rows(health: value, address: model.address))
        } catch {
            if error.kind == .cancelled { return }
            failed = true
            let message = health == nil ? error.message + " · open Ayaneo Hub to edit the connection" : error.message
            status = StatusText.failed(message, kind: error.kind, hasData: health != nil)
        }
    }

    private func open(_ row: ServiceRow) {
        switch row.kind {
        case .hub:
            editingConnection = true
        case .monitor:
            break // a NavigationLink in the card
        case .service:
            if let url = URL(string: row.dashboardURL), !row.dashboardURL.isEmpty { openURL(url) }
        }
    }

    private func scan(_ row: ServiceRow) async {
        guard scanning == nil else {
            status = StatusMessage("A library scan is already running")
            return
        }
        scanning = row.id
        defer { scanning = nil }
        status = StatusMessage("Scanning \(row.name) library…")
        let request = row.id == "jellyfin" ? HubEndpoints.scanJellyfinLibrary : HubEndpoints.scanReadingLibrary(service: row.id)
        do {
            try await model.hub.send(request)
            status = StatusMessage("\(row.name) library scan accepted · new files may take a moment to appear")
        } catch {
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }
}

/// One service: its logo, name, state and what the hub knows about it.
struct ServiceCard: View {
    let row: ServiceRow
    let scanning: Bool
    let open: () -> Void
    let scan: () -> Void

    var body: some View {
        Group {
            if row.kind == .monitor {
                NavigationLink(value: "monitor") { content }
            } else {
                Button(action: open) { content }
            }
        }
        .buttonStyle(CardButtonStyle())
        .contextMenu {
            if !row.dashboardURL.isEmpty {
                Button("Open \(row.name)", systemImage: "safari", action: open)
            }
            if row.canScan {
                Button("Scan library", systemImage: "arrow.triangle.2.circlepath", action: scan)
            }
            if row.kind == .hub {
                Button("Edit connection", systemImage: "pencil", action: open)
            }
        }
    }

    private var content: some View {
        HStack(alignment: .center, spacing: 14) {
            logo
            VStack(alignment: .leading, spacing: 3) {
                Text(row.name)
                    .font(HubType.body(17, weight: .semibold, relativeTo: .headline))
                    .foregroundStyle(Color.ink)
                HStack(spacing: 6) {
                    if row.showsDot {
                        Circle().fill(Color.tone(row.tone)).frame(width: 8, height: 8)
                    }
                    Text(row.stateWord)
                        .font(HubType.body(14, weight: .medium, relativeTo: .subheadline))
                        .foregroundStyle(row.showsDot ? Color.tone(row.tone) : Color.muted)
                }
                Text(row.detail)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(Color.muted)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            Spacer(minLength: 8)
            if row.canScan {
                Button(action: scan) {
                    if scanning {
                        ProgressView().controlSize(.small)
                    } else {
                        Text("Scan")
                    }
                }
                .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .disabled(scanning)
            } else if row.kind != .service || !row.dashboardURL.isEmpty {
                Image(systemName: row.kind == .service ? "arrow.up.forward" : "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.muted)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: 96, alignment: .leading)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(hint)
    }

    @ViewBuilder private var logo: some View {
        if let logo = row.logo {
            Image(logo)
                .resizable()
                .scaledToFit()
                .frame(width: 40, height: 40)
                .accessibilityHidden(true)
        } else if row.kind == .monitor {
            Image(systemName: "cpu")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Color.hubMark, in: RoundedRectangle(cornerRadius: 10.4, style: .continuous))
                .accessibilityHidden(true)
        } else {
            HubMark(size: 40)
        }
    }

    private var hint: String {
        switch row.kind {
        case .hub: "Edits the hub address and token"
        case .monitor: "Opens the server monitor"
        case .service: row.dashboardURL.isEmpty ? "" : "Opens \(row.name) in the browser"
        }
    }
}

/// A raised card that darkens while pressed, like the Android cards.
struct CardButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(configuration.isPressed ? Color.cardPressed : Color.card,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .shadow(color: .black.opacity(0.06), radius: 6, y: 2)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

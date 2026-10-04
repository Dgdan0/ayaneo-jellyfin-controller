import HubKit
import SwiftUI

/// The hub, the server monitor and every service with its live state: the
/// first proof that the whole stack is reachable from this device. Android's
/// Manage screen (`screens/manage/ManageScreen`), as the prototype's
/// `pgServices` draws it.
struct ServicesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.glassMetrics) private var metrics

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
            VStack(alignment: .leading, spacing: 0) {
                header
                // Two columns on an iPad, one on a phone (the prototype's `.svcs`).
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 380, maximum: 640), spacing: 12)], spacing: 12) {
                    ForEach(rows) { row in
                        ServiceCard(row: row, scanning: scanning == row.id, open: { open(row) },
                                    scan: { Task { await scan(row) } })
                    }
                }
                .padding(.top, 12)
                .padding(.bottom, 26)
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
        }
        .refreshable { await load() }
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
    }

    /// The page's own heading, now that the shell's bar carries no title: the
    /// name, then how everything is ("All 13 running"), and Refresh.
    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            PageHeading(title: "Services") {
                StatusLine(message: status) { Task { await load() } }
            }
            Spacer(minLength: 0)
            GlassRoundButton(systemImage: "arrow.clockwise", label: "Refresh", size: 44) {
                Task { await load() }
            }
            .keyboardShortcut("r", modifiers: .command)
            .disabled(loading)
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

/// One service (the prototype's `.scard`): a glass card with its logo, name,
/// state and what the hub knows about it, ringed while a pointer rests on it.
/// A service that is down or misconfigured has an amber edge, as the Activity
/// dashboard's attention card has.
struct ServiceCard: View {
    let row: ServiceRow
    let scanning: Bool
    let open: () -> Void
    let scan: () -> Void

    private var needsAttention: Bool { row.state == "down" || row.state == "misconfigured" }

    var body: some View {
        Group {
            if row.kind == .monitor {
                NavigationLink(value: AppRoute.monitor) { content }
            } else {
                Button(action: open) { content }
            }
        }
        .buttonStyle(GlassCardStyle())
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
            VStack(alignment: .leading, spacing: 2) {
                Text(row.name)
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                HStack(spacing: 6) {
                    if row.showsDot {
                        Circle().fill(Color.tone(row.tone)).frame(width: 8, height: 8)
                    }
                    Text(row.stateWord)
                        .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                        .foregroundStyle(row.showsDot ? Color.tone(row.tone) : .white.opacity(0.64))
                }
                Text(row.detail)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.64))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if row.canScan {
                Button(action: scan) {
                    HStack(spacing: 6) {
                        if scanning {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "arrow.clockwise").font(.system(size: 12, weight: .bold))
                        }
                        Text("Scan")
                    }
                    .font(HubType.body(13, weight: .bold, relativeTo: .footnote))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .foregroundStyle(.white)
                    .glassPanel(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(scanning)
            } else if row.kind != .service || !row.dashboardURL.isEmpty {
                Image(systemName: row.kind == .service ? "arrow.up.forward" : "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.5))
            }
        }
        .padding(.horizontal, 15)
        .padding(.vertical, 13)
        .frame(maxWidth: .infinity, minHeight: 78, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            if needsAttention {
                // The prototype's amber edge at 55%.
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Color(argb: 0x8CF2_B544), lineWidth: 1.5)
            }
        }
        .litRing(corner: 20)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(hint)
    }

    private var logo: some View {
        Group {
            if let logo = row.logo {
                Image(logo)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 40, height: 40)
            } else if row.kind == .monitor {
                Image(systemName: "cpu")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 46, height: 46)
                    .background(Color.hubMark, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                HubMark(size: 46)
            }
        }
        .frame(width: 46, height: 46)
        .accessibilityHidden(true)
    }

    private var hint: String {
        switch row.kind {
        case .hub: "Edits the hub address and token"
        case .monitor: "Opens the server monitor"
        case .service: row.dashboardURL.isEmpty ? "" : "Opens \(row.name) in the browser"
        }
    }
}

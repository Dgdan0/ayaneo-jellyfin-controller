import HubKit
import SwiftUI

/// The Media side's Activity tab (#29; Android's `ActivityScreen`, the
/// prototype's dashboard): one glance at the server. "Activity" over a line
/// saying how things stand, then glass cards: what is downloading, with the
/// speed mode, and what needs attention, edged in amber; what is coming up;
/// and every service, each opening its own dashboard in the browser, above
/// the disks. Every card is a short selection: all the transfers, the
/// calendar and the server are one press away.
///
/// Three columns where there is room, two on an iPad held upright, one on a
/// phone. The transfers are asked for on `PollSchedule`'s pace while the page
/// shows; the services, disks, limits and calendar every half minute.
struct ActivityView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent
    @Environment(\.openURL) private var openURL
    /// False while another section or the player is in front: nothing is asked for then.
    @Environment(\.isEnabled) private var isEnabled

    @State private var activity: ActivityResponse?
    @State private var activityError = ""
    @State private var health: HealthResponse?
    @State private var disks: [HostDisk] = []
    @State private var bandwidth: BandwidthState?
    @State private var agenda: [ActivityDashboard.AgendaEntry]?
    @State private var agendaError = ""
    @State private var notice = StatusMessage("")
    @State private var width: CGFloat = 0
    @State private var polls = 0

    private var attention: [ActivityDashboard.Attention] {
        ActivityDashboard.attention(activity, health: health, disks: disks)
    }

    var body: some View {
        let attention = attention
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Activity") {
                    let line = ActivityDashboard.headline(activity, attention: attention.count, health: health)
                    if !line.isEmpty {
                        Text(line)
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.66))
                            .accessibilityIdentifier("activity-headline")
                    }
                }
                StatusLine(message: notice)
                    .padding(.top, 8)
                columns(attention)
                    .padding(.top, 14)
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .refreshable { polls += 1 }
        .task(id: "\(polls)·\(isEnabled)") {
            guard isEnabled else { return }
            await pollTransfers()
        }
        .task(id: "\(polls)·\(isEnabled)·slow") {
            guard isEnabled else { return }
            while !Task.isCancelled {
                await loadSlow()
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    // MARK: Layout

    @ViewBuilder private func columns(_ attention: [ActivityDashboard.Attention]) -> some View {
        let inner = width - metrics.margin * 2
        let count = inner >= 960 ? 3 : (inner >= 620 ? 2 : 1)
        let downloading = DashboardCard(title: "Downloading", trailing: { speedLine }) { transfers }
        let needs = AttentionCard(entries: attention)
        let upcoming = DashboardCard(title: "Upcoming", trailing: { EmptyView() }) { agendaRows }
        let services = DashboardCard(title: "Services", trailing: { servicesSummary }) { serviceRows }
        switch count {
        case 3:
            HStack(alignment: .top, spacing: 12) {
                VStack(spacing: 12) { downloading; needs }.frame(maxWidth: .infinity)
                VStack(spacing: 12) { upcoming }.frame(maxWidth: .infinity)
                VStack(spacing: 12) { services; storage }.frame(maxWidth: .infinity)
            }
        case 2:
            HStack(alignment: .top, spacing: 12) {
                VStack(spacing: 12) { downloading; needs; upcoming }.frame(maxWidth: .infinity)
                VStack(spacing: 12) { services; storage }.frame(maxWidth: .infinity)
            }
        default:
            VStack(spacing: 12) { downloading; needs; upcoming; services; storage }
        }
    }

    // MARK: Downloading

    @ViewBuilder private var speedLine: some View {
        if let summary = activity?.summary, summary.downSpeedBytes > 0 || summary.upSpeedBytes > 0 {
            Text("↓ \(Fmt.speed(summary.downSpeedBytes)) · ↑ \(Fmt.speed(summary.upSpeedBytes))")
                .font(HubType.body(12, weight: .semibold, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.66))
                .monospacedDigit()
                .lineLimit(1)
        }
    }

    @ViewBuilder private var transfers: some View {
        let rows = activity.map { ActivityDashboard.transfers($0) } ?? []
        if activity == nil && activityError.isEmpty {
            QuietLine(text: "Asking the hub…")
        } else if rows.isEmpty && !activityError.isEmpty {
            QuietLine(text: activityError, tone: .error)
        } else if rows.isEmpty {
            let seeding = activity?.summary.seeding ?? 0
            QuietLine(text: seeding > 0 ? "Nothing downloading · \(seeding) seeding" : "Nothing downloading")
        }
        ForEach(rows) { item in
            NavigationLink(value: AppRoute.transfers(TransfersRoute())) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.headline)
                        .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    TransferBar(fraction: item.progress)
                    Text(ActivityDashboard.transferLine(item))
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.66))
                        .lineLimit(1)
                }
                .padding(.vertical, 6)
                .padding(.horizontal, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            }
            .buttonStyle(DashboardRowStyle())
            .accessibilityLabel("\(item.headline), \(ActivityDashboard.transferLine(item))")
            .accessibilityHint("Opens all transfers")
        }
        HStack(spacing: 10) {
            if let bandwidth, bandwidth.canControl, bandwidth.modeSwitchSupported {
                GlassCapsulePicker(items: [.init(id: "normal", title: "Normal speed"), .init(id: "alternative", title: "Quiet")],
                                   selection: bandwidth.mode) { chosen in Task { await chooseSpeed(chosen) } }
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("speed-mode")
            }
            Spacer(minLength: 0)
            NavigationLink(value: AppRoute.transfers(TransfersRoute())) {
                Text("All transfers ›")
                    .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(accent.tint)
                    .padding(.vertical, 6)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("all-transfers")
        }
        .padding(.top, 4)
    }

    private func chooseSpeed(_ mode: String) async {
        guard bandwidth?.mode != mode else { return }
        do {
            bandwidth = try await model.hub.fetch(HubEndpoints.setBandwidth(BandwidthChange(mode: mode)), as: BandwidthState.self)
            notice = StatusMessage(mode == "alternative" ? "Quiet: qBittorrent's alternative limits" : "Normal speed")
        } catch {
            if error.kind == .cancelled { return }
            notice = StatusMessage(error.message, tone: .error)
        }
    }

    // MARK: Upcoming

    @ViewBuilder private var agendaRows: some View {
        if let agenda {
            if agenda.isEmpty {
                QuietLine(text: agendaError.isEmpty ? "Nothing scheduled in the next two weeks" : agendaError,
                          tone: agendaError.isEmpty ? .normal : .error)
            }
            ForEach(Array(agenda.enumerated()), id: \.element.id) { index, entry in
                // One heading per day, however many titles share it.
                if index == 0 || agenda[index - 1].heading != entry.heading {
                    Text(entry.heading.uppercased())
                        .font(HubType.body(10.5, weight: .bold, relativeTo: .caption2))
                        .tracking(1.1)
                        .foregroundStyle(entry.state == .missing && entry.heading.hasPrefix("Missed") ? Color.dangerText
                                         : entry.heading.hasPrefix("Today") ? accent.tint : .white.opacity(0.55))
                        .padding(.top, index == 0 ? 0 : 6)
                        .padding(.leading, 8)
                }
                agendaRow(entry)
            }
        } else {
            QuietLine(text: agendaError.isEmpty ? "Asking the hub…" : agendaError, tone: agendaError.isEmpty ? .normal : .error)
        }
    }

    @ViewBuilder private func agendaRow(_ entry: ActivityDashboard.AgendaEntry) -> some View {
        let row = HStack(spacing: 10) {
            ArtworkView(path: entry.media.poster, width: 92, placeholder: .white.opacity(0.08))
                .frame(width: 28, height: 42)
                .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.media.title)
                    .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text(entry.line)
                    .font(HubType.body(12, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.66))
                    .lineLimit(1)
            }
            Spacer(minLength: 6)
            ReleaseStateChip(state: entry.state)
        }
        .padding(.vertical, 5)
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        if entry.media.key.isEmpty {
            row
        } else {
            NavigationLink(value: AppRoute.media(MediaRoute(key: entry.media.key, title: entry.media.title))) { row }
                .buttonStyle(DashboardRowStyle())
                .accessibilityLabel("\(entry.heading), \(entry.media.title), \(entry.line), \(entry.state.label)")
        }
    }

    // MARK: Services and storage

    @ViewBuilder private var servicesSummary: some View {
        if let health {
            let allUp = health.services.allSatisfy { $0.state == "up" || $0.state == "disabled" }
            Text(ActivityDashboard.servicesSummary(health))
                .font(HubType.body(12, weight: .bold, relativeTo: .caption))
                .foregroundStyle(allUp ? Color.available : Color.dangerText)
        }
    }

    @ViewBuilder private var serviceRows: some View {
        if let health {
            let shown = health.services.filter { $0.state != "disabled" }
                .sorted { ServiceNames.rank($0.name) < ServiceNames.rank($1.name) }
            ForEach(shown) { service in
                let name = ServiceNames.display(service.name)
                let meta = ActivityDashboard.serviceMeta(service)
                Button { open(service) } label: {
                    HStack(spacing: 9) {
                        Circle().fill(Color.tone(ServiceRows.tone(service.state))).frame(width: 8, height: 8)
                        Text(name)
                            .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                            .foregroundStyle(.white)
                        Spacer(minLength: 6)
                        Text(meta)
                            .font(HubType.body(12, relativeTo: .caption))
                            .foregroundStyle(service.state == "up" ? .white.opacity(0.6) : Color.dangerText)
                            .monospacedDigit()
                            .lineLimit(1)
                        Image(systemName: "arrow.up.forward")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(.white.opacity(0.45))
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 8)
                    .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                }
                .buttonStyle(DashboardRowStyle())
                .accessibilityLabel("\(name), \(meta)")
                .accessibilityHint("Opens it in the browser")
                .accessibilityIdentifier("service-\(service.name)")
            }
        } else {
            QuietLine(text: "Asking the hub…")
        }
    }

    /// A service's own page, in the browser. An address naming the media PC's
    /// loopback would look for the service on this device, so the setting to
    /// fix is named instead of opening a blank page.
    private func open(_ service: ServiceHealth) {
        let name = ServiceNames.display(service.name)
        guard ActivityDashboard.reachable(service.dashboardUrl), let url = URL(string: service.dashboardUrl) else {
            notice = StatusMessage("\(name) has no address this device can reach · set services.\(service.name).web_url in hub.yaml",
                                   tone: .warning)
            return
        }
        openURL(url)
    }

    @ViewBuilder private var storage: some View {
        if !disks.isEmpty {
            NavigationLink(value: AppRoute.monitor) {
                DashboardCard(title: "Storage", trailing: {
                    Image(systemName: "chevron.forward")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.5))
                }) {
                    ForEach(disks) { disk in DiskLine(disk: disk) }
                }
                .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens the server monitor")
            .accessibilityIdentifier("storage-card")
        }
    }

    // MARK: Loading

    /// The transfers, then again on `PollSchedule`'s pace: every two seconds
    /// while something moves, every ten when nothing does, backing off after
    /// failures. A failed read keeps what was shown: the hub out of reach for
    /// a moment is not the downloads gone.
    private func pollTransfers() async {
        var failures = 0
        while !Task.isCancelled {
            do {
                let response = try await model.hub.fetch(HubEndpoints.activity(), as: ActivityResponse.self)
                activity = response
                activityError = response.partial.first { $0.service == "qbittorrent" }?.message ?? ""
                failures = 0
            } catch {
                if error.kind == .cancelled { return }
                failures += 1
                activityError = error.message
            }
            guard let delay = PollSchedule.next(active: activity?.anyActive ?? false, failures: failures, settling: false) else {
                return
            }
            try? await Task.sleep(for: delay)
        }
    }

    /// Services, disks, the limits and the calendar change slowly: together, every half minute.
    private func loadSlow() async {
        let hub = model.hub
        async let health = try? hub.fetch(HubEndpoints.health, as: HealthResponse.self)
        async let monitor = try? hub.fetch(HubEndpoints.monitor, as: ServerMonitor.self)
        async let limits = try? hub.fetch(HubEndpoints.bandwidth, as: BandwidthState.self)
        let zone = TimeZone.current
        let range = ActivityDashboard.agendaRange(now: .now, zone: zone)
        async let calendar = Self.calendar(hub, start: range.start, end: range.end, zone: zone)
        if let value = await health { self.health = value }
        if let value = await monitor { disks = value.host.disks }
        if let value = await limits { bandwidth = value }
        switch await calendar {
        case .success(let response):
            agenda = ActivityDashboard.agenda(response.items, now: .now, zone: zone)
            agendaError = ""
        case .failure(let error):
            if error.kind != .cancelled && agenda == nil { agendaError = error.message }
        }
    }
}

extension ActivityView {
    /// The agenda's month, or why it could not be read.
    nonisolated static func calendar(_ hub: HubClient, start: String, end: String,
                                     zone: TimeZone) async -> Result<CalendarResponse, HubFailure> {
        do {
            return .success(try await hub.fetch(HubEndpoints.calendar(start: start, end: end, timezone: zone.identifier),
                                                as: CalendarResponse.self))
        } catch {
            return .failure(error)
        }
    }
}

/// A dashboard card (`SettingsCard` on the Pocket): a bold heading with
/// something on its right, then its rows, on glass; edged in amber when it
/// says something needs attention.
struct DashboardCard<Trailing: View, Content: View>: View {
    let title: String
    var attention = false
    @ViewBuilder let trailing: Trailing
    @ViewBuilder let content: Content

    init(title: String, attention: Bool = false, @ViewBuilder trailing: () -> Trailing, @ViewBuilder content: () -> Content) {
        self.title = title
        self.attention = attention
        self.trailing = trailing()
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .font(HubType.heading(18, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                trailing
            }
            .padding(.horizontal, 8)
            .padding(.bottom, 4)
            content
        }
        .padding(10)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            if attention {
                // The prototype's amber edge at 55%, as a service needing a look has.
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .strokeBorder(Color(argb: 0x8CF2_B544), lineWidth: 1.5)
            }
        }
        .accessibilityElement(children: .contain)
    }
}

/// What needs attention: broken transfers with Why is this stuck?, services
/// down, disks nearly full. Absent when nothing does.
struct AttentionCard: View {
    let entries: [ActivityDashboard.Attention]

    var body: some View {
        if !entries.isEmpty {
            DashboardCard(title: "Needs attention", attention: true, trailing: { EmptyView() }) {
                ForEach(entries) { entry in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(entry.title)
                            .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                            .foregroundStyle(.white)
                        Text(entry.detail)
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.66))
                            .fixedSize(horizontal: false, vertical: true)
                        if !entry.transferId.isEmpty {
                            NavigationLink(value: AppRoute.transfers(TransfersRoute(target: entry.transferId))) {
                                Text("Why is this stuck?")
                            }
                            .buttonStyle(GlassControlStyle())
                            .padding(.top, 4)
                            .accessibilityIdentifier("why-\(entry.transferId)")
                        }
                    }
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .accessibilityIdentifier("attention-card")
        }
    }
}

/// A drive: its name, how much is free of how much, and a bar of what is
/// used, amber once less than a tenth is free.
struct DiskLine: View {
    let disk: HostDisk

    var body: some View {
        let low = ActivityDashboard.lowSpace(disk)
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline) {
                Text(ActivityDashboard.diskName(disk.name))
                    .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                Spacer(minLength: 6)
                Text("\(Fmt.bytes(disk.availableBytes)) free of \(Fmt.bytes(disk.totalBytes))")
                    .font(HubType.body(12, relativeTo: .caption))
                    .foregroundStyle(low ? Color.pending : .white.opacity(0.6))
                    .monospacedDigit()
            }
            TransferBar(fraction: disk.usedFraction, tint: low ? .pending : .white)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .accessibilityElement(children: .combine)
    }
}

/// A slim bar: white on the glass page, as every progress bar on it.
struct TransferBar: View {
    let fraction: Double
    var tint: Color = .white

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.2))
                Capsule().fill(tint).frame(width: max(0, min(1, fraction)) * geometry.size.width)
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}

/// A quiet line where a card has nothing yet, or what went wrong.
struct QuietLine: View {
    let text: String
    var tone: StatusTone = .normal

    var body: some View {
        Text(text)
            .font(HubType.body(13, relativeTo: .footnote))
            .foregroundStyle(Color.status(tone))
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// A row inside a dashboard card: a quiet fill while pressed or under the pointer.
struct DashboardRowStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .background(.white.opacity(configuration.isPressed ? 0.12 : 0),
                        in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .opacity(isEnabled ? 1 : 0.5)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

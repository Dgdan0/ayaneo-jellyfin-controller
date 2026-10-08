import HubKit
import SwiftUI

/// The media PC at a glance (#36; Android's `ServerMonitorScreen`, the
/// prototype's `pgMonitor`): CPU, memory and uptime on cards across the top,
/// then its disks and what is playing beside the Docker containers. Read-only.
/// It asks again every 15 seconds while it is shown, and when the hub cannot be
/// reached it keeps the last figures and says they may be old.
struct ServerMonitorView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.isEnabled) private var isEnabled

    @State private var monitor: ServerMonitor?
    /// Why the last read failed, with the figures from before it still shown.
    @State private var failure: String?
    @State private var loading = false
    @State private var width: CGFloat = 0
    @State private var polls = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if let monitor {
                    figures(monitor.host)
                        .padding(.top, 14)
                    cards(monitor)
                        .padding(.top, 12)
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        // Refresh, then the cards, which take the ring so a controller can read down the page (#46).
        .padPage("server-monitor")
        .refreshable { await load() }
        .task(id: "\(polls)·\(isEnabled)") {
            guard isEnabled else { return }
            var failures = 0
            while !Task.isCancelled {
                failures = await load() ? 0 : failures + 1
                guard let delay = PollSchedule.next(active: true, failures: failures, cadence: .monitor) else { return }
                try? await Task.sleep(for: delay)
            }
        }
    }

    private var status: StatusMessage {
        if let failure { return StatusMessage(ServerMonitorPresentation.failed(failure), tone: .error) }
        guard let monitor else { return StatusMessage("Asking the media PC…") }
        return StatusMessage(ServerMonitorPresentation.status(monitor))
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            PageHeading(title: "Server monitor") {
                StatusLine(message: status)
                    .accessibilityIdentifier("monitor-status")
            }
            Spacer(minLength: 0)
            GlassRoundButton(systemImage: "arrow.clockwise", label: "Refresh", size: 44, pad: "refresh") { polls += 1 }
                .keyboardShortcut("r", modifiers: .command)
                .disabled(loading)
        }
    }

    // MARK: Cards

    /// Three across where they fit; on a phone CPU and memory side by side
    /// with the uptime under them.
    @ViewBuilder private func figures(_ host: HostSnapshot) -> some View {
        let list = ServerMonitorPresentation.figures(host)
        let inner = width - metrics.margin * 2
        if inner >= 520 {
            HStack(alignment: .top, spacing: 12) { ForEach(list) { StatCard(figure: $0) } }
        } else {
            VStack(spacing: 12) {
                HStack(alignment: .top, spacing: 12) { ForEach(list.prefix(2)) { StatCard(figure: $0) } }
                ForEach(list.dropFirst(2)) { StatCard(figure: $0) }
            }
        }
    }

    @ViewBuilder private func cards(_ monitor: ServerMonitor) -> some View {
        let inner = width - metrics.margin * 2
        if inner >= 700 {
            HStack(alignment: .top, spacing: 12) {
                VStack(spacing: 12) {
                    disks(monitor)
                    playing(monitor)
                }
                .frame(maxWidth: .infinity)
                containers(monitor)
                    .frame(maxWidth: .infinity)
            }
        } else {
            VStack(spacing: 12) {
                disks(monitor)
                playing(monitor)
                containers(monitor)
            }
        }
    }

    private func disks(_ monitor: ServerMonitor) -> some View {
        DashboardCard(title: "Disks", trailing: { trailing(ServerMonitorPresentation.diskCount(monitor.host)) }) {
            if monitor.host.disks.isEmpty {
                QuietLine(text: "No disk figures from this PC")
            }
            ForEach(monitor.host.disks) { disk in
                DiskLine(disk: disk)
                    .accessibilityLabel(ServerMonitorPresentation.spoken(disk))
            }
            // "G:\ space unavailable": a drive the hub can name but not measure.
            ForEach(Array(monitor.host.warnings.enumerated()), id: \.offset) { _, warning in
                QuietLine(text: warning)
            }
        }
        .accessibilityIdentifier("card-disks")
        .padFocusable("disks", ring: .rounded(20), press: nil)
    }

    private func playing(_ monitor: ServerMonitor) -> some View {
        DashboardCard(title: "Playing now", trailing: { trailing(ServerMonitorPresentation.playingTrailing) }) {
            if let note = ServerMonitorPresentation.playingNote(monitor) {
                QuietLine(text: note)
            }
            ForEach(Array(monitor.sessions.enumerated()), id: \.offset) { _, session in
                itemRow(name: session.title, line: ServerMonitorPresentation.sessionLine(session),
                        dot: session.paused ? .muted : .available)
            }
        }
        .accessibilityIdentifier("card-playing")
        .padFocusable("playing", ring: .rounded(20), press: nil)
    }

    private func containers(_ monitor: ServerMonitor) -> some View {
        DashboardCard(title: "Docker containers", trailing: { trailing(ServerMonitorPresentation.containerCount(monitor.containers)) }) {
            if let note = ServerMonitorPresentation.containerNote(monitor) {
                QuietLine(text: note)
            }
            ForEach(ServerMonitorPresentation.containers(monitor.containers)) { container in
                itemRow(name: container.name, line: container.line, dot: container.tone)
            }
        }
        .accessibilityIdentifier("card-containers")
        .padFocusable("containers", ring: .rounded(20), press: nil)
    }

    private func trailing(_ text: String) -> some View {
        Text(text)
            .font(HubType.body(12, weight: .semibold, relativeTo: .caption))
            .foregroundStyle(.white.opacity(0.62))
            .lineLimit(1)
    }

    /// A name with its state dot and a quiet line under it.
    private func itemRow(name: String, line: String, dot: ServiceRow.Tone) -> some View {
        HStack(alignment: .top, spacing: 10) {
            StatusDot(tone: dot, size: 8)
                .padding(.top, 5)
            VStack(alignment: .leading, spacing: 2) {
                Text(name)
                    .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                if !line.isEmpty {
                    Text(line)
                        .font(HubType.body(12, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.64))
                        .lineLimit(2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel([name, line].filter { !$0.isEmpty }.joined(separator: ", "))
    }

    // MARK: Loading

    /// True when the read worked. A failed one keeps the figures from before.
    @discardableResult
    private func load() async -> Bool {
        guard !loading else { return true }
        loading = true
        defer { loading = false }
        do {
            monitor = try await model.hub.fetch(HubEndpoints.monitor, as: ServerMonitor.self)
            failure = nil
            return true
        } catch {
            if error.kind != .cancelled { failure = error.message }
            return error.kind == .cancelled
        }
    }
}

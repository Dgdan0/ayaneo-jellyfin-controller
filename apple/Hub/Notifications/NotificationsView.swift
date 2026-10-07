import HubKit
import SwiftUI

/// Notifications (#36; Android's `NotificationsScreen`, the prototype's
/// `pgNotifs`): what Sonarr, Radarr and Bazarr (or, on the Books side,
/// BookKeeprr, Kavita and Storyteller) have been doing, one glass column each,
/// with their current health warnings pinned above the history.
///
/// A dot marks what is unread. A row is seen when it has stayed on screen for a
/// moment, when focus reaches it, or when it is tapped, which also opens its
/// whole message; Mark all seen answers the rest. The bell's count is the same
/// answer (`NotificationsModel`).
///
/// Three columns where there is room and one on top of another where there is
/// not, as the prototype stacks them on a phone. Each shows its newest entries
/// and Show all opens the rest, so the services further down are never a long
/// scroll away.
struct NotificationsView: View {
    @Environment(AppModel.self) private var model
    @Environment(NotificationsModel.self) private var notifications
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.appSide) private var side
    @Environment(\.isEnabled) private var isEnabled

    @State private var width: CGFloat = 0
    @State private var polls = 0
    /// What Mark all seen answered, said for a few seconds in the status line.
    @State private var flash = ""
    @FocusState private var focused: String?

    private var otherSide: AppSide { side == .media ? .books : .media }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                columns
                    .padding(.top, 14)
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 28)
            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        }
        .refreshable { await notifications.refresh(model) }
        .task(id: "\(polls)·\(isEnabled)·\(model.address)") {
            guard isEnabled else { return }
            var failures = 0
            while !Task.isCancelled {
                await notifications.refresh(model)
                failures = notifications.failure == nil ? 0 : failures + 1
                guard let delay = PollSchedule.next(active: true, failures: failures, cadence: .notifications) else { return }
                try? await Task.sleep(for: delay)
            }
        }
        .task(id: flash) {
            guard !flash.isEmpty else { return }
            try? await Task.sleep(for: .seconds(3))
            flash = ""
        }
        // Keyboard or controller focus on a row is reading it.
        .onChange(of: focused) { _, id in
            if let id { notifications.markSeen(id) }
        }
    }

    // MARK: Heading

    private var status: StatusMessage {
        if !flash.isEmpty { return StatusMessage(flash) }
        if let failure = notifications.failure { return failure }
        guard let response = notifications.response else { return StatusText.loading("activity", refreshing: false) }
        return NotificationsPresentation.status(response, unread: notifications.unread(on: side), otherSide: otherSide,
                                                unreadOnOtherSide: notifications.unread(on: otherSide))
    }

    private var header: some View {
        let wide = !metrics.compact
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                PageHeading(title: "Notifications") {
                    StatusLine(message: status) { polls += 1 }
                        .accessibilityIdentifier("notifications-status")
                }
                if wide {
                    Spacer(minLength: 0)
                    controls
                }
            }
            if !wide { controls }
        }
    }

    private var controls: some View {
        HStack(spacing: 10) {
            if notifications.unread > 0 {
                Button {
                    let hadUnread = notifications.unread > 0
                    notifications.markAllSeen()
                    flash = NotificationsPresentation.markAllLine(hadUnread: hadUnread)
                } label: {
                    Label("Mark all seen", systemImage: "checkmark.circle")
                }
                .buttonStyle(GlassControlStyle())
                .accessibilityIdentifier("mark-all-seen")
            }
            GlassRoundButton(systemImage: "arrow.clockwise", label: "Refresh", size: 44) {
                Task { await notifications.refresh(model) }
            }
            .keyboardShortcut("r", modifiers: .command)
        }
    }

    // MARK: Columns

    @ViewBuilder private var columns: some View {
        let list = notifications.response.map {
            NotificationsPresentation.columns(side: side, sections: $0.sections, previous: notifications.kept,
                                              unread: notifications.unreadIds)
        } ?? []
        let inner = width - metrics.margin * 2
        // About 260 points a column; an iPhone, even turned, keeps them one over another.
        if inner >= 780 {
            HStack(alignment: .top, spacing: 12) {
                ForEach(list) { column in
                    NotificationColumnView(column: column, focused: $focused).frame(maxWidth: .infinity)
                }
            }
        } else {
            VStack(spacing: 12) {
                ForEach(list) { column in NotificationColumnView(column: column, focused: $focused) }
            }
        }
    }
}

/// One service's column: its logo, name and state with the unread count, then
/// its health warnings and its newest history.
struct NotificationColumnView: View {
    let column: NotificationsPresentation.Column
    let focused: FocusState<String?>.Binding
    @Environment(NotificationsModel.self) private var notifications
    @Environment(\.glassAccent) private var accent

    @State private var showsAll = false
    @State private var open: Set<String> = []

    /// How many of the history a column shows before Show all.
    static let collapsed = 8

    var body: some View {
        let pinned = column.items.filter(\.active)
        let history = column.items.filter { !$0.active }
        let shown = showsAll ? history : Array(history.prefix(Self.collapsed))
        VStack(alignment: .leading, spacing: 2) {
            header
            if column.items.isEmpty {
                QuietLine(text: column.empty)
            }
            ForEach(pinned + shown) { notice in row(notice) }
            if history.count > Self.collapsed {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { showsAll.toggle() }
                } label: {
                    Text(showsAll ? "Show fewer" : "Show all \(history.count)")
                        .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(accent.tint)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("more-\(column.service)")
            }
        }
        .padding(10)
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("column-\(column.service)")
    }

    private var header: some View {
        HStack(spacing: 11) {
            Image("logo_" + column.service)
                .resizable()
                .scaledToFit()
                .frame(width: 32, height: 32)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(column.name)
                    .font(HubType.heading(17, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                HStack(spacing: 6) {
                    StatusDot(tone: DashboardTone.of(column.state), size: 7)
                    Text(column.stateLabel)
                        .font(HubType.body(12, relativeTo: .caption))
                        .foregroundStyle(stateColor)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 8)
            if column.unread > 0 {
                Text("\(column.unread)")
                    .font(HubType.chrome(11, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(Color.glassAlert, in: Capsule())
                    .accessibilityLabel(column.unreadLabel)
                    .accessibilityIdentifier("unread-\(column.service)")
            }
        }
        .padding(.horizontal, 8)
        .padding(.bottom, 8)
    }

    private var stateColor: Color {
        switch column.state {
        case "degraded": .pending
        case "up", "disabled": .white.opacity(0.64)
        default: .dangerText
        }
    }

    private func row(_ notice: ServiceNotice) -> some View {
        let unread = notifications.unreadIds.contains(notice.id)
        let isOpen = open.contains(notice.id)
        let now = Date()
        let meta = NotificationsPresentation.meta(notice, now: now)
        return Button {
            notifications.markSeen(notice.id)
            withAnimation(.easeOut(duration: 0.2)) {
                if isOpen { open.remove(notice.id) } else { open.insert(notice.id) }
            }
        } label: {
            HStack(alignment: .top, spacing: 10) {
                Circle()
                    .fill(severityColor(notice))
                    .frame(width: 8, height: 8)
                    .padding(.top, 5)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text(NotificationsPresentation.headline(notice))
                        .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(isOpen ? nil : 2)
                        .multilineTextAlignment(.leading)
                    if !notice.detail.isEmpty {
                        Text(notice.detail)
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.66))
                            .lineLimit(isOpen ? nil : 2)
                            .multilineTextAlignment(.leading)
                    }
                    if !meta.isEmpty {
                        Text(meta)
                            .font(HubType.body(12, weight: notice.active ? .bold : .regular, relativeTo: .caption))
                            .foregroundStyle(notice.active ? Color.pending : .white.opacity(0.52))
                    }
                }
                Spacer(minLength: 4)
                if unread {
                    Circle()
                        .fill(accent.tint)
                        .frame(width: 9, height: 9)
                        .padding(.top, 4)
                        .accessibilityHidden(true)
                }
            }
            .padding(.vertical, 8)
            .padding(.horizontal, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(unread ? Color.white.opacity(0.08) : .clear, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
        }
        .buttonStyle(DashboardRowStyle())
        .focused(focused, equals: notice.id)
        .onScrollVisibilityChange(threshold: 0.6) { notifications.rowVisible(notice.id, $0) }
        .accessibilityLabel(NotificationsPresentation.spoken(notice, unread: unread, now: now))
        .accessibilityValue([unread ? "Unread" : "", isOpen ? "Expanded" : ""].filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityHint(isOpen ? "Shows less" : "Shows the whole message")
        .accessibilityIdentifier("notice-\(notice.id)")
    }

    private func severityColor(_ notice: ServiceNotice) -> Color {
        switch NotificationsPresentation.Severity(notice.severity) {
        case .success: .available
        case .warning: .pending
        case .error: .failed
        case .info: accent.tint
        }
    }
}

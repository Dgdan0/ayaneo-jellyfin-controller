import HubKit
import SwiftUI

/// Upcoming, Discover's second tab (the prototype's `upcomingHtml`; Android
/// `UpcomingScreen`): a week's monitored releases from Sonarr and Radarr, day
/// by day, a season's episodes on one day as one row, each with where it is
/// (Soon, Aired, Missing, In library). On an iPad or a Mac the chosen release
/// shows beside the days with Open title; on a phone a row opens it.
struct UpcomingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute
    @Binding var week: Int

    @State private var groups: [UpcomingGroup] = []
    @State private var status = StatusMessage("")
    @State private var selectedId: String?
    @State private var loadedWeek: Int?
    @State private var loads = 0

    private var zone: TimeZone { .current }
    private var today: String { UpcomingPresentation.today(now: .now, zone: zone) }
    private var range: UpcomingPresentation.Range { UpcomingPresentation.range(today: today, week: week) }
    private var selected: UpcomingGroup? {
        UpcomingPresentation.selected(groups, previous: selectedId, week: week, today: today)
    }
    /// The preview beside the days, where there is room for it (not on a phone).
    private var previews: Bool { !metrics.compact }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                if loadedWeek == week, status.text.isEmpty {
                    Text(UpcomingPresentation.count(groups))
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.6))
                }
                StatusLine(message: status) { loads += 1 }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 10)
            HStack(alignment: .top, spacing: 22) {
                days.frame(maxWidth: .infinity, alignment: .leading)
                if previews {
                    preview
                        .frame(width: metrics.small ? 300 : 360)
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 12)
        }
        .ambientArtwork(selected?.first.media.poster ?? "")
        .task(id: "\(week)·\(loads)") { await load() }
    }

    // MARK: Days

    private var days: some View {
        VStack(alignment: .leading, spacing: 18) {
            ForEach(UpcomingPresentation.days(range), id: \.self) { day in
                let list = groups.filter { $0.first.date == day }
                VStack(alignment: .leading, spacing: 8) {
                    dayHeading(day, empty: list.isEmpty)
                    ForEach(list) { group in
                        Button {
                            if previews {
                                selectedId = group.id
                            } else {
                                open(group)
                            }
                        } label: {
                            UpcomingRow(group: group, state: UpcomingPresentation.state(group, now: .now, zone: zone),
                                        time: UpcomingPresentation.timeLabel(group, zone: zone),
                                        chosen: previews && group.id == selected?.id)
                        }
                        .buttonStyle(GlassCardStyle())
                        .accessibilityHint(previews ? "Shows it beside the days" : "Opens the title")
                    }
                }
            }
        }
    }

    /// "Fri 2 Oct", today's in the accent with its Today pill, and "· Nothing
    /// scheduled" on an empty day.
    private func dayHeading(_ day: String, empty: Bool) -> some View {
        DayHeading(day: day, today: today, empty: empty)
    }

    // MARK: Preview

    @ViewBuilder private var preview: some View {
        if let group = selected {
            let state = UpcomingPresentation.state(group, now: .now, zone: zone)
            VStack(alignment: .leading, spacing: 0) {
                Color.clear
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .overlay { ArtworkView(path: group.first.media.poster, width: 640) }
                    .clipped()
                    .overlay(alignment: .bottomLeading) { ReleaseStateChip(state: state).padding(12) }
                VStack(alignment: .leading, spacing: 6) {
                    Text(group.first.media.title)
                        .font(HubType.heading(26, weight: .heavy, relativeTo: .title))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    let line = [group.label, group.items.count == 1 ? group.first.episodeTitle : ""]
                        .filter { !$0.isEmpty }.joined(separator: " · ")
                    if !line.isEmpty {
                        Text(line)
                            .font(HubType.body(15, relativeTo: .subheadline))
                            .foregroundStyle(.white)
                    }
                    Text(UpcomingPresentation.previewDate(group, zone: zone))
                        .font(HubType.body(13.5, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.62))
                    Button {
                        open(group)
                    } label: {
                        Label("Open title", systemImage: "info.circle")
                    }
                    .buttonStyle(PrimaryPillStyle())
                    .disabled(group.first.media.key.isEmpty)
                    .padding(.top, 6)
                    if group.first.media.key.isEmpty {
                        Text("Title details unavailable: missing metadata ID")
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .padding(.horizontal, 18)
                .padding(.top, 16)
                .padding(.bottom, 18)
            }
            .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        } else if loadedWeek == week {
            VStack(alignment: .leading, spacing: 6) {
                Text("Nothing this week")
                    .font(HubType.body(17, weight: .semibold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Text("Choose another week above.")
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.62))
            }
            .padding(20)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
        }
    }

    private func open(_ group: UpcomingGroup) {
        guard !group.first.media.key.isEmpty else { return }
        openRoute(.media(MediaRoute(key: group.first.media.key, title: group.first.media.title)))
    }

    // MARK: Loading

    private func load() async {
        let wanted = week
        let range = self.range
        if loadedWeek != wanted {
            groups = []
            selectedId = nil
        }
        status = StatusMessage("Loading schedule…")
        do {
            let response = try await model.hub.fetch(
                HubEndpoints.calendar(start: range.start, end: range.endExclusive, timezone: zone.identifier),
                as: CalendarResponse.self)
            guard wanted == week else { return }
            groups = UpcomingPresentation.groups(response.items)
            loadedWeek = wanted
            model.colors.want(groups.map(\.first.media.poster))
            status = StatusText.caveat(CacheInfo(), unavailable: response.partial.map(\.service))
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: !groups.isEmpty)
        }
    }
}

/// A day over its releases: "Fri 2 Oct", today's in the accent with a Today
/// or Tomorrow pill, and "· Nothing scheduled" on an empty day.
struct DayHeading: View {
    let day: String
    let today: String
    let empty: Bool
    @Environment(\.glassAccent) private var accent

    var body: some View {
        HStack(spacing: 8) {
            Text(UpcomingPresentation.dayHeading(day))
                .font(HubType.body(15, weight: .heavy, relativeTo: .subheadline))
                .foregroundStyle(day == today ? accent.tint : .white)
            if let tag = UpcomingPresentation.dayTag(day, today: today) {
                Text(tag)
                    .font(HubType.chrome(11, weight: .bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .foregroundStyle(accent.inkColor)
                    .background(accent.tint, in: Capsule())
            }
            if empty {
                Text("·  Nothing scheduled")
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.5))
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One release, or a season's episodes on one day (`.uit`): its poster, its
/// name, "S2E6 · 21:00", and where it is. The one in the preview is lit in the
/// accent.
struct UpcomingRow: View {
    let group: UpcomingGroup
    let state: ReleaseState
    let time: String
    let chosen: Bool
    @Environment(\.glassAccent) private var accent

    var body: some View {
        HStack(spacing: 12) {
            ArtworkView(path: group.first.media.poster, width: 120)
                .frame(width: 40, height: 60)
                .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(group.first.media.title)
                    .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Text([group.label, time].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            ReleaseStateChip(state: state)
        }
        .padding(.leading, 8)
        .padding(.trailing, 12)
        .padding(.vertical, 8)
        .background {
            if chosen {
                RoundedRectangle(cornerRadius: 16, style: .continuous).fill(accent.tint.opacity(0.18))
            }
        }
        .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay {
            if chosen {
                RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(accent.tint, lineWidth: 2)
            }
        }
        .litRing(corner: 16)
        .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(chosen ? .isSelected : [])
    }
}

/// Soon, Aired, Missing or In library (`.st`).
struct ReleaseStateChip: View {
    let state: ReleaseState

    var body: some View {
        Text(state.label)
            .font(HubType.chrome(11, weight: .bold))
            .lineLimit(1)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(Color(argb: state.ink))
            .background(Color(argb: state.fill), in: Capsule())
    }
}

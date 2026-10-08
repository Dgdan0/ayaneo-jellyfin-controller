import HubKit
import SwiftUI

/// A series' episodes to download (#5; Android's `OfflineSelectionScreen`):
/// its seasons from the hub with `?format=apple`, each episode with the size
/// of the MP4 the PC will make, ticked by hand or by the quick choices (the
/// next three, five or ten from where Play would start, or every one), then
/// asked once with the room it takes and queued as one batch. An episode
/// already here or on its way is shown but cannot be ticked again, and one
/// that cannot become an MP4 says so.
struct OfflinePickerView: View {
    let route: OfflinePickerRoute
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.dismiss) private var dismiss
    @State private var offline = OfflineLibrary.shared
    @State private var selection: OfflineSelectionResponse?
    @State private var chosen: Set<String> = []
    @State private var status = StatusMessage("")
    @State private var confirming = false
    @State private var sending = false

    private var seasons: [OfflineSelectionSeason] { selection?.seasons ?? [] }

    /// Episodes not already on this device or on their way.
    private var selectable: [OfflineSelectionItem] {
        OfflineSelection.selectable(seasons) { offline.row(forItem: $0) != nil }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Download episodes") {
                    Text(route.title)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                StatusLine(message: status) { Task { await load() } }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 8)
                if selection != nil {
                    quickChoices
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 12)
                    ForEach(seasons, id: \.season.id) { season in
                        seasonCard(season)
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 18)
                    }
                }
            }
            .padding(.bottom, 120)
        }
        .safeAreaInset(edge: .bottom) {
            if selection != nil { footer }
        }
        .task { if selection == nil { await load() } }
        .alert(OfflineSelection.confirmTitle(chosen.count), isPresented: $confirming) {
            // The harmless answer in the cancel role: without one, iOS 26 adds a Cancel of its own.
            Button("Not now", role: .cancel) {}
            Button("Download") { Task { await send() } }
        } message: {
            Text(OfflineSelection.confirmDetail(bytes: OfflineSelection.size(chosen, among: selectable),
                                                free: offline.freeBytes, source: nil, format: OfflineFormat.apple))
        }
    }

    private func load() async {
        status = StatusMessage("Asking the hub for the episodes…")
        do {
            selection = try await model.hub.fetch(HubEndpoints.offlineSelection(seriesId: route.seriesId, format: OfflineFormat.apple),
                                                  as: OfflineSelectionResponse.self)
            status = StatusMessage(OfflineSelection.available(seasons).isEmpty ? "No episode here can be downloaded." : "")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusMessage(error.kind == .forbidden ? OfflineTransfer.noScope : error.message, tone: .error)
        }
    }

    // MARK: Choosing

    private var quickChoices: some View {
        let items = selectable
        let start = selection?.playTargetId ?? ""
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach([3, 5, 10], id: \.self) { count in
                    ChoicePill(title: "Next \(count)", selected: false) {
                        chosen = Set(OfflineSelection.next(count, among: items, playTargetId: start).map(\.id))
                    }
                    .accessibilityIdentifier("pick-next-\(count)")
                }
                ChoicePill(title: "All \(items.count)", selected: false) { chosen = Set(items.map(\.item.id)) }
                    .accessibilityIdentifier("pick-all")
                ChoicePill(title: "None", selected: false) { chosen = [] }
                    .accessibilityIdentifier("pick-none")
            }
        }
        .scrollClipDisabled()
        .disabled(items.isEmpty)
    }

    private func seasonCard(_ season: OfflineSelectionSeason) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            GlassLabel(text: season.season.title.isEmpty ? EpisodeLabel.season(season.season.indexNumber) : season.season.title)
            VStack(spacing: 0) {
                ForEach(season.episodes, id: \.item.id) { episode in
                    episodeRow(episode)
                }
            }
            .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }

    private func episodeRow(_ episode: OfflineSelectionItem) -> some View {
        let item = episode.item
        let stored = offline.row(forItem: item.id)
        let canPick = episode.available && stored == nil
        let ticked = chosen.contains(item.id)
        return Button {
            if ticked { chosen.remove(item.id) } else { chosen.insert(item.id) }
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: stored != nil ? "arrow.down.circle.fill" : (ticked ? "checkmark.circle.fill" : "circle"))
                    .font(.system(size: 20))
                    .foregroundStyle(ticked || stored != nil ? .white : .white.opacity(0.5))
                VStack(alignment: .leading, spacing: 3) {
                    Text(EpisodeLabel.of(season: item.seasonNumber, episode: item.indexNumber, title: item.title))
                        .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(canPick || stored != nil ? 1 : 0.5))
                        .lineLimit(2)
                    Text(line(episode, stored: stored))
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 6)
                if item.played {
                    Image(systemName: "eye.fill")
                        .font(.system(size: 13))
                        .foregroundStyle(.white.opacity(0.5))
                        .accessibilityLabel("Watched")
                }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!canPick)
        .accessibilityAddTraits(ticked ? .isSelected : [])
        .accessibilityIdentifier("pick-" + item.id)
    }

    /// "About 1.2 GB", "About 1.2 GB · takes longer to prepare · 1 subtitle
    /// left out", "On this device", "On its way", or why it cannot come.
    private func line(_ episode: OfflineSelectionItem, stored: OfflineRow?) -> String {
        if let stored { return stored.state == .complete ? "On this device" : "On its way" }
        guard episode.available else { return "The PC cannot make an MP4 of this one" }
        var parts = ["About " + Fmt.bytes(episode.estimatedSizeBytes)]
        if let apple = episode.apple, let notes = OfflineAppleNotes.selection([apple]) { parts.append(notes) }
        return parts.joined(separator: " · ")
    }

    // MARK: Sending

    private var footer: some View {
        let items = selectable
        return HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(OfflineSelection.counter(chosen, among: items))
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .monospacedDigit()
                    .accessibilityIdentifier("pick-counter")
                if let notes = OfflineAppleNotes.selection(items.filter { chosen.contains($0.item.id) }.compactMap(\.apple)) {
                    Text(notes)
                        .font(HubType.body(12, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                }
            }
            Spacer(minLength: 8)
            Button {
                confirming = true
            } label: {
                Label("Download", systemImage: "arrow.down.circle")
            }
            .buttonStyle(PrimaryPillStyle())
            .disabled(chosen.isEmpty || sending)
            .accessibilityIdentifier("pick-download")
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .padding(.horizontal, metrics.margin)
        .padding(.bottom, 8)
    }

    /// The chosen episodes, in the series' order, as one batch.
    private func send() async {
        guard !sending else { return }
        sending = true
        defer { sending = false }
        let ids = selectable.map(\.item.id).filter(chosen.contains)
        status = StatusMessage("Asking the PC for \(ids.count == 1 ? "1 episode" : "\(ids.count) episodes")…")
        if let problem = await offline.download(itemIds: ids, title: route.title, seriesId: route.seriesId) {
            status = StatusMessage(problem, tone: .error)
            return
        }
        chosen = []
        status = StatusMessage("On their way · Downloads shows how far")
    }
}

import HubKit
import SwiftUI

/// A downloaded film, or a series with its downloaded episodes, on its own page.
struct OfflineTitleRoute: Hashable {
    /// The film's or the series' Jellyfin id (`OfflineCatalogEntry.key`).
    let key: String
    let title: String
}

/// The episodes of a series to download; from a season's Download season,
/// that season's episodes ticked (#43).
struct OfflinePickerRoute: Hashable {
    let seriesId: String
    let title: String
    var seasonId = ""
}

/// The Media side's Downloads tab (#5; Android's offline screen): what is on
/// this device, one poster per film or series under its library, A to Z,
/// which plays with no hub; and the queue, a batch at a time, each download
/// with how far it is, on the PC while its MP4 is made and then on its way
/// here, with Pause, Resume, Retry and Remove.
struct DownloadsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute
    @Environment(\.shellStack) private var stack

    @State private var offline = OfflineLibrary.shared
    @State private var taps = DownloadAlertTaps.shared
    @State private var tab: Tab
    @State private var removing: OfflineRemoval?
    /// The Books side's (#43): the books kept here first, the films and series beside them.
    private let books: Bool
    @State private var booksSummary = ""

    enum Tab: Hashable { case books, device, queue }

    /// On this device, or the queue when a title's Download button opens it;
    /// on the Books side, its books first.
    init(startOn tab: Tab = .device, books: Bool = false) {
        self.books = books
        _tab = State(initialValue: books && tab == .device ? .books : tab)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Downloads") {
                    Text(summary)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .monospacedDigit()
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                HStack(spacing: 8) {
                    if books {
                        ChoicePill(title: "Books", selected: tab == .books, pad: "books") { tab = .books }
                            .accessibilityIdentifier("downloads-books")
                    }
                    ChoicePill(title: books ? "Films and TV" : "On this device", selected: tab == .device, pad: "device") {
                        tab = .device
                    }
                    .accessibilityIdentifier("downloads-device")
                    ChoicePill(title: offline.coming > 0 ? "Queue · \(offline.coming)" : "Queue", selected: tab == .queue,
                               pad: "queue") {
                        tab = .queue
                    }
                    .accessibilityIdentifier("downloads-queue")
                }
                // A controller's row of the three (#46); what is under them it finds by looking down.
                .padGroup("tabs", .row, members: (books ? ["books"] : []) + ["device", "queue"])
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                switch tab {
                case .books: KeptBooksView(summary: $booksSummary)
                case .device: catalog
                case .queue: queue
                }
            }
            .padding(.bottom, 28)
        }
        .padPage(books ? "downloads-books" : "downloads")
        .task { offline.syncSoon() }
        // A download's notification tapped: the queue for a failure, the device for one done (#43).
        .onChange(of: taps.opening, initial: true) { _, _ in
            if let wanted = taps.take(stack: stack) { tab = wanted }
        }
        .offlineRemoval($removing)
    }

    /// "4 titles · 6.2 GB on this device · 120 GB free"; on the Books tab, its books.
    private var summary: String {
        if tab == .books {
            return [booksSummary, "\(Fmt.bytes(offline.freeBytes)) free"].filter { !$0.isEmpty }.joined(separator: " · ")
        }
        let titles = offline.titles.count
        var parts: [String] = []
        if titles > 0 { parts.append(titles == 1 ? "1 title" : "\(titles) titles") }
        parts.append("\(Fmt.bytes(offline.storedBytes)) on this device")
        parts.append("\(Fmt.bytes(offline.freeBytes)) free")
        return parts.joined(separator: " · ")
    }

    // MARK: On this device

    @ViewBuilder private var catalog: some View {
        let groups = OfflineCatalog.byLibrary(offline.titles)
        if groups.isEmpty {
            GlassEmpty(title: "Nothing downloaded yet",
                       detail: "Download a film or a series' episodes from its page in the Library, and watch them here without the hub.")
                .padding(.horizontal, metrics.margin)
                .padding(.top, 18)
        }
        ForEach(groups, id: \.library) { group in
            VStack(alignment: .leading, spacing: 10) {
                GlassLabel(text: group.library)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.poster, maximum: metrics.poster * 1.4),
                                             spacing: metrics.gap, alignment: .top)],
                          alignment: .leading, spacing: metrics.gap) {
                    ForEach(group.entries) { entry in
                        Button {
                            openRoute(.offlineTitle(OfflineTitleRoute(key: entry.key, title: entry.title)))
                        } label: {
                            OfflinePosterCard(entry: entry, store: offline.store)
                        }
                        .buttonStyle(GlassCardStyle())
                        .contextMenu {
                            Button(role: .destructive) {
                                askRemove(entry)
                            } label: {
                                Label("Remove download", systemImage: "trash")
                            }
                        }
                        // Ⓨ is the hold's Remove download (#46).
                        .padFocusable(entry.key, ring: .card, hold: { askRemove(entry) }) {
                            openRoute(.offlineTitle(OfflineTitleRoute(key: entry.key, title: entry.title)))
                        }
                    }
                }
                .padGroup("library-\(group.library)", .grid(columns: 0), members: group.entries.map(\.key))
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 20)
        }
    }

    private func askRemove(_ entry: OfflineCatalogEntry) {
        removing = OfflineRemoval(id: entry.key, title: "Remove \(entry.title)?",
                                  detail: OfflineRemoval.detail(entry)) { offline.removeTitle(entry) }
    }

    // MARK: The queue

    @ViewBuilder private var queue: some View {
        let batches = offline.batches.filter { $0.jobs.contains { $0.state != .complete } }
        if batches.isEmpty {
            GlassEmpty(title: "Nothing on its way", detail: "Downloads show here while the PC prepares them and they come to this device.")
                .padding(.horizontal, metrics.margin)
                .padding(.top, 18)
        }
        ForEach(batches) { batch in
            OfflineBatchCard(batch: batch, offline: offline) { row in
                removing = OfflineRemoval(id: row.id, title: "Remove this download?",
                                          detail: OfflineQueueRow.title(row) + " · " + OfflineQueueLabels.figures(row)) {
                    offline.remove(row.id)
                }
            } cancel: {
                removing = OfflineRemoval(id: batch.id, title: "Cancel \(batch.title)?",
                                          detail: "The downloads not yet finished stop and go; the finished ones stay.") {
                    offline.cancelBatch(batch.id, keepFinished: true)
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
        }
        OfflineSettingsCard(offline: offline)
            .padding(.horizontal, metrics.margin)
            .padding(.top, 20)
    }
}

/// A removal from this device waiting for its answer.
struct OfflineRemoval: Identifiable {
    let id: String
    let title: String
    let detail: String
    let action: @MainActor () -> Void

    /// "2 episodes · 1.4 GB gone from this device. The library keeps it on the PC."
    static func detail(_ entry: OfflineCatalogEntry) -> String {
        let size = Fmt.bytes(entry.rows.reduce(0) { $0 + $1.totalBytes })
        let what = entry.isSeries ? "\(entry.rows.count) episode" + (entry.rows.count == 1 ? "" : "s") : "The film"
        return "\(what) · \(size) gone from this device. The library keeps it on the PC."
    }
}

extension View {
    /// Asks before a download leaves this device: Keep, the harmless answer,
    /// in the cancel role (without one iOS 26 adds a Cancel of its own), or Remove.
    func offlineRemoval(_ removing: Binding<OfflineRemoval?>) -> some View {
        alert(removing.wrappedValue?.title ?? "", isPresented: Binding(get: { removing.wrappedValue != nil },
                                                                     set: { if !$0 { removing.wrappedValue = nil } }),
              presenting: removing.wrappedValue) { removal in
            Button("Keep", role: .cancel) { removing.wrappedValue = nil }
            Button("Remove", role: .destructive) {
                removing.wrappedValue = nil
                removal.action()
            }
        } message: { removal in
            Text(removal.detail)
        }
    }
}

/// A film or a series on this device: its poster from the device, its name,
/// and how many episodes and how much room.
struct OfflinePosterCard: View {
    let entry: OfflineCatalogEntry
    let store: OfflineStore
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .overlay {
                    if let row = entry.rows.first {
                        OfflineArtwork(file: store.artworkFile(row, kind: "poster"), fallback: row.manifest.item.poster, width: 360)
                    }
                }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .litArtwork(corner: metrics.radius)
            CardCaption(title: entry.title, detail: caption)
        }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var caption: String {
        let size = Fmt.bytes(entry.rows.reduce(0) { $0 + $1.totalBytes })
        guard entry.isSeries else { return size }
        return (entry.rows.count == 1 ? "1 episode" : "\(entry.rows.count) episodes") + " · " + size
    }
}

/// A picture kept beside a download, read from the device, or the hub's while
/// it has not arrived.
struct OfflineArtwork: View {
    let file: URL
    let fallback: String
    let width: Int
    @State private var image: DecodedArtwork?

    var body: some View {
        Group {
            if let image {
                Color.placeholder.overlay {
                    Image(decorative: image.image, scale: 1).resizable().scaledToFill()
                }
                .clipped()
            } else if FileManager.default.fileExists(atPath: file.path) {
                Color.placeholder
            } else {
                ArtworkView(path: fallback, width: width)
            }
        }
        .task(id: file) {
            let key = "file:" + file.path
            if let cached = ArtworkMemory.shared.get(key) {
                image = cached
                return
            }
            let pixels = width * 2
            let path = file
            let decoded = await Task.detached(priority: .userInitiated) { () -> DecodedArtwork? in
                guard let data = try? Data(contentsOf: path) else { return nil }
                return decodeArtwork(data, maxPixels: pixels)
            }.value
            guard let decoded else { return }
            ArtworkMemory.shared.set(key, decoded)
            image = decoded
        }
    }
}

/// A batch in the queue: its name and how far it is, Pause all or Resume
/// all and Cancel, then each download in its order.
struct OfflineBatchCard: View {
    let batch: OfflineBatch
    let offline: OfflineLibrary
    let remove: (OfflineRow) -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(batch.title)
                        .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(OfflineQueueLabels.batchLine(batch))
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.62))
                        .monospacedDigit()
                }
                Spacer(minLength: 8)
                Button(batch.paused ? "Resume all" : "Pause all") {
                    if batch.paused { offline.resumeBatch(batch.id) } else { offline.pauseBatch(batch.id) }
                }
                .buttonStyle(GlassControlStyle())
                .padFocusable("batch-\(batch.id)-pause") {
                    if batch.paused { offline.resumeBatch(batch.id) } else { offline.pauseBatch(batch.id) }
                }
                Button("Cancel", action: cancel)
                    .buttonStyle(GlassControlStyle())
                    .padFocusable("batch-\(batch.id)-cancel", press: cancel)
            }
            ForEach(batch.jobs) { row in
                OfflineQueueRow(row: row, offline: offline) { remove(row) }
            }
        }
        .padding(16)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

/// One download: its state as a chip by its name, the figures, a bar (the
/// PC's progress in a quieter tone while it makes the MP4), what will not
/// come across, why it waits or failed, and what can be done.
struct OfflineQueueRow: View {
    let row: OfflineRow
    let offline: OfflineLibrary
    let remove: () -> Void

    /// "S1E4 · Pilot" for an episode, the film's name for a film.
    static func title(_ row: OfflineRow) -> String {
        let item = row.manifest.item
        guard !item.seriesId.isEmpty else { return item.title }
        return EpisodeLabel.of(season: item.seasonNumber, episode: item.indexNumber, title: item.title)
    }

    private var tone: Color {
        switch OfflineQueueLabels.chip(row.state).tone {
        case .good: .available
        case .waiting: .pending
        case .bad: .failed
        case .quiet: .white.opacity(0.75)
        }
    }

    var body: some View {
        let chip = OfflineQueueLabels.chip(row.state)
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(Self.title(row))
                    .font(HubType.body(14.5, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                Spacer(minLength: 6)
                Text(chip.word)
                    .font(HubType.chrome(11.5, weight: .bold))
                    .foregroundStyle(tone)
                    .padding(.horizontal, 9)
                    .padding(.vertical, 4)
                    .background(tone.opacity(0.16), in: Capsule())
                    .fixedSize()
            }
            if row.state != .complete {
                TransferBar(fraction: OfflineQueueLabels.fraction(row), tint: row.state == .preparing ? .white.opacity(0.55) : .white)
            }
            Text(OfflineQueueLabels.figures(row))
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.66))
                .monospacedDigit()
            if let apple = row.manifest.apple, row.state != .complete {
                ForEach(OfflineAppleNotes.lines(apple), id: \.self) { line in
                    Text(line)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.55))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if !row.error.isEmpty, row.state == .waiting || row.state == .failed {
                Text(row.error)
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(row.state == .failed ? Color.dangerText : Color.pending)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack(spacing: 8) {
                switch row.state {
                case .queued, .downloading, .preparing, .waiting:
                    Button("Pause") { offline.pause(row.id) }.buttonStyle(GlassControlStyle())
                        .padFocusable("row-\(row.id)-step") { offline.pause(row.id) }
                case .paused:
                    Button("Resume") { offline.resume(row.id) }.buttonStyle(GlassControlStyle())
                        .padFocusable("row-\(row.id)-step") { offline.resume(row.id) }
                case .failed:
                    Button("Retry") { offline.retry(row.id) }.buttonStyle(GlassControlStyle())
                        .padFocusable("row-\(row.id)-step") { offline.retry(row.id) }
                case .complete:
                    EmptyView()
                }
                Button("Remove", action: remove).buttonStyle(GlassControlStyle())
                    .padFocusable("row-\(row.id)-remove", press: remove)
            }
            .padding(.top, 2)
        }
        .padding(.vertical, 10)
        .overlay(alignment: .top) { Rectangle().fill(.white.opacity(0.1)).frame(height: 1) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("offline-row-" + row.id)
    }
}

/// How downloads behave on this device: Wi-Fi only, and the room they take.
struct OfflineSettingsCard: View {
    @Bindable var offline: OfflineLibrary

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Toggle(isOn: $offline.wifiOnly) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Wait for Wi-Fi")
                        .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                    Text("Downloads stop on mobile data and a hotspot, and go on once Wi-Fi is back.")
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .tint(.white.opacity(0.6))
            .padFocusable("wifi-only", ring: .rounded(12)) { offline.wifiOnly.toggle() }
            Text("Kept on this device and out of its backups. The PC makes an MP4 of each for this device first; a converted picture takes a few minutes.")
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.55))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

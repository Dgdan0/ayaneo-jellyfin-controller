import HubKit
import SwiftUI

/// A downloaded film, or a series with the episodes on this device, on its
/// own page (Android's local series screen): played from the files, with no
/// hub. A series lists its seasons from the episodes that have arrived only;
/// each plays from where it was left here, and goes from this device on its own.
struct OfflineTitleView: View {
    let route: OfflineTitleRoute
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.play) private var play
    @State private var offline = OfflineLibrary.shared
    @State private var removing: OfflineRemoval?

    private var entry: OfflineCatalogEntry? { offline.titles.first { $0.key == route.key } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: entry?.title ?? route.title) {
                    Text(summary)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                        .monospacedDigit()
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                if let entry {
                    if entry.isSeries {
                        series(entry)
                    } else {
                        film(entry)
                    }
                } else {
                    GlassEmpty(title: "Not on this device", detail: "Its download was removed. The library keeps it on the PC.")
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 18)
                }
            }
            .padding(.bottom, 28)
        }
        .offlineRemoval($removing)
    }

    /// "On this device · 3 episodes · 1.4 GB".
    private var summary: String {
        guard let entry else { return "" }
        let size = Fmt.bytes(entry.rows.reduce(0) { $0 + $1.totalBytes })
        guard entry.isSeries else { return "On this device · " + size }
        return "On this device · " + (entry.rows.count == 1 ? "1 episode" : "\(entry.rows.count) episodes") + " · " + size
    }

    // MARK: A film

    private func film(_ entry: OfflineCatalogEntry) -> some View {
        let row = entry.rows[0]
        let saved = offline.progress([row.itemId])[row.itemId]
        return HStack(alignment: .top, spacing: 20) {
            Color.clear
                .aspectRatio(2 / 3, contentMode: .fit)
                .frame(width: metrics.small ? 120 : 170)
                .overlay {
                    OfflineArtwork(file: offline.store.artworkFile(row, kind: "poster"), fallback: row.manifest.item.poster,
                                   width: 360)
                }
                .clipShape(RoundedRectangle(cornerRadius: metrics.radius, style: .continuous))
                .litArtwork(corner: metrics.radius)
            VStack(alignment: .leading, spacing: 12) {
                if let saved, !saved.isComplete, saved.resumePosition > 0 {
                    TransferBar(fraction: saved.durationMillis > 0
                                ? Double(saved.positionMillis) / Double(saved.durationMillis) : 0)
                        .frame(maxWidth: 220)
                }
                HStack(spacing: 10) {
                    Button {
                        play(PlayRequest(itemId: row.itemId, mode: .resume, title: entry.title))
                    } label: {
                        Label(playWords(saved), systemImage: "play.fill")
                    }
                    .buttonStyle(PrimaryPillStyle())
                    .accessibilityIdentifier("offline-play")
                    Button {
                        removing = OfflineRemoval(id: entry.key, title: "Remove \(entry.title)?",
                                                  detail: OfflineRemoval.detail(entry)) { offline.removeTitle(entry) }
                    } label: {
                        Label("Remove", systemImage: "trash")
                    }
                    .buttonStyle(GlassPillStyle())
                }
                ForEach(row.manifest.apple.map(OfflineAppleNotes.lines) ?? [], id: \.self) { line in
                    Text(line)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(.top, 6)
        }
        .padding(.horizontal, metrics.margin)
        .padding(.top, 20)
    }

    /// "Resume · 17:12" from where it was left here, else "Play".
    private func playWords(_ saved: OfflineCatalogProgress?) -> String {
        guard let saved, !saved.isComplete, saved.resumePosition > 0 else { return "Play" }
        return "Resume · " + Fmt.clock(millis: saved.resumePosition)
    }

    // MARK: A series

    @ViewBuilder private func series(_ entry: OfflineCatalogEntry) -> some View {
        let saved = offline.progress(entry.rows.map(\.itemId))
        if let target = OfflineCatalog.playTarget(entry.rows, progress: saved) {
            let item = target.row.manifest.item
            let code = EpisodeLabel.code(season: item.seasonNumber, episode: item.indexNumber)
            Button {
                play(PlayRequest(itemId: target.row.itemId, mode: .resume, title: entry.title))
            } label: {
                Label(target.kind == .resume ? "Resume \(code)" : "Play \(code)", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryPillStyle())
            .accessibilityIdentifier("offline-play")
            .padding(.horizontal, metrics.margin)
            .padding(.top, 18)
        }
        ForEach(OfflineCatalog.seasons(seriesId: entry.key, entry.rows)) { season in
            VStack(alignment: .leading, spacing: 10) {
                GlassLabel(text: season.title)
                VStack(spacing: 0) {
                    ForEach(season.rows) { row in
                        episode(row, entry: entry, saved: saved[row.itemId])
                    }
                }
                .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 22)
        }
    }

    private func episode(_ row: OfflineRow, entry: OfflineCatalogEntry, saved: OfflineCatalogProgress?) -> some View {
        let item = row.manifest.item
        return Button {
            play(PlayRequest(itemId: row.itemId, mode: .resume, title: entry.title))
        } label: {
            HStack(spacing: 14) {
                Color.clear
                    .aspectRatio(16 / 9, contentMode: .fit)
                    .frame(width: metrics.small ? 104 : 132)
                    .overlay {
                        OfflineArtwork(file: offline.store.artworkFile(row, kind: "thumb"), fallback: item.thumb, width: 300)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(EpisodeLabel.of(season: item.seasonNumber, episode: item.indexNumber, title: item.title))
                        .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Text(episodeLine(row, saved: saved))
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                        .monospacedDigit()
                    if let saved, !saved.isComplete, saved.durationMillis > 0, saved.positionMillis > 0 {
                        TransferBar(fraction: Double(saved.positionMillis) / Double(saved.durationMillis))
                            .frame(maxWidth: 160)
                    }
                }
                Spacer(minLength: 8)
                Image(systemName: saved?.isComplete == true ? "checkmark.circle.fill" : "play.circle")
                    .font(.system(size: 22, weight: .regular))
                    .foregroundStyle(.white.opacity(0.8))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("offline-episode-" + row.itemId)
        .contextMenu {
            Button(role: .destructive) {
                removing = OfflineRemoval(id: row.id, title: "Remove this episode?",
                                          detail: OfflineQueueRow.title(row) + " · \(Fmt.bytes(row.totalBytes)) gone from this device.") {
                    offline.remove(row.id)
                }
            } label: {
                Label("Remove download", systemImage: "trash")
            }
        }
    }

    /// "1.2 GB", "1.2 GB · watched", "1.2 GB · 17:12 left".
    private func episodeLine(_ row: OfflineRow, saved: OfflineCatalogProgress?) -> String {
        var parts = [Fmt.bytes(row.totalBytes)]
        if let saved {
            if saved.isComplete {
                parts.append("watched")
            } else if saved.positionMillis > 0, saved.durationMillis > saved.positionMillis {
                parts.append(Fmt.clock(millis: saved.durationMillis - saved.positionMillis) + " left")
            }
        }
        return parts.joined(separator: " · ")
    }
}

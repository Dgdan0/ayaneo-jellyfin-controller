import HubKit
import SwiftUI

/// A downloaded film, or a series with the episodes on this device, on its
/// own page (Android's local series screen): the same shape as the library's
/// title page (`TitlePage`, `TitleHeader`, the seasons and the strip of
/// episode cards), played from the files, with no hub. A series lists its
/// seasons from the episodes that have arrived only; its facts and overview
/// are what was kept of the series when it was downloaded
/// (`OfflineSeriesSnapshot`), and without them the page is its name and its
/// episodes. Each plays from where it was left here, and goes from this
/// device on its own.
struct OfflineTitleView: View {
    let route: OfflineTitleRoute
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.play) private var play
    @Environment(\.openRoute) private var openRoute
    @State private var offline = OfflineLibrary.shared
    @State private var removing: OfflineRemoval?
    /// The round "…"'s choices, for a controller's Ⓐ on it (#46).
    /// The season chosen on its pill; until one is, the season Play is in.
    @State private var chosenSeason = ""

    private var entry: OfflineCatalogEntry? { offline.titles.first { $0.key == route.key } }

    var body: some View {
        Group {
            if let entry {
                page(entry)
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        PageHeading(title: route.title) { EmptyView() }
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 4)
                        GlassEmpty(title: "Not on this device", detail: "Its download was removed. The library keeps it on the PC.")
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 18)
                    }
                }
            }
        }
        .offlineRemoval($removing)
        // The series' own words, if the hub can be asked for them now.
        .task { offline.keepSeriesSoon() }
    }

    // MARK: The page

    private func page(_ entry: OfflineCatalogEntry) -> some View {
        let item = facts(entry)
        // A controller goes down the page as on the library's (#46): Read more, the
        // actions, then a series' seasons and episodes.
        let column = (item.overview.isEmpty ? [] : ["read-more"]) + ["actions"] + (entry.isSeries ? ["seasons", "episodes"] : [])
        return TitlePage(backdrop: FadedArtwork.titleFade { backdrop(entry) }, pad: "offline-title:\(entry.key)",
                         padColumn: column) { page in
            TitleHeader(title: entry.title, originalTitle: item.originalTitle, page: page, facts: DetailLines.facts(item),
                        state: summary(entry), overview: item.overview) {
                EmptyView()
            } actions: {
                actions(entry).padding(.top, 4)
            }
        } below: {
            if entry.isSeries {
                seasons(entry)
            } else {
                notes(entry.rows[0])
            }
        }
        .ambientArtwork(artworkPath(entry))
    }

    /// The title as an item, for its facts and overview: a film's own, the
    /// series' as it was kept; a series with none kept says only its name.
    private func facts(_ entry: OfflineCatalogEntry) -> HubKit.LibraryItem {
        guard entry.isSeries else { return entry.rows[0].manifest.item }
        return offline.seriesSnapshot(entry.key)?.item ?? HubKit.LibraryItem(id: entry.key, type: "series", title: entry.title)
    }

    /// The backdrop: the series' own picture kept beside its downloads, else
    /// the first episode's or the film's; the hub's, while none has come.
    private func backdrop(_ entry: OfflineCatalogEntry) -> some View {
        let kept = entry.isSeries ? offline.store.series.artworkFile(entry.key, kind: "backdrop") : nil
        let row = entry.rows.first(where: { FileManager.default.fileExists(atPath: offline.store.artworkFile($0, kind: "backdrop").path) })
            ?? entry.rows[0]
        let file = kept.flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil }
            ?? offline.store.artworkFile(row, kind: "backdrop")
        return OfflineArtwork(file: file, fallback: artworkPath(entry), width: 1280, placeholder: .clear)
    }

    /// A picture of it on the hub, for the Glass page's colour and a backdrop not kept yet.
    private func artworkPath(_ entry: OfflineCatalogEntry) -> String {
        let item = entry.rows[0].manifest.item
        let snapshot = entry.isSeries ? offline.seriesSnapshot(entry.key) : nil
        return [snapshot?.backdrop ?? "", item.backdrop, snapshot?.poster ?? "", item.poster].first { !$0.isEmpty } ?? ""
    }

    /// "On this device · 3 episodes · 1.4 GB".
    private func summary(_ entry: OfflineCatalogEntry) -> String {
        let size = Fmt.bytes(entry.rows.reduce(0) { $0 + $1.totalBytes })
        guard entry.isSeries else { return "On this device · " + size }
        return "On this device · " + (entry.rows.count == 1 ? "1 episode" : "\(entry.rows.count) episodes") + " · " + size
    }

    // MARK: The actions

    /// What Play goes on with, as the page and the player play it: a series'
    /// episode left part watched, else the next to watch, else the first.
    private func target(_ entry: OfflineCatalogEntry) -> OfflineCatalogPlayTarget? {
        let saved = offline.progress(entry.rows.map(\.itemId))
        if entry.isSeries { return OfflineCatalog.playTarget(entry.rows, progress: saved) }
        return OfflineCatalog.filmTarget(entry.rows[0], progress: saved[entry.rows[0].itemId])
    }

    /// Play, then Remove as a round glass button, then more.
    private func actions(_ entry: OfflineCatalogEntry) -> some View {
        let target = target(entry)
        let size: CGFloat = metrics.small ? 42 : 46
        return HStack(spacing: metrics.small ? 8 : 10) {
            Button {
                play(PlayRequest(itemId: (target?.row ?? entry.rows[0]).itemId, mode: .resume, title: entry.title))
            } label: {
                Label(playWords(entry, target), systemImage: "play.fill")
            }
            .buttonStyle(PrimaryPillStyle())
            .accessibilityIdentifier("offline-play")
            .padFocusable("play") {
                play(PlayRequest(itemId: (target?.row ?? entry.rows[0]).itemId, mode: .resume, title: entry.title))
            }
            GlassRoundButton(systemImage: "trash", label: "Remove", size: size, pad: "remove") {
                removing = OfflineRemoval(id: entry.key, title: "Remove \(entry.title)?",
                                          detail: OfflineRemoval.detail(entry)) { offline.removeTitle(entry) }
            }
            .accessibilityIdentifier("offline-remove")
            more(entry, target: target, size: size)
        }
        .padGroup("actions", .row, members: ["play", "remove", "more"], prefix: false)
    }

    /// "Resume S1E4", "Play S1E1" for a series; "Resume · 17:12" or "Play" for a film.
    private func playWords(_ entry: OfflineCatalogEntry, _ target: OfflineCatalogPlayTarget?) -> String {
        guard entry.isSeries else {
            guard let target, target.kind == .resume else { return "Play" }
            return "Resume · " + Fmt.clock(millis: target.positionMillis)
        }
        let item = (target?.row ?? entry.rows[0]).manifest.item
        let code = EpisodeLabel.code(season: item.seasonNumber, episode: item.indexNumber)
        return (target?.kind == .resume ? "Resume" : "Play") + (code.isEmpty ? "" : " " + code)
    }

    /// The round "…": start over what is half watched, and the title's page on the hub.
    private func more(_ entry: OfflineCatalogEntry, target: OfflineCatalogPlayTarget?, size: CGFloat) -> some View {
        Menu {
            PadChoicesMenu(choices: moreChoices(entry, target: target))
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .glassPanel(Circle())
                .contentShape(Circle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("More actions")
        .accessibilityIdentifier("offline-more")
        // A menu cannot be opened for a controller: Ⓐ shows its choices as rows the ring walks (#46).
        .padFocusable("more", ring: .circle) {
            PadFocusCenter.shared.present(PadMenu(title: "More actions for \(entry.title)",
                                                  choices: moreChoices(entry, target: target)))
        }
    }

    /// The "…"'s choices, in its menu and in the panel a controller opens.
    private func moreChoices(_ entry: OfflineCatalogEntry, target: OfflineCatalogPlayTarget?) -> [PadChoice] {
        var out: [PadChoice] = []
        if let target, target.kind == .resume {
            out.append(PadChoice(id: "start-over", title: "Start over", systemImage: "arrow.counterclockwise") {
                play(PlayRequest(itemId: target.row.itemId, mode: .restart, title: entry.title))
            })
        }
        out.append(PadChoice(id: "hub", title: "Open on the hub", systemImage: "arrow.up.right.square") {
            openRoute(.title(TitleRoute(itemId: entry.key, title: entry.title)))
        })
        return out
    }

    // MARK: A film

    /// What was left out of its MP4, in words.
    @ViewBuilder private func notes(_ row: OfflineRow) -> some View {
        let lines = row.manifest.apple.map(OfflineAppleNotes.lines) ?? []
        if !lines.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                ForEach(lines, id: \.self) { line in
                    Text(line)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.6))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: 620, alignment: .leading)
            .padding(.horizontal, metrics.margin)
            .padding(.top, 16)
        }
    }

    // MARK: A series

    @ViewBuilder private func seasons(_ entry: OfflineCatalogEntry) -> some View {
        let saved = offline.progress(entry.rows.map(\.itemId))
        let all = OfflineCatalog.seasons(seriesId: entry.key, entry.rows)
        let target = OfflineCatalog.playTarget(entry.rows, progress: saved)
        let inPlay = target.flatMap { target in all.first { $0.rows.contains { $0.id == target.row.id } } }
        let shown = all.first { $0.key == chosenSeason } ?? inPlay ?? all.first
        VStack(alignment: .leading, spacing: 0) {
            SeasonPills(all.map { SeasonPill(id: $0.key, title: seasonTitle($0, shown: $0.key == shown?.key), selected: $0.key == shown?.key) },
                        choose: { chosenSeason = $0 }, menu: { _ in EmptyView() })
            if let shown {
                EpisodeStrip(shown.rows, reveal: target?.row.id,
                             play: { row in play(PlayRequest(itemId: row.itemId, mode: .resume, title: entry.title)) },
                             hint: { (saved[$0.itemId]?.resumePosition ?? 0) > 0 ? "Resumes the episode" : "Plays the episode" },
                             identifier: { "offline-episode-" + $0.itemId },
                             card: { row in card(row, saved: saved[row.itemId], upNext: row.id == target?.row.id) },
                             menu: { row in
                                 Button(role: .destructive) {
                                     removing = OfflineRemoval(id: row.id, title: "Remove this episode?",
                                                               detail: OfflineQueueRow.title(row) + " · \(Fmt.bytes(row.totalBytes)) gone from this device.") {
                                         offline.remove(row.id)
                                     }
                                 } label: {
                                     Label("Remove download", systemImage: "trash")
                                 }
                             })
            }
        }
    }

    /// "Season 2 · 3 episodes" on the one shown, as the library's page says it.
    private func seasonTitle(_ season: OfflineCatalogSeason, shown: Bool) -> String {
        shown ? season.title + " · " + (season.rows.count == 1 ? "1 episode" : "\(season.rows.count) episodes") : season.title
    }

    /// An episode on the device: its still, and what is on the device under it, as the library's card says what is on the hub.
    private func card(_ row: OfflineRow, saved: OfflineCatalogProgress?, upNext: Bool) -> some View {
        let item = row.manifest.item
        let part = saved.flatMap { watch in
            !watch.isComplete && watch.positionMillis > 0 && watch.durationMillis > 0
                ? Double(watch.positionMillis) / Double(watch.durationMillis) : nil
        } ?? 0
        return EpisodeCard(title: DetailLines.episodeTitle(item), detail: episodeLine(row, saved: saved),
                           played: saved?.isComplete == true, progress: part, upNext: upNext) {
            OfflineArtwork(file: offline.store.artworkFile(row, kind: "thumb"), fallback: item.thumb, width: 480)
        } badge: {
            // On this device, as the library's page draws it: the download mark, never a tick (a tick is watched).
            DownloadedMark(size: 26)
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

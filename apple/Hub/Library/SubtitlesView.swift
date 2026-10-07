import HubKit
import SwiftUI

/// A film's or an episode's subtitles (#34; Android `SubtitleScreen`): the
/// tracks installed and what Bazarr once downloaded, a search of the
/// providers that lists what it found (kept to one language on a pill), and
/// the download of one. Jellyfin is asked to look again, so the player lists a
/// new subtitle.
struct SubtitlesRoute: Hashable {
    let itemId: String
    let title: String
}

struct SubtitlesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let route: SubtitlesRoute

    @State private var state: SubtitleState?
    /// A search's results while they are shown in place of the lists.
    @State private var candidates: [SubtitleCandidate]?
    @State private var language: String?
    /// The ticket of the candidate opened for its details.
    @State private var chosen: String?
    @State private var status = StatusMessage("")
    /// What the last download or refresh did, kept over the next load's status.
    @State private var notice = StatusMessage("")
    @State private var working = false
    @State private var loads = 0
    #if DEBUG
    @State private var debugSearched = false
    #endif

    private var shownStatus: StatusMessage {
        if !notice.text.isEmpty { return notice }
        if !status.text.isEmpty { return status }
        return StatusMessage(candidates == nil ? "" : SubtitleLines.legend)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Subtitles") {
                    Text(route.title)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                Text(state.map { SubtitleLines.library(installed: SubtitleLines.installed($0).count) }
                     ?? "Library · checking subtitles…")
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.8))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassPanel(Capsule())
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 12)
                    .accessibilityIdentifier("subtitles-library")
                HStack(spacing: 10) {
                    if working { ProgressView().controlSize(.small).tint(.white) }
                    StatusLine(message: shownStatus) { loads += 1 }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 10)
                if let results = candidates {
                    found(results)
                } else if let state {
                    installed(state)
                }
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork("")
        .refreshable { loads += 1 }
        .task(id: "\(route.itemId)·\(model.userId)·\(loads)") { await load() }
        #if DEBUG
        // scripts/mac.sh: HUB_SUBTITLES=search searches once the page has read its tracks, and opens the first result.
        .task(id: state != nil) {
            guard state != nil, !debugSearched, ProcessInfo.processInfo.environment["HUB_SUBTITLES"] == "search" else { return }
            debugSearched = true
            await search()
            chosen = candidates?.first?.id
        }
        #endif
    }

    // MARK: The lists

    private func installed(_ state: SubtitleState) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                if state.canDownload {
                    Button {
                        Task { await search() }
                    } label: {
                        Label("Search subtitle providers", systemImage: "magnifyingglass")
                    }
                    .buttonStyle(PrimaryPillStyle())
                    .disabled(working)
                    .accessibilityIdentifier("subtitles-search")
                    Button {
                        Task { await refresh() }
                    } label: {
                        Label("Refresh subtitles", systemImage: "arrow.clockwise")
                    }
                    .buttonStyle(GlassPillStyle())
                    .disabled(working)
                    .accessibilityIdentifier("subtitles-refresh")
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            if !state.canDownload {
                Text(SubtitleLines.readOnly)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.7))
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
            }
            records("Installed", SubtitleLines.installed(state), empty: SubtitleLines.noInstalled)
            records("Download history", SubtitleLines.history(state), empty: SubtitleLines.noHistory)
        }
    }

    private func records(_ heading: String, _ list: [SubtitleRecord], empty: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(heading)
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
                .padding(.top, 22)
            if list.isEmpty {
                Text(empty)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.66))
            }
            ForEach(list) { record in
                RecordRow(record: record)
            }
        }
        .padding(.horizontal, metrics.margin)
    }

    private func found(_ results: [SubtitleCandidate]) -> some View {
        let shown = SubtitleLines.filtered(results, language: language)
        return VStack(alignment: .leading, spacing: 0) {
            Button {
                candidates = nil
                chosen = nil
            } label: {
                Label("Installed subtitles and history", systemImage: "chevron.left")
            }
            .buttonStyle(GlassControlStyle())
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
            .accessibilityIdentifier("subtitles-back")
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ChoicePill(title: "All languages", selected: language == nil) { language = nil }
                    ForEach(SubtitleLines.languages(results), id: \.self) { code in
                        ChoicePill(title: SubtitleLines.language(code), selected: language == code) { language = code }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                .padding(.bottom, 2)
            }
            if shown.isEmpty {
                GlassEmpty(title: "No subtitles", detail: SubtitleLines.nothingFound)
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 14)
            }
            LazyVStack(spacing: 8) {
                ForEach(shown) { candidate in
                    CandidateRow(candidate: candidate, open: chosen == candidate.id, busy: working,
                                 toggle: { chosen = chosen == candidate.id ? nil : candidate.id },
                                 download: { Task { await download(candidate) } })
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 14)
        }
    }

    // MARK: Loading and acting

    private func load() async {
        status = StatusText.loading("subtitles", refreshing: state != nil)
        do {
            let read = try await model.hub.fetch(HubEndpoints.subtitles(itemId: route.itemId), as: SubtitleState.self)
            state = read
            status = read.warning.isEmpty ? StatusMessage("") : StatusMessage(read.warning, tone: .warning)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: state != nil)
        }
    }

    private func search() async {
        guard !working else { return }
        working = true
        defer { working = false }
        notice = StatusMessage("")
        status = StatusMessage(SubtitleLines.searching)
        do {
            let found = try await model.hub.fetch(HubEndpoints.searchSubtitles(itemId: route.itemId), as: SubtitleSearch.self)
            candidates = found.candidates
            language = nil
            chosen = nil
            status = StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }

    /// Sent once. Whatever it answers the results are done with: the ticket is
    /// spent, and a new search gives new ones.
    private func download(_ candidate: SubtitleCandidate) async {
        guard !working else { return }
        working = true
        defer { working = false }
        notice = StatusMessage("")
        status = StatusMessage(SubtitleLines.downloading)
        do {
            let ack = try await model.hub.fetch(HubEndpoints.downloadSubtitle(itemId: route.itemId, ticket: candidate.ticket),
                                                as: SubtitleDownloadAck.self)
            candidates = nil
            chosen = nil
            notice = StatusMessage(SubtitleLines.downloaded(ack), tone: ack.warning.isEmpty && ack.jellyfinRefreshStarted ? .normal : .warning)
            status = StatusMessage("")
            loads += 1
        } catch {
            candidates = nil
            chosen = nil
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }

    private func refresh() async {
        guard !working else { return }
        working = true
        defer { working = false }
        notice = StatusMessage("")
        status = StatusMessage(SubtitleLines.refreshing)
        do {
            _ = try await model.hub.fetch(HubEndpoints.refreshSubtitles(itemId: route.itemId), as: ActionAck.self)
            notice = StatusMessage(SubtitleLines.refreshed)
            status = StatusMessage("")
            loads += 1
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }
}

/// A track of the file or one Bazarr once fetched: "Hebrew · SDH · OpenSubtitles"
/// over its score and when it was saved.
private struct RecordRow: View {
    let record: SubtitleRecord

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(SubtitleLines.recordTitle(record))
                .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                .foregroundStyle(.white)
            Text(SubtitleLines.recordScore(record))
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.62))
            let detail = SubtitleLines.recordDetail(record)
            if !detail.isEmpty {
                Text(detail)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.52))
                    .lineLimit(2)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// One subtitle a search found. Opened, it says what matched and what did
/// not, and offers the download.
private struct CandidateRow: View {
    let candidate: SubtitleCandidate
    let open: Bool
    let busy: Bool
    let toggle: () -> Void
    let download: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Button(action: toggle) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(SubtitleLines.candidateTitle(candidate))
                            .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.leading)
                        Text(SubtitleLines.release(candidate))
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.62))
                            .lineLimit(2)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 0)
                    Image(systemName: open ? "chevron.up" : "chevron.down")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.5))
                        .padding(.top, 3)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityValue(open ? "Open" : "")
            if open {
                VStack(alignment: .leading, spacing: 6) {
                    GlassLabel(text: "Matches")
                    Text(SubtitleLines.matches(candidate))
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                    GlassLabel(text: "Doesn't match")
                        .padding(.top, 4)
                    Text(SubtitleLines.mismatches(candidate))
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                    Text(SubtitleLines.overwrite)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.6))
                        .padding(.top, 4)
                }
                .accessibilityElement(children: .combine)
                Button(action: download) {
                    Label("Download this subtitle", systemImage: "arrow.down.circle")
                }
                .buttonStyle(PrimaryPillStyle())
                .disabled(busy)
                .accessibilityHint(SubtitleLines.downloadDetail(candidate))
                .accessibilityIdentifier("subtitle-download")
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

import HubKit
import SwiftUI

/// The round Download button by a title's Play (#5; Android's download
/// action): a film or an episode asks once and is queued; a series opens the
/// smart choices (`openChoices`, #48), and carries the number while Keep ready
/// is on. Its ring fills while the download is on its way; once here it is
/// filled, and a press offers the page on this device or removing it. A season
/// has none (its series picks its episodes).
struct DownloadButton: View {
    let item: HubKit.LibraryItem
    var size: CGFloat = 46
    /// A series' press: the panel of choices.
    var openChoices: (() -> Void)?
    @Environment(\.openRoute) private var openRoute
    @Environment(\.glassAccent) private var accent
    @State private var offline = OfflineLibrary.shared
    @State private var asking = false
    @State private var choosing = false
    @State private var removing: OfflineRemoval?
    @State private var problem: String?

    /// The film's or episode's download; a series' episodes'.
    private var state: OfflineTitleState {
        if item.type == "series" {
            return OfflineTitleState.of(series: offline.batches.flatMap(\.jobs).filter { $0.manifest.item.seriesId == item.id })
        }
        return OfflineTitleState.of(offline.row(forItem: item.id))
    }

    var body: some View {
        // A series' button is the way to the choices, never "done": some of its episodes on the device is
        // not the series on the device.
        let state = item.type == "series" && state == .downloaded ? OfflineTitleState.none : state
        Button {
            press(state)
        } label: {
            ZStack {
                if case .coming(let fraction) = state {
                    Circle()
                        .trim(from: 0, to: max(0.03, fraction))
                        .stroke(.white, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .padding(3)
                }
                if state == .downloaded {
                    // On the device: the download mark, never a tick (a tick is watched).
                    DownloadedMark(size: size)
                } else {
                    Image(systemName: icon(state))
                        .font(.system(size: size * 0.38, weight: .semibold))
                        .foregroundStyle(.white)
                }
            }
            .frame(width: size, height: size)
            .glassPanel(Circle())
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(state.label)
        .accessibilityValue(item.type == "series" ? offline.keepReadyCount(item.id).map { "Keeping \($0) ready" } ?? "" : "")
        .accessibilityIdentifier("title-download")
        .overlay(alignment: .topTrailing) {
            // Keep ready is on: how many it keeps. Beside the button, so it is read on its own.
            if item.type == "series", let kept = offline.keepReadyCount(item.id) {
                Text("\(kept)")
                    .font(HubType.chrome(10.5, weight: .bold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .frame(minWidth: 19, minHeight: 19)
                    .background(accent.tint, in: Capsule())
                    .offset(x: 4, y: -4)
                    .allowsHitTesting(false)
                    .accessibilityLabel("\(kept)")
                    .accessibilityIdentifier("keep-ready-badge")
            }
        }
        .confirmationDialog(item.title, isPresented: $choosing, titleVisibility: .visible) {
            Button("Show in Downloads") { openDownloads() }
            Button("Remove from this device", role: .destructive) { askRemove() }
        }
        .alert(OfflineTitleState.confirmTitle(item.title), isPresented: $asking) {
            // The harmless answer in the cancel role: without one, iOS 26 adds a Cancel of its own.
            Button("Not now", role: .cancel) {}
            Button("Download") { Task { await download() } }
        } message: {
            Text(OfflineTitleState.confirmDetail(free: offline.freeBytes))
        }
        .alert("Download", isPresented: Binding(get: { problem != nil }, set: { if !$0 { problem = nil } })) {
            Button("OK", role: .cancel) { problem = nil }
        } message: {
            Text(problem ?? "")
        }
        .offlineRemoval($removing)
    }

    private func icon(_ state: OfflineTitleState) -> String {
        switch state {
        case .none: "arrow.down"
        case .coming: "arrow.down"
        case .failed: "exclamationmark.arrow.circlepath"
        case .downloaded: DownloadedMark.symbol
        }
    }

    private func press(_ state: OfflineTitleState) {
        if item.type == "series" {
            if let openChoices { openChoices() } else { openRoute(.offlineQueue) }
            return
        }
        switch state {
        case .none: asking = true
        case .coming, .failed: openDownloads()
        case .downloaded: choosing = true
        }
    }

    private func openDownloads() {
        let key = item.seriesId.isEmpty ? item.id : item.seriesId
        if case .downloaded = state {
            openRoute(.offlineTitle(OfflineTitleRoute(key: key, title: item.seriesTitle.isEmpty ? item.title : item.seriesTitle)))
        } else {
            openRoute(.offlineQueue)
        }
    }

    private func askRemove() {
        guard let row = offline.row(forItem: item.id) else { return }
        removing = OfflineRemoval(id: row.id, title: "Remove \(item.title)?",
                                  detail: "\(Fmt.bytes(row.totalBytes)) gone from this device. The library keeps it on the PC.") {
            offline.remove(row.id)
        }
    }

    private func download() async {
        let title = item.seriesTitle.isEmpty ? item.title : item.seriesTitle
        problem = await offline.download(itemIds: [item.id], title: title, seriesId: item.seriesId)
    }
}

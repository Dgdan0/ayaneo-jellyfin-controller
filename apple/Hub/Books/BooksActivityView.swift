import HubKit
import SwiftUI

/// The reading side of Activity (#25; Android's `DownloadsScreen` in Books):
/// BookKeeprr's transfers grouped by kind, each with its state as a chip, a
/// white bar while it moves and its size, speed and time left, and Retry or
/// Cancel transfer where BookKeeprr offers them (Cancel asks first). It asks
/// again every two seconds while something moves, every ten when nothing
/// does, faster for a few seconds after an action, and not at all out of sight.
struct BooksActivityView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics

    @State private var items: [ReadingDownloadItem]?
    @State private var status = StatusMessage("")
    @State private var notice = ""
    @State private var cancelling: ReadingDownloadItem?
    @State private var working = false
    @State private var settleUntil: ContinuousClock.Instant?
    @State private var polls = 0

    var body: some View {
        let shown = ReadingTransferSummary.grouped(items ?? [])
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Activity") {
                    if let items {
                        Text(summary(items))
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.66))
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                StatusLine(message: shownStatus) { polls += 1 }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 8)
                if items?.isEmpty == true {
                    GlassEmpty(title: "No book transfers yet",
                               detail: "Request a book in Discover, and its download shows here.")
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 18)
                }
                ForEach(Array(shown.enumerated()), id: \.element.id) { index, item in
                    let label = ReadingTransferSummary.groupLabel(item.contentType)
                    if index == 0 || ReadingTransferSummary.groupLabel(shown[index - 1].contentType) != label {
                        RowHeading(title: label)
                            .padding(.horizontal, metrics.margin)
                            .padding(.top, 20)
                            .padding(.bottom, 8)
                    }
                    ReadingTransferRow(item: item, working: working) { action in act(action, on: item) }
                        .padding(.horizontal, metrics.margin)
                        .padding(.bottom, 10)
                }
            }
            .padding(.bottom, 28)
        }
        .refreshable { polls += 1 }
        .task(id: polls) { await poll() }
        .alert("Cancel transfer?", isPresented: Binding(get: { cancelling != nil }, set: { if !$0 { cancelling = nil } }),
               presenting: cancelling) { item in
            // The harmless answer first. iOS 26 hides a dialog's cancel role and moves an
            // alert's after the destructive answer, so it is a plain button that Escape presses.
            Button("Keep transfer") { cancelling = nil }
                .keyboardShortcut(.cancelAction)
                .accessibilityIdentifier("keep-transfer")
            Button("Cancel transfer", role: .destructive) {
                cancelling = nil
                Task { await run(.cancel, on: item) }
            }
            .accessibilityIdentifier("confirm-cancel")
        } message: { item in
            Text("\(item.title)\nStop this transfer and delete its incomplete files.")
        }
    }

    /// What an action just did, until the transfers have settled; else the page's own line.
    private var shownStatus: StatusMessage {
        notice.isEmpty ? status : StatusMessage(notice)
    }

    /// "1 downloading · 2 queued   ↓ 1.2 MB/s".
    private func summary(_ items: [ReadingDownloadItem]) -> String {
        let speed = items.reduce(Int64(0)) { $0 + $1.downloadSpeedBytesPerSecond }
        return ReadingTransferSummary.summary(items) + (speed > 0 ? "   ↓ " + Fmt.speed(speed) : "")
    }

    private func act(_ action: ReadingTransferAction, on item: ReadingDownloadItem) {
        switch action {
        case .retry: Task { await run(.retry, on: item) }
        case .cancel: cancelling = item
        }
    }

    private func run(_ action: ReadingTransferAction, on item: ReadingDownloadItem) async {
        guard !working else { return }
        working = true
        defer { working = false }
        notice = action == .retry ? "Retrying…" : "Cancelling…"
        do {
            switch action {
            case .retry: try await model.hub.send(HubEndpoints.retryReadingDownload(item.id))
            case .cancel: try await model.hub.send(HubEndpoints.cancelReadingDownload(item.id))
            }
            notice = action == .retry ? "Retrying \(item.title)" : "Cancelled \(item.title)"
        } catch {
            if error.kind == .cancelled { return }
            notice = error.message
        }
        // BookKeeprr does not change a transfer with its reply: ask fast for a while.
        settleUntil = .now + PollSchedule.settle
        polls += 1
    }

    /// Reads the transfers, then again on `PollSchedule`'s pace while the page shows.
    private func poll() async {
        var failures = 0
        while !Task.isCancelled {
            do {
                let response = try await model.hub.fetch(HubEndpoints.readingDownloads, as: ReadingDownloadsResponse.self)
                items = response.items
                failures = 0
                let failed = response.items.filter(\.failed).count
                status = StatusMessage(ReadingTransferSummary.status(response.items), tone: failed > 0 ? .error : .normal)
            } catch {
                if error.kind == .cancelled { return }
                failures += 1
                // The list stays: a failed read is the hub out of reach, not the transfers gone.
                status = StatusMessage(error.message + " · retrying", tone: .error)
            }
            let settling = settleUntil.map { .now < $0 } ?? false
            if !settling && !working { notice = "" }
            guard let delay = PollSchedule.next(active: items?.contains(where: \.isActive) ?? false, failures: failures,
                                                settling: settling) else { return }
            try? await Task.sleep(for: delay)
        }
    }
}

/// One transfer (`.xfer`): a tile with its state's mark, its title and the
/// release's own name, the state as a chip, a white bar while it moves, the
/// line of size, speed and time left, and its actions.
struct ReadingTransferRow: View {
    let item: ReadingDownloadItem
    let working: Bool
    let act: (ReadingTransferAction) -> Void

    private var tone: Color {
        switch ReadingTransferSummary.tone(item.status, failed: item.failed) {
        case .good: .available
        case .waiting: .pending
        case .bad: .failed
        case .quiet: .white.opacity(0.6)
        }
    }

    private var mark: String {
        if item.failed { return "exclamationmark" }
        switch item.status {
        case "downloading": return "arrow.down"
        case "importing": return "arrow.right"
        case "completed", "imported": return "checkmark"
        default: return "ellipsis"
        }
    }

    var body: some View {
        let moving = ReadingTransferSummary.showProgress(item.status, failed: item.failed)
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: mark)
                .font(.system(size: 16, weight: .bold))
                .foregroundStyle(tone)
                .frame(width: 42, height: 42)
                .background(tone.opacity(0.18), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.title)
                        .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                    Spacer(minLength: 6)
                    Text(ReadingTransferSummary.chipLabel(item.status, failed: item.failed))
                        .font(HubType.chrome(11.5, weight: .bold))
                        .foregroundStyle(tone)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(tone.opacity(0.16), in: Capsule())
                        .fixedSize()
                }
                if !item.releaseTitle.isEmpty && item.releaseTitle != item.title {
                    Text(item.releaseTitle)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if moving {
                    HStack(spacing: 10) {
                        GeometryReader { geometry in
                            ZStack(alignment: .leading) {
                                Capsule().fill(.white.opacity(0.22))
                                Capsule().fill(.white).frame(width: geometry.size.width * item.progress)
                            }
                        }
                        .frame(height: 4)
                        if item.progressPercent > 0 {
                            Text("\(item.progressPercent)%")
                                .font(HubType.body(12, weight: .semibold, relativeTo: .caption))
                                .foregroundStyle(.white.opacity(0.8))
                                .monospacedDigit()
                        }
                    }
                    .padding(.top, 2)
                }
                Text(ReadingTransferSummary.line(item))
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.66))
                if !item.availableActions.isEmpty {
                    HStack(spacing: 8) {
                        ForEach(item.availableActions, id: \.rawValue) { action in
                            Button(action == .retry ? "Retry" : "Cancel transfer") { act(action) }
                                .buttonStyle(GlassControlStyle())
                                .disabled(working)
                                .accessibilityIdentifier("\(action.rawValue)-\(item.id)")
                        }
                    }
                    .padding(.top, 4)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: 900, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .contain)
    }
}

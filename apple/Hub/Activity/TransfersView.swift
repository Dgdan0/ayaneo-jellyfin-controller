import HubKit
import SwiftUI

/// All the transfers, from Activity (#29).
struct TransfersRoute: Hashable {
    /// A transfer to explain on arrival: Why is this stuck? from the dashboard.
    var target = ""
}

/// The download manager (Android's `DownloadsScreen`): one list answering
/// "what is happening, and what is broken", joined by the hub across
/// qBittorrent, Radarr and Sonarr, problems first.
///
/// The page decides nothing about what may be done: each transfer's menu is
/// built from the hub's `actions`, computed from this token's scopes, so a
/// read-only token gets no buttons that would fail. Stop and Start are
/// reversible and happen on the press; everything that destroys something is
/// asked about again, the harmless answer first.
struct TransfersView: View {
    let route: TransfersRoute

    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    /// False while another section or the player is in front: nothing is asked for then.
    @Environment(\.isEnabled) private var isEnabled

    @State private var activity: ActivityResponse?
    @State private var status = StatusMessage("")
    @State private var notice = StatusMessage("")
    @State private var attentionOnly = false
    @State private var includeFinished = false
    @State private var working = false
    @State private var settleUntil: ContinuousClock.Instant?
    @State private var polls = 0
    @State private var confirming: Pending?
    @State private var sheet: TransferSheet?
    @State private var targetOpened = false

    /// A destructive choice waiting for its answer.
    struct Pending: Identifiable {
        let item: ActivityItem
        let choice: TransferPresentation.Choice
        var id: String { item.id + "·" + choice.id }
    }

    private var shown: [ActivityItem] {
        let all = activity?.items ?? []
        return attentionOnly ? all.filter(\.isBroken) : all
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: "Transfers") {
                    if let activity {
                        Text(TransferPresentation.summary(activity.summary))
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.66))
                            .monospacedDigit()
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                filters
                    .padding(.top, 12)
                StatusLine(message: notice.text.isEmpty ? status : notice) { polls += 1 }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 10)
                if let activity, activity.items.isEmpty {
                    GlassEmpty(title: includeFinished ? "Nothing in the queues" : "Nothing running",
                               detail: includeFinished ? "Requested titles show here while they download."
                                   : "Show finished lists what is done and seeding.")
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 16)
                }
                ForEach(shown) { item in
                    TransferRow(item: item, working: working) { choice in handle(choice, on: item) }
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 10)
                }
            }
            .padding(.bottom, 28)
        }
        .refreshable { polls += 1 }
        .task(id: "\(polls)·\(includeFinished)·\(isEnabled)") {
            guard isEnabled else { return }
            await poll()
        }
        .alert(confirming.map { $0.choice.label + "?" } ?? "",
               isPresented: Binding(get: { confirming != nil }, set: { if !$0 { confirming = nil } }),
               presenting: confirming) { pending in
            // The harmless answer first. Given the cancel role, iOS 26 moves it
            // after the destructive one, so it is a plain button that Escape presses.
            Button("Cancel") { confirming = nil }
                .keyboardShortcut(.cancelAction)
            Button(pending.choice.label, role: .destructive) {
                confirming = nil
                Task { await run(pending.choice.id, on: pending.item) }
            }
        } message: { pending in
            Text(pending.item.headline + "\n" + pending.choice.detail)
        }
        .sheet(item: $sheet) { which in
            TransferSheetView(sheet: which, refresh: { polls += 1 }, resume: { item in
                Task { await run("start", on: item) }
            })
        }
    }

    private var filters: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                let broken = activity?.items.filter(\.isBroken).count ?? 0
                ChoicePill(title: TransferPresentation.attentionFilter(count: broken, on: attentionOnly), selected: attentionOnly) {
                    attentionOnly.toggle()
                }
                .accessibilityIdentifier("attention-filter")
                ChoicePill(title: "Show finished", selected: includeFinished) { includeFinished.toggle() }
                    .accessibilityIdentifier("show-finished")
                NavigationLink(value: AppRoute.speedLimits) {
                    Label("Speed limits", systemImage: "gauge.with.dots.needle.33percent")
                }
                .buttonStyle(GlassControlStyle())
                .accessibilityIdentifier("speed-limits")
            }
        }
        .scrollClipDisabled()
        .contentMargins(.horizontal, metrics.margin, for: .scrollContent)
    }

    // MARK: Choices

    private func handle(_ choice: TransferPresentation.Choice, on item: ActivityItem) {
        switch choice.id {
        case "diagnosis": sheet = .diagnosis(item)
        case "details": sheet = .details(item)
        default:
            if choice.danger {
                confirming = Pending(item: item, choice: choice)
            } else {
                Task { await run(choice.id, on: item) }
            }
        }
    }

    private func run(_ action: String, on item: ActivityItem) async {
        guard !working else { return }
        working = true
        defer { working = false }
        notice = StatusMessage("Working…")
        let hub = model.hub
        do {
            switch action {
            case "stop", "start", "priority_up", "priority_down":
                try await hub.send(HubEndpoints.downloadAction(id: item.id, action: action))
            case "delete":
                try await hub.send(HubEndpoints.downloadDelete(id: item.id, deleteFiles: false))
            case "delete_with_data":
                try await hub.send(HubEndpoints.downloadDelete(id: item.id, deleteFiles: true))
            case "arr_remove", "arr_blocklist_and_search":
                guard let arr = item.arr else {
                    notice = StatusMessage("This transfer has no queue row to remove", tone: .error)
                    return
                }
                let again = action == "arr_blocklist_and_search"
                try await hub.send(HubEndpoints.queueRemove(service: arr.service, queueId: arr.queueId, removeFromClient: true,
                                                            blocklist: again, search: again))
            default:
                notice = StatusMessage("This app does not know how to \(action)", tone: .error)
                return
            }
            notice = StatusMessage(TransferPresentation.done(action, headline: item.headline))
        } catch {
            if error.kind == .cancelled { return }
            notice = StatusMessage(error.message, tone: .error)
        }
        // qBittorrent does not change a transfer with its reply: ask fast for a while.
        settleUntil = .now + PollSchedule.settle
        polls += 1
    }

    // MARK: Loading

    /// The transfers, then again on `PollSchedule`'s pace while the page shows.
    /// A failed read keeps the list: the hub out of reach for a moment is not
    /// the downloads gone, and blanking the page would say they were.
    private func poll() async {
        var failures = 0
        while !Task.isCancelled {
            do {
                let response = try await model.hub.fetch(HubEndpoints.activity(includeFinished: includeFinished),
                                                         as: ActivityResponse.self)
                activity = response
                failures = 0
                let broken = response.items.filter(\.isBroken).count
                let text = TransferPresentation.status(shown: attentionOnly ? broken : response.items.count,
                                                       total: response.items.count, attentionOnly: attentionOnly,
                                                       includeFinished: includeFinished)
                status = StatusText.caveat(CacheInfo(), unavailable: response.partial.map(\.service))
                if status.text.isEmpty { status = StatusMessage(text) }
                openTarget(response)
            } catch {
                if error.kind == .cancelled { return }
                failures += 1
                status = StatusMessage(error.message + " · retrying", tone: .error)
            }
            let settling = settleUntil.map { .now < $0 } ?? false
            if !settling && !working { notice = StatusMessage("") }
            guard let delay = PollSchedule.next(active: activity?.anyActive ?? false, failures: failures,
                                                settling: settling) else { return }
            try? await Task.sleep(for: delay)
        }
    }

    /// Why is this stuck? from the dashboard: its explanation, once, on arrival.
    private func openTarget(_ response: ActivityResponse) {
        guard !route.target.isEmpty, !targetOpened else { return }
        targetOpened = true
        if let item = response.items.first(where: { $0.id == route.target }) {
            sheet = .diagnosis(item)
        } else {
            notice = StatusMessage("This transfer is no longer in the queue.")
        }
    }
}

/// One transfer (`.trow`): a tile saying what it is doing, the title with its
/// stage as a chip, the release's own name, a white bar, one line of figures,
/// what is wrong in the services' words, and its buttons: Stop or Start, Why
/// isn't it working? when it is broken, and the rest under More.
struct TransferRow: View {
    let item: ActivityItem
    let working: Bool
    let act: (TransferPresentation.Choice) -> Void

    private var tone: Color {
        switch TransferPresentation.tone(item.stage) {
        case .good: .available
        case .waiting: .pending
        case .bad: .failed
        case .quiet: item.stage == Stages.downloading ? .white : .white.opacity(0.6)
        }
    }

    var body: some View {
        let choices = TransferPresentation.choices(item)
        let toggle = TransferPresentation.toggle(item)
        let trailing = TransferPresentation.trailing(item)
        let stats = TransferPresentation.stats(item)
        let note = TransferPresentation.note(item)
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: TransferPresentation.symbol(item.stage))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(tone)
                .frame(width: 40, height: 40)
                .background(tone.opacity(0.18), in: RoundedRectangle(cornerRadius: 11, style: .continuous))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(item.headline)
                        .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 6)
                    Text(Stages.label(item.stage))
                        .font(HubType.chrome(11.5, weight: .bold))
                        .foregroundStyle(tone)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 4)
                        .background(tone.opacity(0.16), in: Capsule())
                        .fixedSize()
                    if !trailing.isEmpty {
                        Text(trailing)
                            .font(HubType.body(12.5, weight: .semibold, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.75))
                            .monospacedDigit()
                            .fixedSize()
                    }
                }
                if !item.subline.isEmpty {
                    Text(item.subline)
                        .font(HubType.body(12, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.5))
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
                if TransferPresentation.showsBar(item) {
                    TransferBar(fraction: item.progress)
                        .padding(.vertical, 2)
                }
                if !stats.isEmpty {
                    Text(stats)
                        .font(HubType.body(12.5, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.66))
                        .lineLimit(2)
                }
                if !note.isEmpty {
                    Text(note)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(Color.dangerText)
                        .lineLimit(3)
                }
                // A phone's row has room for "Why?" where an iPad's says it in full.
                ViewThatFits(in: .horizontal) {
                    buttons(choices, toggle: toggle, why: choices.first?.label ?? "")
                    buttons(choices, toggle: toggle, why: "Why?")
                }
                .padding(.top, 4)
            }
        }
        .padding(14)
        .frame(maxWidth: 900, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("transfer-\(item.id)")
    }

    private func buttons(_ choices: [TransferPresentation.Choice], toggle: TransferPresentation.Choice?, why: String) -> some View {
        HStack(spacing: 8) {
            if let toggle {
                Button(toggle.label) { act(toggle) }
                    .buttonStyle(GlassControlStyle())
                    .disabled(working)
                    .accessibilityIdentifier("\(toggle.id)-\(item.id)")
            }
            if item.isBroken, let diagnosis = choices.first {
                Button(why) { act(diagnosis) }
                    .buttonStyle(GlassControlStyle())
                    .accessibilityLabel(diagnosis.label)
                    .accessibilityIdentifier("diagnosis-\(item.id)")
            }
            Menu {
                ForEach(choices.filter { $0.id != toggle?.id && !(item.isBroken && $0.id == "diagnosis") }) { choice in
                    Button(role: choice.danger ? .destructive : nil) { act(choice) } label: {
                        Label(choice.label, systemImage: Self.symbol(choice.id))
                    }
                }
            } label: {
                Label("More", systemImage: "ellipsis")
            }
            .menuStyle(.button)
            .buttonStyle(GlassControlStyle())
            .disabled(working)
            .accessibilityIdentifier("more-\(item.id)")
        }
        .fixedSize()
    }

    /// Each choice's mark in the menu.
    static func symbol(_ id: String) -> String {
        switch id {
        case "diagnosis": "stethoscope"
        case "details": "info.circle"
        case "stop": "pause"
        case "start": "play"
        case "priority_up": "arrow.up"
        case "priority_down": "arrow.down"
        case "delete": "minus.circle"
        case "delete_with_data": "trash"
        case "arr_remove": "xmark.circle"
        case "arr_blocklist_and_search": "hand.raised"
        default: "circle"
        }
    }
}

/// What a sheet over the transfers shows: why one is stuck, or its details.
enum TransferSheet: Identifiable {
    case diagnosis(ActivityItem)
    case details(ActivityItem)

    var id: String {
        switch self {
        case .diagnosis(let item): "diagnosis:" + item.id
        case .details(let item): "details:" + item.id
        }
    }
}

/// Why isn't it working? (or the transfer's status), with the hub's own
/// words and Resume transfer only where the hub offers it; or the details.
struct TransferSheetView: View {
    let sheet: TransferSheet
    let refresh: () -> Void
    let resume: (ActivityItem) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(heading)
                        .font(HubType.heading(22, weight: .bold, relativeTo: .title2))
                        .foregroundStyle(.white)
                    Text(item.headline)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.7))
                }
                Spacer(minLength: 8)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 40) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(20)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    switch sheet {
                    case .diagnosis(let item): diagnosis(item)
                    case .details(let item): details(item)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 20)
                .padding(.bottom, 16)
            }
            // What may be done stays in sight under the words, however long they run.
            if case .diagnosis(let item) = sheet {
                actions(item)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
            }
        }
        .presentationBackground { GlassSheetFill() }
        .presentationDetents(sizeClass == .compact ? [.medium, .large] : [.large])
        // A page's height on an iPad and the Mac: an explanation is for reading.
        .presentationSizing(.page)
        .accessibilityIdentifier("transfer-sheet")
    }

    private var item: ActivityItem {
        switch sheet {
        case .diagnosis(let item), .details(let item): item
        }
    }

    private var heading: String {
        switch sheet {
        case .diagnosis(let item): item.isBroken ? "Why isn't it working?" : "Transfer status"
        case .details: "Transfer details"
        }
    }

    @ViewBuilder private func diagnosis(_ item: ActivityItem) -> some View {
        let said = TransferPresentation.diagnosis(item)
        paragraph(said.title, heading: true)
        paragraph(said.explanation)
        paragraph("Suggested next step", heading: true)
        paragraph(said.nextStep)
        if !said.evidence.isEmpty {
            paragraph("What the services said", heading: true)
            ForEach(said.evidence, id: \.self) { paragraph($0) }
        }
        paragraph("A snapshot from the last refresh.")
    }

    private func actions(_ item: ActivityItem) -> some View {
        HStack(spacing: 10) {
            Button {
                refresh()
                dismiss()
            } label: {
                Label("Refresh status", systemImage: "arrow.clockwise")
            }
            .buttonStyle(GlassControlStyle())
            // Only the harmless action the hub itself offers for it.
            if item.diagnosis?.action == "start" && item.can("start") {
                Button {
                    resume(item)
                    dismiss()
                } label: {
                    Label("Resume transfer", systemImage: "play.fill")
                }
                .buttonStyle(PrimaryPillStyle())
                .accessibilityIdentifier("resume-transfer")
            }
            Spacer(minLength: 0)
        }
    }

    @ViewBuilder private func details(_ item: ActivityItem) -> some View {
        let facts: [(String, String)] = [
            ("Release", item.title),
            ("Stage", Stages.label(item.stage) + (item.clientState.isEmpty ? "" : " · qBittorrent says \(item.clientState)")),
            ("Size", item.sizeBytes > 0 ? Fmt.bytes(item.sizeBytes) : ""),
            ("Queue", item.arr.map { "\(ServiceNames.display($0.service)) · row \($0.queueId)" } ?? ""),
            ("Category", item.category),
            ("Indexer", item.indexer),
            ("Warnings", item.warnings.map(TransferPresentation.warningLabel).joined(separator: "\n")),
        ].filter { !$0.1.isEmpty }
        ForEach(facts, id: \.0) { fact in
            VStack(alignment: .leading, spacing: 3) {
                GlassLabel(text: fact.0)
                Text(fact.1)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func paragraph(_ text: String, heading: Bool = false) -> some View {
        Text(text)
            .font(heading ? HubType.body(16, weight: .bold, relativeTo: .headline) : HubType.body(14, relativeTo: .subheadline))
            .foregroundStyle(heading ? .white : .white.opacity(0.75))
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, heading ? 6 : 0)
    }
}

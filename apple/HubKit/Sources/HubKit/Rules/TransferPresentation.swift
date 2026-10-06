import Foundation

/// A transfer's words on the transfers page (#29; Android's `DownloadRowView`
/// and `DownloadsScreen`): its tile, chip, figures and the note on what is
/// wrong, the menu of what may be done (built from the hub's `actions`, never
/// guessed here), and the page's summary.
///
/// Every action that destroys something takes a second answer, the harmless
/// one first: whatever the pointer or the thumb happened to be on, "delete
/// the files" is never one press away.
public enum TransferPresentation {
    /// How a stage is coloured: moving or waiting quiet, importing amber, done green, stuck red.
    public enum Tone: Equatable, Sendable { case quiet, waiting, good, bad }

    public static func tone(_ stage: String) -> Tone {
        switch stage {
        case Stages.importing: .waiting
        case Stages.seeding, Stages.done: .good
        case Stages.stuck: .bad
        default: .quiet
        }
    }

    /// The tile's mark, an SF Symbol: what the transfer is doing at a glance.
    public static func symbol(_ stage: String) -> String {
        switch stage {
        case Stages.downloading: "arrow.down"
        case Stages.seeding: "arrow.up"
        case Stages.importing: "arrow.right"
        case Stages.stuck: "exclamationmark"
        case Stages.done: "checkmark"
        case Stages.stopped: "pause.fill"
        default: "ellipsis"
        }
    }

    /// Beside the title: a seeding torrent's upload rate (it sits at 100%
    /// for ever), else how far, else nothing.
    public static func trailing(_ item: ActivityItem) -> String {
        if item.stage == Stages.seeding { return "↑ " + Fmt.speed(item.uploadBps) }
        return item.progress > 0 ? Fmt.percent(item.progress) : ""
    }

    /// The bar, except on a stuck transfer that never started.
    public static func showsBar(_ item: ActivityItem) -> Bool {
        !(item.stage == Stages.stuck && item.progress <= 0)
    }

    /// "23 episodes · 12.6 of 40.8 GB · 3.2 MB/s · 9m left · 41s/8p · Demo Indexer".
    public static func stats(_ item: ActivityItem) -> String {
        var parts: [String] = []
        if item.queueItems > 1 { parts.append("\(item.queueItems) episodes") }
        if item.sizeBytes > 0 {
            let done = item.remainingBytes > 0 && item.remainingBytes < item.sizeBytes
                ? Fmt.bytes(item.sizeBytes - item.remainingBytes) + " of " : ""
            parts.append(done + Fmt.bytes(item.sizeBytes))
        }
        if item.speedBps > 0 { parts.append(Fmt.speed(item.speedBps)) }
        if item.etaSeconds >= 0 && item.stage == Stages.downloading { parts.append(Fmt.eta(item.etaSeconds) + " left") }
        if item.seeds > 0 || item.peers > 0 { parts.append("\(item.seeds)s/\(item.peers)p") }
        if !item.indexer.isEmpty { parts.append(item.indexer) }
        return parts.isEmpty ? item.`protocol` : parts.joined(separator: " · ")
    }

    /// What is wrong, in the services' own words: the hub's diagnosis, then
    /// the *arr's problem verbatim (paraphrasing "Found executable file with
    /// extension: '.exe'" would hide the most useful thing said), then the
    /// client's warnings in words.
    public static func note(_ item: ActivityItem) -> String {
        var parts: [String] = []
        if let diagnosis = item.diagnosis, diagnosis.needsAttention, !diagnosis.title.isEmpty { parts.append(diagnosis.title) }
        if let problem = item.arr?.problem, !problem.isEmpty, !parts.joined().contains(problem) { parts.append(problem) }
        parts += item.warnings.map(warningLabel)
        return parts.joined(separator: " · ")
    }

    /// The hub's and qBittorrent's words for a broken transfer, in English;
    /// one not known here is shown as it came, which beats dropping it.
    public static func warningLabel(_ warning: String) -> String {
        switch warning {
        case "no_client_item": "no download-client item — the grab never landed"
        case "unmatched_download": "running, but no matching queue row"
        case "usenet_no_client_detail": "usenet — no client-level detail"
        case "missingFiles": "qBittorrent cannot find the files on disk"
        case "error": "qBittorrent reports an error on this transfer"
        default: warning
        }
    }

    // MARK: What may be done

    /// One entry of a transfer's menu.
    public struct Choice: Equatable, Sendable, Identifiable {
        /// The hub's action word, or "diagnosis" and "details".
        public let id: String
        public let label: String
        public let detail: String
        /// Destroys something: asked about again, the harmless answer first.
        public let danger: Bool

        public init(id: String, label: String, detail: String = "", danger: Bool = false) {
            self.id = id
            self.label = label
            self.detail = detail
            self.danger = danger
        }
    }

    /// The menu: why it is (or is not) stuck, then what the hub lets this
    /// token do, in the hub's order, then the transfer's details.
    public static func choices(_ item: ActivityItem) -> [Choice] {
        [Choice(id: "diagnosis", label: item.isBroken ? "Why isn't it working?" : "Check transfer status",
                detail: item.diagnosis?.title ?? "")]
            + item.actions.compactMap { choice(for: $0, item: item) }
            + [Choice(id: "details", label: "Transfer details")]
    }

    public static func choice(for action: String, item: ActivityItem) -> Choice? {
        switch action {
        case "priority_up": Choice(id: action, label: "Move up queue", detail: "Current queue position: \(item.priority)")
        case "priority_down": Choice(id: action, label: "Move down queue", detail: "Current queue position: \(item.priority)")
        case "stop": Choice(id: action, label: "Stop", detail: "Leaves the files and the queue row")
        case "start": Choice(id: action, label: "Start", detail: "Resume this transfer")
        case "delete": Choice(id: action, label: "Remove from client", detail: "Keeps the downloaded files", danger: true)
        case "delete_with_data":
            Choice(id: action, label: "Remove and delete files", detail: "The data is gone for good", danger: true)
        case "arr_remove":
            Choice(id: action, label: "Remove from " + (item.arr.map { ServiceNames.display($0.service) } ?? "queue"),
                   detail: "Drops the queue row and the transfer", danger: true)
        case "arr_blocklist_and_search":
            Choice(id: action, label: "Blocklist and search again",
                   detail: "Never grab this release again, then look for another", danger: true)
        default: nil
        }
    }

    /// The reversible action a row offers as its own button: Stop or Start.
    public static func toggle(_ item: ActivityItem) -> Choice? {
        if item.can("stop") { return choice(for: "stop", item: item) }
        if item.can("start") { return choice(for: "start", item: item) }
        return nil
    }

    /// "Stopped Severance", "Blocklisted, searching again for Dune".
    public static func done(_ action: String, headline: String) -> String {
        let verb: String = switch action {
        case "stop": "Stopped"
        case "start": "Started"
        case "priority_up", "priority_down": "Updated queue priority for"
        case "delete": "Removed"
        case "delete_with_data": "Deleted"
        case "arr_remove": "Removed from queue"
        case "arr_blocklist_and_search": "Blocklisted, searching again for"
        default: action
        }
        return verb + " " + headline
    }

    /// The diagnosis sheet's paragraphs, with the hub's words or an honest
    /// stand-in when an older hub sends none.
    public static func diagnosis(_ item: ActivityItem) -> (title: String, explanation: String, nextStep: String, evidence: [String]) {
        let found = item.diagnosis
        return (title: found.map(\.title).flatMap { $0.isEmpty ? nil : $0 } ?? "Detailed diagnosis unavailable",
                explanation: found.map(\.explanation).flatMap { $0.isEmpty ? nil : $0 }
                    ?? "This hub does not explain transfers yet. The latest stage is \(Stages.label(item.stage)).",
                nextStep: found.map(\.nextStep).flatMap { $0.isEmpty ? nil : $0 } ?? "Look at the queue and the services in Services.",
                evidence: found?.evidence ?? [])
    }

    // MARK: The page

    /// "2 downloading · 1 queued · 3 seeding · 1 stuck   ↓ 1.2 MB/s  ↑ 300 KB/s".
    public static func summary(_ summary: ActivitySummary) -> String {
        var line = "\(summary.downloading) downloading"
        if summary.queued > 0 { line += " · \(summary.queued) queued" }
        if summary.seeding > 0 { line += " · \(summary.seeding) seeding" }
        if summary.stuck > 0 { line += " · \(summary.stuck) stuck" }
        if summary.downSpeedBytes > 0 || summary.upSpeedBytes > 0 {
            line += "   ↓ " + Fmt.speed(summary.downSpeedBytes) + "  ↑ " + Fmt.speed(summary.upSpeedBytes)
        }
        return line
    }

    /// The line under the summary: how many are shown, or that none are.
    public static func status(shown: Int, total: Int, attentionOnly: Bool, includeFinished: Bool) -> String {
        if attentionOnly && shown == 0 { return "No transfers need attention." }
        if attentionOnly { return ActivityDashboard.needAttention(shown) }
        if total == 0 && includeFinished { return "Nothing in the queues." }
        if total == 0 { return "Nothing running. Show finished lists what is done." }
        return "\(shown) item\(shown == 1 ? "" : "s")" + (includeFinished ? " · including finished" : "")
    }

    /// The filter pill: "Needs attention · 2", or "Showing 2 that need attention" while it is on.
    public static func attentionFilter(count: Int, on: Bool) -> String {
        on ? "Showing \(count) that need\(count == 1 ? "s" : "") attention" : "Needs attention · \(count)"
    }
}

/// qBittorrent's limits in words and in the edit sheet (#29; Android's
/// `BandwidthScreen`): Normal speed and Quiet, the caps in KiB/s.
public enum BandwidthPresentation {
    /// The set in use, by the names Activity uses.
    public static func name(quiet: Bool) -> String { quiet ? "Quiet" : "Normal speed" }

    /// "Unlimited", "10 KiB/s", "1,536 KiB/s", "1,536.5 KiB/s".
    public static func rate(_ bytesPerSecond: Int64) -> String {
        guard bytesPerSecond > 0 else { return "Unlimited" }
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.numberStyle = .decimal
        formatter.usesGroupingSeparator = true
        formatter.groupingSeparator = ","
        formatter.groupingSize = 3
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 1
        formatter.roundingMode = .halfEven
        return (formatter.string(from: NSNumber(value: Double(bytesPerSecond) / 1024)) ?? "\(bytesPerSecond / 1024)") + " KiB/s"
    }

    /// A cap as the edit field starts with it, in KiB/s: "10", "1.5", "0".
    public static func field(_ bytesPerSecond: Int64) -> String {
        var text = String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), Double(max(bytesPerSecond, 0)) / 1024)
        while text.hasSuffix("0") { text.removeLast() }
        if text.hasSuffix(".") { text.removeLast() }
        return text
    }

    /// The most a cap may be: 1 GiB/s, the hub's limit, in KiB/s.
    public static let maximumKiB: Double = 1_048_576

    /// An entered cap in bytes per second, or nil when it is not a number from 0 to 1,048,576 KiB/s.
    public static func parse(_ text: String) -> Int64? {
        guard let kib = Double(text.trimmingCharacters(in: .whitespaces)), kib.isFinite, kib >= 0, kib <= maximumKiB else {
            return nil
        }
        return Int64((kib * 1024).rounded())
    }

    /// What else is true of the limits, each a sentence.
    public static func notes(_ state: BandwidthState) -> [String] {
        var notes: [String] = []
        if state.schedulerEnabled { notes.append("qBittorrent's own schedule is on, so it may switch between these by itself.") }
        notes.append(state.queueingEnabled ? "Each transfer's actions can move it up or down the queue."
                     : "Queueing is off in qBittorrent, so transfers have no order to change.")
        if !state.canControl { notes.append("This token can read these limits but not change them.") }
        if state.canControl && !state.modeSwitchSupported { notes.append("Switching between them here needs qBittorrent 5 or later.") }
        return notes
    }
}

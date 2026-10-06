import Foundation

// An Apple download (#5, option 1): the hub makes an MP4 of the original on
// the PC, one at a time, and the device downloads it once it is ready. What
// the device does at each answer is decided here, pure, so the downloader in
// the app only carries it out and the rules are tested.

/// What an Apple download does next.
public enum OfflineTransfer {
    /// The next step, from the PC's answer about the MP4.
    public enum Step: Equatable, Sendable {
        /// Still on the PC: show how far (or the place in line) and ask again after a while.
        case wait(percent: Int, queuePosition: Int, afterMillis: Int64)
        /// Ready: download exactly `sizeBytes`, the file `etag` names.
        case download(sizeBytes: Int64, etag: String)
        /// The PC could not make it: say so, and offer Retry.
        case failed(message: String, retryable: Bool)
    }

    /// How often to ask while it runs, and while it waits its turn: the
    /// contract asks for every 2 to 5 seconds.
    public static let runningPollMillis: Int64 = 2_000
    public static let queuedPollMillis: Int64 = 5_000

    public static func step(_ status: OfflineGrantStatus) -> Step {
        switch status.state {
        case .ready:
            // An original grant answers ready with the original's size and no ETag.
            return .download(sizeBytes: status.sizeBytes, etag: status.etag)
        case .failed:
            let message = status.error?.message.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return .failed(message: message.isEmpty ? failedOnThePC : message, retryable: status.error?.retryable ?? false)
        case .preparing:
            // 100 only once ready: the hub holds 99 while it finishes the file.
            return .wait(percent: min(max(status.percent, 0), 99), queuePosition: 0, afterMillis: runningPollMillis)
        case .queued, .unknown:
            return .wait(percent: 0, queuePosition: max(status.queuePosition, 0), afterMillis: queuedPollMillis)
        }
    }

    /// What a request for the file, or for its status, that failed means.
    public enum Recovery: Equatable, Sendable {
        /// The MP4 is not ready after all (409 `offline_preparing`): wait on the PC again.
        case prepareAgain(afterMillis: Int64)
        /// The grant expired (410 `grant_expired`): renew it and carry on.
        case renew
        /// The token was refused, or the device is banned: hold every download
        /// (the client's gate holds every screen too) until that changes.
        case credentials(String)
        /// Leave it for the person, with this sentence.
        case failed(String)
        /// Try again later, counted as a failure (`OfflineRetryPolicy`).
        case later(String)
    }

    public static func recovery(_ failure: HubFailure) -> Recovery {
        let status = failure.status ?? 0
        switch status {
        case 409 where failure.code == "offline_preparing":
            return .prepareAgain(afterMillis: max(failure.retryAfterSeconds ?? 5, 1) * 1_000)
        case 409 where failure.code == "source_changed":
            return .failed(sourceChanged)
        case 409:
            // `offline_failed`: the status says what went wrong and whether a retry helps.
            return .failed(failure.message)
        case 410:
            return .renew
        case 401:
            return .credentials(FailureKind.unauthorized.message)
        case 429 where (failure.retryAfterSeconds ?? 0) >= CredentialGate.banThresholdSeconds:
            return .credentials(FailureKind.banned.message)
        case 404:
            return .failed(gone)
        case 403:
            return .failed(noScope)
        default:
            return failure.kind.isRetryable || failure.kind == .unknown ? .later(failure.message) : .failed(failure.message)
        }
    }

    static let failedOnThePC = "The PC could not prepare this download"
    static let sourceChanged = "The file changed on the PC. Remove this download and download it again."
    static let gone = "This download is no longer on the hub. Remove it and download it again."
    static let noScope = "This device's token may not download. Give it the download scope on the hub."
    /// A hub that predates the Apple format answers the original, which AVPlayer cannot play.
    public static let hubTooOld = "The hub needs updating before it can make downloads for this device."
}

/// What an Apple download says about its MP4 in words (#5): the person is
/// told what will not come across before they wait for it.
public enum OfflineAppleNotes {
    /// A track's language in English: "fra" is French. The code itself when
    /// it is not a language ("und").
    public static func language(_ code: String) -> String {
        let trimmed = code.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, trimmed.lowercased() != "und" else { return "" }
        return Locale(identifier: "en_US").localizedString(forLanguageCode: trimmed) ?? trimmed.uppercased()
    }

    /// "French and German subtitles are left out: they are pictures, which
    /// this device cannot show." Nil when every subtitle comes across.
    public static func leftOut(_ apple: OfflineApple) -> String? {
        let missing = apple.subtitles.filter { !$0.available }
        guard !missing.isEmpty else { return nil }
        var names: [String] = []
        for track in missing {
            let name = language(track.language)
            if !name.isEmpty, !names.contains(name) { names.append(name) }
        }
        let pictures = missing.allSatisfy { $0.reason == "picture_subtitle" }
        return (names.isEmpty ? "Some" : list(names)) + " subtitles are left out"
            + (pictures ? ": they are pictures, which this device cannot show." : ".")
    }

    /// "Takes longer to prepare: the picture is converted." Nil when it is copied.
    public static func slower(_ apple: OfflineApple) -> String? {
        apple.video.converted ? "Takes longer to prepare: the picture is converted." : nil
    }

    /// The lines under a download's name in the manager and on its page.
    public static func lines(_ apple: OfflineApple) -> [String] {
        [slower(apple), leftOut(apple)].compactMap { $0 }
    }

    /// The picker's line for what is chosen: "2 take longer to prepare ·
    /// 3 subtitle tracks left out". Nil when nothing is lost or slow.
    public static func selection(_ summaries: [OfflineAppleSummary]) -> String? {
        let converted = summaries.filter(\.videoConverted).count
        let omitted = summaries.reduce(0) { $0 + $1.omittedSubtitles }
        var parts: [String] = []
        if converted > 0 { parts.append(converted == 1 ? "1 takes longer to prepare" : "\(converted) take longer to prepare") }
        if omitted > 0 { parts.append(omitted == 1 ? "1 subtitle track left out" : "\(omitted) subtitle tracks left out") }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private static func list(_ names: [String]) -> String {
        guard names.count > 1 else { return names.first ?? "" }
        return names.dropLast().joined(separator: ", ") + " and " + (names.last ?? "")
    }
}

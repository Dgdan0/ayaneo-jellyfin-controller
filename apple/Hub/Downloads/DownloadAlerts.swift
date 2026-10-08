import HubKit
import UserNotifications

/// A notification when one of this device's downloads finishes or fails
/// (#43; the Pocket's offline alerts), as a local notification: the
/// outcomes are `OfflineAlerts`'. Permission is asked the first time a
/// download starts, never at launch; with it refused, nothing is shown. A
/// notification also shows while the app is open, as the Pocket's does, and
/// a tap on one opens Downloads: a failure on the queue, where its Retry is.
@MainActor
final class DownloadAlerts: NSObject, UNUserNotificationCenterDelegate {
    static let shared = DownloadAlerts()

    /// At launch: shown while the app is open too. Nothing is asked here.
    func start() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// The first download asks once whether the app may say when it is done.
    func askOnce(demo: Bool) async {
        guard Self.speaks(demo: demo) else { return }
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    /// The demo hub's runs (the UI tests among them) neither ask nor post,
    /// unless HUB_ALERTS=1: a permission given in one test would otherwise put
    /// a banner over the next one's screen.
    private static func speaks(demo: Bool) -> Bool {
        #if DEBUG
        !demo || ProcessInfo.processInfo.environment["HUB_ALERTS"] == "1"
        #else
        !demo
        #endif
    }

    /// Each outcome as a notification; a batch's group together.
    func post(_ alerts: [OfflineAlert], demo: Bool) {
        guard !alerts.isEmpty, Self.speaks(demo: demo) else { return }
        Task {
            let center = UNUserNotificationCenter.current()
            let status = await center.notificationSettings().authorizationStatus
            guard status == .authorized || status == .provisional else { return }
            for alert in alerts {
                let content = UNMutableNotificationContent()
                content.title = alert.title
                content.body = alert.body
                content.threadIdentifier = "offline:" + alert.batchId
                content.sound = .default
                try? await center.add(UNNotificationRequest(identifier: alert.id, content: content, trigger: nil))
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            didReceive response: UNNotificationResponse) async {
        let id = response.notification.request.identifier
        guard id.hasPrefix("offline:") else { return }
        let failed = id.hasSuffix(":failed")
        await MainActor.run { DownloadAlertTaps.shared.tapped(failed: failed) }
    }
}

/// A download's notification tapped (#43): the shell goes to Downloads, and
/// the Downloads page there shows the queue for a failure (its Retry and why
/// it failed) or what is on the device for one that finished.
@MainActor
@Observable
final class DownloadAlertTaps {
    static let shared = DownloadAlertTaps()

    struct Request: Equatable {
        let failed: Bool
        /// Each tap anew, so a second tap on the same outcome still moves.
        let serial: Int
    }

    /// The tab a Downloads page is to show, and which stack's page.
    struct Opening: Equatable {
        let stack: String
        let tab: DownloadsView.Tab
    }

    /// For the shell: a tap to act on.
    private(set) var request: Request?
    /// For the Downloads page the shell went to, until it takes it.
    private(set) var opening: Opening?

    func tapped(failed: Bool) {
        request = Request(failed: failed, serial: (request?.serial ?? 0) + 1)
    }

    /// The shell has gone to this stack's Downloads.
    func open(stack: String, failed: Bool) {
        opening = Opening(stack: stack, tab: failed ? .queue : .device)
    }

    /// The Downloads page of `stack` takes its tab, once.
    func take(stack: String) -> DownloadsView.Tab? {
        guard let opening, opening.stack == stack else { return nil }
        self.opening = nil
        return opening.tab
    }
}

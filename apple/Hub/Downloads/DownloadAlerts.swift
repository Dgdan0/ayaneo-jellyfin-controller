import HubKit
import UserNotifications

/// A notification when one of this device's downloads finishes or fails
/// (#43; the Pocket's offline alerts), as a local notification: the
/// outcomes are `OfflineAlerts`'. Permission is asked the first time a
/// download starts, never at launch; with it refused, nothing is shown. A
/// notification also shows while the app is open, as the Pocket's does.
@MainActor
final class DownloadAlerts: NSObject, UNUserNotificationCenterDelegate {
    static let shared = DownloadAlerts()

    /// At launch: shown while the app is open too. Nothing is asked here.
    func start() {
        UNUserNotificationCenter.current().delegate = self
    }

    /// The first download asks once whether the app may say when it is done.
    func askOnce(demo: Bool) async {
        // The demo hub's runs (the UI tests among them) are never interrupted by the question.
        #if DEBUG
        let asks = !demo || ProcessInfo.processInfo.environment["HUB_ALERTS"] == "1"
        #else
        let asks = !demo
        #endif
        guard asks else { return }
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .notDetermined else { return }
        _ = try? await center.requestAuthorization(options: [.alert, .sound])
    }

    /// Each outcome as a notification; a batch's group together.
    func post(_ alerts: [OfflineAlert]) {
        guard !alerts.isEmpty else { return }
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
}

import Foundation
import HubKit
import Observation
import SwiftUI

/// The services' notifications and what this device has seen of them (#36):
/// one answer for the bell's count and the page's unread dots, as Android keeps
/// them (`NotificationReadStore.observe` feeds both). The shell owns it, so the
/// bell asks once a minute wherever the person is, and the page asks while it
/// is shown; a read already in flight is shared rather than asked twice.
@MainActor
@Observable
final class NotificationsModel {
    private(set) var response: NotificationsResponse?
    private(set) var unreadIds: Set<String> = []
    /// A service's last good history, kept when a later read could not reach it.
    private(set) var kept: [String: [ServiceNotice]] = [:]
    /// Why the last read failed; nil after one that worked.
    private(set) var failure: StatusMessage?
    /// A row on screen this long is seen, so a flick down the list is not
    /// the same as reading it.
    static let dwell: Duration = {
        #if DEBUG
        // The UI tests read an unread row before it is seen, with HUB_SEEN_DWELL_MS=600000.
        if let text = ProcessInfo.processInfo.environment["HUB_SEEN_DWELL_MS"], let millis = Int(text) { return .milliseconds(millis) }
        #endif
        return .milliseconds(1_200)
    }()

    var unread: Int { unreadIds.count }

    @ObservationIgnored private var store: NotificationReadStore?
    @ObservationIgnored private var storeKey = ""
    @ObservationIgnored private var inflight: Task<Void, Never>?
    @ObservationIgnored private var dwelling: [String: Task<Void, Never>] = [:]

    /// Asks the hub and takes the answer in. Several callers at once share one ask.
    func refresh(_ app: AppModel) async {
        if let inflight {
            await inflight.value
            return
        }
        let task = Task { await self.read(app) }
        inflight = task
        await task.value
        inflight = nil
    }

    private func read(_ app: AppModel) async {
        guard app.isConfigured else { return }
        use(app)
        do {
            let answer = try await app.hub.fetch(HubEndpoints.notifications(NotificationSettings.limits()), as: NotificationsResponse.self)
            guard let store else { return }
            let unread = store.observe(answer.sections)
            // Merged with what was kept before the answer replaces it.
            for section in answer.sections {
                kept[section.service] = NotificationsPresentation.column(service: section.service, section: section,
                                                                         previous: kept[section.service] ?? [], unread: []).items
            }
            response = answer
            unreadIds = unread
            failure = nil
        } catch {
            if error.kind == .cancelled { return }
            failure = StatusText.failed(error.message, kind: error.kind, hasData: response != nil)
        }
    }

    /// The seen list for this hub. A different hub, or the demo, starts its own.
    private func use(_ app: AppModel) {
        let key = app.isDemo ? "demo" : app.address
        guard key != storeKey else { return }
        storeKey = key
        store = app.isDemo ? NotificationReadStore(snapshot: DemoTransport.notificationsSeenAtStart)
                           : NotificationReadStore.defaults(hub: app.address)
        response = nil
        unreadIds = []
        kept = [:]
        failure = nil
    }

    // MARK: Seen

    func markSeen(_ id: String) {
        dwelling[id]?.cancel()
        dwelling[id] = nil
        guard unreadIds.contains(id) else { return }
        store?.markSeen(id)
        unreadIds.remove(id)
    }

    /// Every notification of every service, as Android does: the bell counts
    /// them all.
    func markAllSeen() {
        dwelling.values.forEach { $0.cancel() }
        dwelling = [:]
        let ids = response?.sections.flatMap(\.items).map(\.id) ?? []
        store?.markAllSeen(ids)
        unreadIds = []
    }

    /// A row came on screen or left it. One that stays for `dwell` is seen.
    func rowVisible(_ id: String, _ visible: Bool) {
        guard unreadIds.contains(id) else { return }
        if !visible {
            dwelling[id]?.cancel()
            dwelling[id] = nil
        } else if dwelling[id] == nil {
            dwelling[id] = Task { [weak self] in
                try? await Task.sleep(for: Self.dwell)
                guard !Task.isCancelled else { return }
                self?.markSeen(id)
            }
        }
    }

    /// Unread entries among the services of a side.
    func unread(on side: AppSide) -> Int {
        NotificationsPresentation.unread(on: side, sections: response?.sections ?? [], unread: unreadIds)
    }
}

extension EnvironmentValues {
    /// Media or Books: the side the shell is showing. Pages that are the same
    /// on both sides but show each side's own things read it (Notifications).
    @Entry var appSide: AppSide = .media
}

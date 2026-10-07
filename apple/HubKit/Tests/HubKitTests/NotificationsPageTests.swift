import Foundation
import Testing
@testable import HubKit

/// The Notifications page and the server monitor (#36): Android's
/// `NotificationReadStateTest` case for case, then the words, the columns, the
/// history lengths and the demo hub's answers.
struct NotificationReadTests {
    private func notice(_ id: String, _ at: String = "", service: String = "sonarr", active: Bool = false) -> ServiceNotice {
        ServiceNotice(id: id, service: service, occurredAt: at, active: active)
    }

    private func sections(_ notices: ServiceNotice..., state: String = "up") -> [NotificationSection] {
        [NotificationSection(service: "sonarr", state: state, items: notices)]
    }

    @Test func firstObservationBaselinesExistingHistory() {
        let result = NotificationReadReducer.observe(NotificationReadSnapshot(), sections: sections(notice("old", "2026-01-01T00:00:00Z")))
        #expect(result.unreadIds.isEmpty)
        #expect(result.snapshot.seenIds.contains("old"))
    }

    @Test func aLaterItemIsUnreadUntilMarkedSeen() {
        let baseline = NotificationReadReducer.observe(NotificationReadSnapshot(),
                                                       sections: sections(notice("old", "2026-01-01T00:00:00Z"))).snapshot
        let observed = NotificationReadReducer.observe(
            baseline, sections: sections(notice("new", "2026-01-02T00:00:00Z"), notice("old", "2026-01-01T00:00:00Z")))
        #expect(observed.unreadIds == ["new"])
        let marked = NotificationReadReducer.markSeen(observed.snapshot, id: "new")
        #expect(NotificationReadReducer.observe(marked, sections: sections(notice("new", "2026-01-02T00:00:00Z"))).unreadIds.isEmpty)
    }

    @Test func olderBackfillFromALargerLimitIsAlreadySeen() {
        let baseline = NotificationReadReducer.observe(NotificationReadSnapshot(),
                                                       sections: sections(notice("old", "2026-01-02T00:00:00Z"))).snapshot
        let result = NotificationReadReducer.observe(
            baseline, sections: sections(notice("old", "2026-01-02T00:00:00Z"), notice("backfill", "2026-01-01T00:00:00Z")))
        #expect(result.unreadIds.isEmpty)
    }

    @Test func aNewHealthIssueWithoutATimestampIsUnread() {
        let baseline = NotificationReadReducer.observe(NotificationReadSnapshot(), sections: sections()).snapshot
        let result = NotificationReadReducer.observe(baseline, sections: sections(notice("health", active: true)))
        #expect(result.unreadIds == ["health"])
    }

    @Test func theCapNeverForgetsAnIdStillOnScreen() {
        // "a1" sorts first, so the old alphabetical cap dropped it and the bell
        // counted it unread again on the next read.
        let kept = NotificationReadReducer.bounded(["a1", "m2", "z3", "z4"], currentIds: ["a1"], max: 3)
        #expect(kept == ["a1", "z3", "z4"])
    }

    @Test func theCapLeavesASmallListAlone() {
        #expect(NotificationReadReducer.bounded(["a", "b"], currentIds: [], max: 3) == ["a", "b"])
    }

    @Test func aServiceIsBaselinedWhenItFirstGivesHistoryOrIsUp() {
        // Down with nothing, it has not been looked at yet; the first history it
        // does give is the baseline, whenever it arrives, and not an old inbox of news.
        let down = NotificationReadReducer.observe(NotificationReadSnapshot(), sections: sections(state: "unavailable"))
        #expect(down.snapshot.initializedServices.isEmpty)
        let back = NotificationReadReducer.observe(down.snapshot, sections: sections(notice("one", "2026-01-01T00:00:00Z")))
        #expect(back.unreadIds.isEmpty)
        #expect(back.snapshot.initializedServices == ["sonarr"])
    }

    @Test func theStoreKeepsWhatWasSeenAcrossLaunchesPerHub() throws {
        let suite = "notification-read-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = NotificationReadStore.defaults(defaults, hub: "https://one")
        let first = store.observe(sections(notice("a", "2026-01-01T00:00:00Z")))
        #expect(first.isEmpty)
        #expect(store.observe(sections(notice("b", "2026-01-02T00:00:00Z"), notice("a", "2026-01-01T00:00:00Z"))) == ["b"])
        store.markSeen("b")
        // Another launch reads the same list; another hub starts clean.
        let again = NotificationReadStore.defaults(defaults, hub: "https://one")
        #expect(again.seen == ["a", "b"])
        #expect(again.observe(sections(notice("b", "2026-01-02T00:00:00Z"))).isEmpty)
        #expect(NotificationReadStore.defaults(defaults, hub: "https://two").seen.isEmpty)
    }

    @Test func markAllSeenTakesEveryIdAndNothingElse() {
        let store = NotificationReadStore()
        store.markAllSeen(["x", "y"])
        store.markSeen("")
        #expect(store.seen == ["x", "y"])
    }
}

struct NotificationsPresentationTests {
    private let now = ISO8601DateFormatter().date(from: "2026-10-07T12:00:00Z")!

    @Test func eachSideShowsItsOwnThreeServices() {
        #expect(NotificationsPresentation.services(for: .media) == ["sonarr", "radarr", "bazarr"])
        #expect(NotificationsPresentation.services(for: .books) == ["bookkeeprr", "kavita", "storyteller"])
    }

    @Test func aHealthChecksNameIsSpacedAtEachCapital() {
        #expect(NotificationsPresentation.humanizeHealthTitle("IndexerLongTermStatusCheck") == "Indexer Long Term Status Check")
        #expect(NotificationsPresentation.humanizeHealthTitle("DownloadClient2Check") == "Download Client2 Check")
        #expect(NotificationsPresentation.humanizeHealthTitle("Radarr needs attention") == "Radarr needs attention")
        let health = ServiceNotice(id: "h", kind: "health", title: "RootFolderCheck")
        let history = ServiceNotice(id: "g", kind: "grabbed", title: "BleachTitle")
        #expect(NotificationsPresentation.headline(health) == "Root Folder Check")
        #expect(NotificationsPresentation.headline(history) == "BleachTitle", "only a health check is spaced")
    }

    @Test func aTimeIsTheServicesOwnWordsOrHowLongAgo() {
        func notice(_ at: String, label: String = "") -> ServiceNotice { ServiceNotice(id: "n", occurredAt: at, timeLabel: label) }
        func ago(_ seconds: Double) -> String {
            let when = ISO8601DateFormatter().string(from: now.addingTimeInterval(-seconds))
            return NotificationsPresentation.time(notice(when), now: now)
        }
        #expect(NotificationsPresentation.time(notice("", label: "5 hours ago"), now: now) == "5 hours ago")
        #expect(ago(20) == "Just now")
        #expect(ago(5 * 60) == "5m ago")
        #expect(ago(3 * 3_600) == "3h ago")
        #expect(ago(2 * 86_400) == "2d ago")
        #expect(ago(15 * 86_400) == "2w ago")
        #expect(ago(-30) == "Just now", "a clock a little ahead is not the future")
        #expect(NotificationsPresentation.time(notice("not a time"), now: now) == "")
        #expect(NotificationsPresentation.time(notice("2026-10-07T11:55:00.250Z"), now: now) == "4m ago")
    }

    @Test func aCurrentProblemSaysSoInsteadOfWhen() {
        let active = ServiceNotice(id: "h", occurredAt: "2026-10-07T11:00:00Z", active: true)
        #expect(NotificationsPresentation.meta(active, now: now) == "Needs attention now")
        #expect(NotificationsPresentation.meta(ServiceNotice(id: "g", occurredAt: "2026-10-07T11:00:00Z"), now: now) == "1h ago")
    }

    @Test func aColumnPinsWarningsAboveTheHistoryInTheirOwnOrder() {
        let items = [ServiceNotice(id: "h1", active: false), ServiceNotice(id: "w1", active: true),
                     ServiceNotice(id: "h2"), ServiceNotice(id: "w2", active: true)]
        let column = NotificationsPresentation.column(service: "sonarr", section: NotificationSection(service: "sonarr", state: "up", items: items),
                                                      previous: [], unread: ["h2", "w1"])
        #expect(column.items.map(\.id) == ["w1", "w2", "h1", "h2"])
        #expect(column.unread == 2)
        #expect(column.name == "Sonarr" && column.stateLabel == "Up to date")
        #expect(column.unreadLabel == "2 unread Sonarr notifications")
    }

    @Test func aServiceThatCouldNotBeReadKeepsItsLastGoodHistory() {
        let kept = [ServiceNotice(id: "old")]
        let degraded = NotificationsPresentation.column(service: "bazarr", section: NotificationSection(service: "bazarr", state: "degraded"),
                                                        previous: kept, unread: [])
        #expect(degraded.items.map(\.id) == ["old"])
        #expect(degraded.stateLabel == "Partial")
        // Switched off, it has nothing; up, what it says now; with nothing before, what it gives.
        let off = NotificationsPresentation.column(service: "bazarr", section: NotificationSection(service: "bazarr", state: "disabled"),
                                                   previous: kept, unread: [])
        #expect(off.items.isEmpty && off.empty == "Not configured")
        let up = NotificationsPresentation.column(service: "bazarr", section: NotificationSection(service: "bazarr", state: "up", items: [ServiceNotice(id: "new")]),
                                                  previous: kept, unread: [])
        #expect(up.items.map(\.id) == ["new"])
        let first = NotificationsPresentation.column(service: "bazarr", section: NotificationSection(service: "bazarr", state: "degraded", items: [ServiceNotice(id: "some")]),
                                                     previous: [], unread: [])
        #expect(first.items.map(\.id) == ["some"])
    }

    @Test func aServiceTheHubNamedNoColumnForIsNotConfigured() {
        let columns = NotificationsPresentation.columns(side: .books, sections: [], previous: [:], unread: [])
        #expect(columns.map(\.service) == ["bookkeeprr", "kavita", "storyteller"])
        #expect(columns.allSatisfy { $0.state == "disabled" && $0.empty == "Not configured" && $0.items.isEmpty })
    }

    @Test func eachStatesWordsAreTheirOwn() {
        #expect(["up", "degraded", "unavailable", "disabled", "other"].map(NotificationsPresentation.stateLabel)
            == ["Up to date", "Partial", "Unavailable", "Not configured", "Other"])
        #expect(["up", "degraded", "unavailable", "disabled"].map(NotificationsPresentation.emptyText)
            == ["No recent activity", "Some activity unavailable", "Service unavailable. Refresh tries again.", "Not configured"])
    }

    @Test func severityIsFourColoursAndAnythingElseIsInformation() {
        #expect(["success", "warning", "error", "info", "", "anything"].map { NotificationsPresentation.Severity($0) }
            == [.success, .warning, .error, .info, .info, .info])
    }

    private func response(items: [ServiceNotice] = [], attention: Int = 0, partial: [Partial] = [],
                          cache: CacheInfo = CacheInfo()) -> NotificationsResponse {
        NotificationsResponse(attentionCount: attention,
                              sections: [NotificationSection(service: "sonarr", state: "up", items: items)], partial: partial, cache: cache)
    }

    @Test func theSummaryPutsWhatIsUnreadFirstAndNamesTheOtherSide() {
        let item = [ServiceNotice(id: "a")]
        func line(_ response: NotificationsResponse, _ unread: Int, other: Int = 0) -> String {
            NotificationsPresentation.summary(response, unread: unread, otherSide: .books, unreadOnOtherSide: other)
        }
        #expect(line(response(), 0) == "No recent activity or service warnings.")
        #expect(line(response(items: item), 1) == "1 unread notification")
        #expect(line(response(items: item), 3) == "3 unread notifications")
        #expect(line(response(items: item), 2, other: 1) == "2 unread notifications · 1 in Books")
        #expect(line(response(items: item), 0, other: 4) == "All seen here · 4 unread in Books")
        #expect(line(response(items: item, attention: 1), 0) == "1 current service issue · all seen")
        #expect(line(response(items: item, attention: 2), 0) == "2 current service issues · all seen")
        #expect(line(response(items: item, partial: [Partial(service: "bazarr")]), 0) == "Recent activity")
        #expect(line(response(items: item, cache: CacheInfo(stale: true)), 0) == "Recent activity")
        #expect(line(response(items: item), 0) == "Recent activity · all services responding")
        #expect(NotificationsPresentation.sideName(.media) == "Movies and TV")
    }

    @Test func aServiceThatDidNotAnswerIsNamedInTheStatusLineInAmber() {
        let status = NotificationsPresentation.status(response(items: [ServiceNotice(id: "a")], partial: [Partial(service: "storyteller")]),
                                                      unread: 0, otherSide: .books, unreadOnOtherSide: 0)
        #expect(status.text == "Recent activity · Storyteller unavailable")
        #expect(status.tone == .warning)
    }

    @Test func unreadOnASideCountsOnlyItsServices() {
        let sections = [NotificationSection(service: "sonarr", items: [ServiceNotice(id: "a"), ServiceNotice(id: "b")]),
                        NotificationSection(service: "kavita", items: [ServiceNotice(id: "c")])]
        #expect(NotificationsPresentation.unread(on: .media, sections: sections, unread: ["a", "c"]) == 1)
        #expect(NotificationsPresentation.unread(on: .books, sections: sections, unread: ["a", "c"]) == 1)
        #expect(NotificationsPresentation.unread(on: .books, sections: sections, unread: []) == 0)
    }

    @Test func aRowIsReadAloudWithItsServiceAndWhetherItIsUnread() {
        let notice = ServiceNotice(id: "n", service: "radarr", title: "Dune", detail: "WEBDL", occurredAt: "2026-10-07T11:00:00Z")
        #expect(NotificationsPresentation.spoken(notice, unread: true, now: now) == "Unread, Radarr, Dune, WEBDL, 1h ago")
        #expect(NotificationsPresentation.spoken(notice, unread: false, now: now) == "Radarr, Dune, WEBDL, 1h ago")
        #expect(NotificationsPresentation.markAllLine(hadUnread: true) == "All notifications marked as seen")
        #expect(NotificationsPresentation.markAllLine(hadUnread: false) == "All notifications are already seen")
    }
}

struct NotificationSettingsTests {
    private func defaults() throws -> (UserDefaults, () -> Void) {
        let suite = "notification-limits-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        return (defaults, { defaults.removePersistentDomain(forName: suite) })
    }

    @Test func historyLengthsStartAsAndroidsAndAreAskedForAsChosen() throws {
        let (defaults, clean) = try defaults()
        defer { clean() }
        #expect(NotificationSettings.limits(defaults) == NotificationLimits(sonarr: 60, radarr: 20, bazarr: 40))
        #expect(HubEndpoints.notifications(NotificationSettings.limits(defaults)).path
            == "/v1/notifications?sonarrLimit=60&radarrLimit=20&bazarrLimit=40")
        NotificationSettings.setLimit(100, for: "sonarr", in: defaults)
        NotificationSettings.setLimit(20, for: "bazarr", in: defaults)
        #expect(NotificationSettings.limits(defaults) == NotificationLimits(sonarr: 100, radarr: 20, bazarr: 20))
        #expect(HubEndpoints.notifications(NotificationSettings.limits(defaults)).path
            == "/v1/notifications?sonarrLimit=100&radarrLimit=20&bazarrLimit=20")
    }

    @Test func onlyTheOfferedLengthsOnlyForTheServicesWithOne() throws {
        let (defaults, clean) = try defaults()
        defer { clean() }
        NotificationSettings.setLimit(30, for: "sonarr", in: defaults)
        NotificationSettings.setLimit(100, for: "kavita", in: defaults)
        #expect(NotificationSettings.limits(defaults) == NotificationLimits())
        #expect(NotificationSettings.choices == [20, 40, 60, 100])
        // A stored value that is no longer a choice is the default again.
        defaults.set(33, forKey: "notifications.limit.radarr")
        #expect(NotificationSettings.limits(defaults).radarr == 20)
        #expect(NotificationLimits().limit(for: "bazarr") == 40 && NotificationLimits().limit(for: "kavita") == nil)
    }
}

struct ServerMonitorPresentationTests {
    private func host(cpu: Double? = 14.5, total: Int64 = 32 << 30, free: Int64 = 17 << 30, uptime: Int64 = 363_600) -> HostSnapshot {
        HostSnapshot(os: "Windows 11 Pro", cpuPercent: cpu, memoryTotalBytes: total, memoryAvailableBytes: free, uptimeSeconds: uptime)
    }

    @Test func theThreeFiguresReadAsOnAndroid() {
        let figures = ServerMonitorPresentation.figures(host())
        #expect(figures.map(\.label) == ["CPU", "Memory", "Up for"])
        // 14.5 is 15%: Android's format rounds half up where C's would say 14.
        #expect(figures[0].value == "15%" && figures[0].detail == "Over a short sample")
        #expect(figures[0].fraction == 0.145 && !figures[0].warning)
        #expect(figures[1].value == "15.0 GB" && figures[1].detail == "of 32.0 GB")
        #expect(figures[1].fraction == 15.0 / 32.0)
        #expect(figures[2].value == "4 days 5 hours" && figures[2].detail == "Since the PC last started" && figures[2].fraction == nil)
        #expect(figures[0].spoken == "CPU, 15%, Over a short sample")
    }

    @Test func aBusyProcessorIsFlaggedAtNinetyPercent() {
        #expect(ServerMonitorPresentation.figures(host(cpu: 89.4))[0].warning == false)
        #expect(ServerMonitorPresentation.figures(host(cpu: 90))[0].warning)
    }

    @Test func whatTheHubCouldNotMeasureIsADashAndSaysUnavailable() {
        let figures = ServerMonitorPresentation.figures(host(cpu: nil, total: 0, free: 0, uptime: 0))
        #expect(figures.map(\.value) == ["—", "—", "—"])
        #expect(figures[0].detail == "Unavailable" && figures[0].fraction == nil && !figures[0].warning)
        #expect(figures[1].detail == "Unavailable" && figures[1].fraction == nil)
    }

    @Test func memoryNeverGoesNegative() {
        let figures = ServerMonitorPresentation.figures(host(total: 8 << 30, free: 9 << 30))
        #expect(figures[1].fraction == 0)
    }

    @Test func theStatusLineNamesTheHostAndWhenItWasChecked() {
        var monitor = ServerMonitor(host: host(), checkedAt: "2026-10-07T12:03:22Z")
        let utc = TimeZone(identifier: "UTC")!
        #expect(ServerMonitorPresentation.status(monitor, zone: utc)
            == "Windows 11 Pro host · checked 12:03:22 · refreshes every 15 seconds")
        #expect(ServerMonitorPresentation.status(monitor, zone: TimeZone(identifier: "Asia/Jerusalem")!).contains("checked 15:03:22"))
        monitor = ServerMonitor(host: HostSnapshot(), checkedAt: "")
        #expect(ServerMonitorPresentation.status(monitor, zone: utc) == "Media PC host · refreshes every 15 seconds")
        #expect(ServerMonitorPresentation.failed("The hub did not answer")
            == "Could not refresh · The hub did not answer · the figures below may be old")
        #expect(ServerMonitorPresentation.refreshSeconds == 15)
    }

    @Test func aDiskIsReadAloudInWordsAsWellAsColour() {
        let full = HostDisk(name: "E:\\", totalBytes: 8_000_000_000_000, availableBytes: 400_000_000_000)
        #expect(ServerMonitorPresentation.spoken(full) == "E:, 372 GB free of 7.3 TB, nearly full")
        let roomy = HostDisk(name: "C:\\", totalBytes: 1_000_000_000_000, availableBytes: 500_000_000_000)
        #expect(ServerMonitorPresentation.spoken(roomy) == "C:, 465 GB free of 931 GB")
        #expect(ServerMonitorPresentation.diskCount(HostSnapshot(disks: [full, roomy])) == "2 with space the hub can see")
    }

    @Test func whatIsPlayingIsItsDeviceItsAppAndHowItPlays() {
        #expect(ServerMonitorPresentation.sessionLine(HostSession(title: "T", device: "TV", client: "Jellyfin", method: "Direct play"))
            == "TV · Jellyfin · Direct play")
        #expect(ServerMonitorPresentation.sessionLine(HostSession(title: "T", device: "iPad", client: "JellyHub", method: "Transcoding",
                                                                   paused: true)) == "iPad · JellyHub · Paused")
        #expect(ServerMonitorPresentation.sessionLine(HostSession(title: "T", device: " ", client: "", method: "")) == "Playing")
        #expect(ServerMonitorPresentation.playingNote(ServerMonitor()) == "Nothing is playing")
        #expect(ServerMonitorPresentation.playingNote(ServerMonitor(sessions: [HostSession()])) == nil)
        #expect(ServerMonitorPresentation.playingNote(ServerMonitor(sessions: [HostSession()], sessionWarning: "Jellyfin did not answer"))
            == "Jellyfin did not answer", "the hub's warning is said even beside sessions")
    }

    @Test func containersListRunningFirstThenByNameAndAnUnhealthyOneIsDegraded() {
        let values = [HostContainer(name: "zeta", image: "z:1", state: "exited", status: "Exited (0) 2 days ago"),
                      HostContainer(name: "beta", image: "b:2", state: "running", status: "Up 4 days (Unhealthy)"),
                      HostContainer(name: "alpha", image: "", state: "running", status: "Up 4 days")]
        let rows = ServerMonitorPresentation.containers(values)
        #expect(rows.map(\.name) == ["alpha", "beta", "zeta"])
        #expect(rows.map(\.state) == ["running", "degraded", "exited"])
        #expect(rows.map(\.tone) == [.available, .pending, .muted])
        #expect(rows[0].line == "Up 4 days" && rows[1].line == "Up 4 days (Unhealthy) · b:2")
        #expect(ServerMonitorPresentation.containerCount(values) == "2 of 3 running")
        #expect(ServerMonitorPresentation.containerCount([]) == "")
        #expect(ServerMonitorPresentation.containerNote(ServerMonitor()) == "Docker reports no containers")
        #expect(ServerMonitorPresentation.containerNote(ServerMonitor(containers: values)) == nil)
        #expect(ServerMonitorPresentation.containerNote(ServerMonitor(dockerWarning: "Docker is not running")) == "Docker is not running")
    }

    @Test func aDotsColourFollowsTheStateAnyDashboardReports() {
        #expect(["up", "running", "healthy"].allSatisfy { DashboardTone.of($0) == .available })
        #expect(["misconfigured", "restarting", "paused", "created", "degraded"].allSatisfy { DashboardTone.of($0) == .pending })
        #expect(["disabled", "exited"].allSatisfy { DashboardTone.of($0) == .muted })
        #expect(["down", "unavailable", "dead", ""].allSatisfy { DashboardTone.of($0) == .danger })
        #expect(DashboardTone.of("UP") == .available)
    }
}

struct DemoNotificationsTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport())

    private func read(_ limits: NotificationLimits = NotificationLimits()) async throws -> NotificationsResponse {
        try await hub.fetch(HubEndpoints.notifications(limits), as: NotificationsResponse.self)
    }

    @Test func everyServiceHasAColumnAndOneCannotBeReached() async throws {
        let response = try await read()
        #expect(response.sections.map(\.service) == ["sonarr", "radarr", "bazarr", "bookkeeprr", "kavita", "storyteller"])
        #expect(response.sections.first { $0.service == "storyteller" }?.state == "unavailable")
        #expect(response.partial.map(\.service).contains("storyteller"))
        // Storyteller is unreachable and Sonarr has a warning: two things need attention.
        #expect(response.attentionCount == 2)
    }

    @Test func aHealthWarningIsPinnedAndTheHistoryIsNewestFirst() async throws {
        let sonarr = try #require(try await read().sections.first { $0.service == "sonarr" })
        #expect(sonarr.items.first?.active == true && sonarr.items.first?.kind == "health")
        let times = sonarr.items.dropFirst().map(\.occurredAt)
        #expect(times == times.sorted(by: >), "newest first")
    }

    @Test func theHistoryLengthIsTheOneAskedForAndHealthIsNotCountedInIt() async throws {
        let short = try await read(NotificationLimits(sonarr: 3, radarr: 20, bazarr: 40))
        let sonarr = try #require(short.sections.first { $0.service == "sonarr" })
        #expect(sonarr.items.count == 4, "one warning and three of the history")
        let long = try await read(NotificationLimits(sonarr: 100, radarr: 20, bazarr: 40))
        #expect(long.sections.first { $0.service == "sonarr" }?.items.count == 31, "the warning and thirty in the history")
        let twenty = try await read(NotificationLimits(sonarr: 20, radarr: 20, bazarr: 40))
        #expect(twenty.sections.first { $0.service == "sonarr" }?.items.count == 21)
    }

    @Test func theDemoSeenListLeavesTheNewestUnreadOnEachSideAndTheOlderSeen() async throws {
        let response = try await read()
        let store = NotificationReadStore(snapshot: DemoNotifications.baseline)
        let unread = store.observe(response.sections)
        #expect(unread == ["sonarr:history:9001", "sonarr:history:9000", "sonarr:history:8999", "radarr:history:4100",
                           "bazarr:episode:a1", "bookkeeprr:1"])
        #expect(NotificationsPresentation.unread(on: .media, sections: response.sections, unread: unread) == 5)
        #expect(NotificationsPresentation.unread(on: .books, sections: response.sections, unread: unread) == 1)
    }

    @Test func theDemoMonitorHasContainersASessionAndAWarningForTheMonitorPage() async throws {
        let monitor = try await hub.fetch(HubEndpoints.monitor, as: ServerMonitor.self)
        let rows = ServerMonitorPresentation.containers(monitor.containers)
        #expect(rows.map(\.name) == ["cleanuparr", "jellyseerr", "flaresolverr"])
        #expect(rows.map(\.state) == ["degraded", "running", "exited"])
        #expect(monitor.sessions.count == 2 && monitor.sessions.last?.paused == true)
        #expect(monitor.host.warnings == ["G:\\ space unavailable"])
    }
}

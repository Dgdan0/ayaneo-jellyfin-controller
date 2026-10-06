import Foundation
import Testing
@testable import HubKit

/// The Activity tab (#29): Android's `ActivityDashboardTest`,
/// `ActivityDiagnosisTest` and `ReleaseNamesTest`, case for case, then the
/// transfer rows' words, the speed limits and the routes.
struct ActivityTests {
    private let zone = TimeZone(identifier: "Asia/Jerusalem")!
    // Friday 2 October 2026, 09:00 in Jerusalem.
    private let now = ISO8601DateFormatter().date(from: "2026-10-02T06:00:00Z")!

    private func episode(_ date: String, _ at: String, _ key: String, _ title: String, _ season: Int, _ number: Int,
                         hasFile: Bool) -> CalendarItem {
        CalendarItem(id: "\(key):\(season):\(number)", media: MediaRef(type: "series", title: title, key: key), date: date, at: at,
                     releaseType: "Episode", season: season, episode: number, hasFile: hasFile)
    }

    // MARK: The agenda

    @Test func theAgendaStartsWithWhatNeverArrivedThenRunsFromYesterday() {
        let items = [
            episode("2026-09-24", "2026-09-24T19:00:00Z", "tv:1", "Last Seen", 1, 3, hasFile: false),
            episode("2026-09-30", "2026-09-30T04:00:00Z", "tv:2", "Ted Lasso", 4, 9, hasFile: true),
            episode("2026-10-01", "2026-10-01T18:00:00Z", "tv:1", "Last Seen", 1, 4, hasFile: true),
            episode("2026-10-02", "2026-10-02T04:00:00Z", "tv:3", "Dark Matter", 2, 6, hasFile: false),
            episode("2026-10-03", "2026-10-03T17:00:00Z", "tv:4", "Shogun", 2, 1, hasFile: false),
        ]
        let agenda = ActivityDashboard.agenda(items, now: now, zone: zone)
        #expect(agenda.map(\.media.title) == ["Last Seen", "Last Seen", "Dark Matter", "Shogun"])
        #expect(agenda[0].heading == "Missed · Thu 24 Sep")
        #expect(agenda[0].state == .missing)
        #expect(agenda[1].heading == "Yesterday · Thu 1 Oct")
        #expect(agenda[1].state == .inLibrary)
        // Aired three hours ago: not missing yet, Sonarr has barely had time to look.
        #expect(agenda[2].state == .aired)
        #expect(agenda[2].line == "S2E6 · 07:00")
        #expect(agenda[3].heading == "Tomorrow · Sat 3 Oct")
        #expect(agenda[3].state == .soon)
    }

    @Test func aTitleAppearsOncePerDayHoweverManyEpisodesAir() {
        let items = (1...3).map { episode("2026-10-05", "2026-10-05T01:00:00Z", "tv:9", "Lanterns", 1, $0, hasFile: false) }
        let agenda = ActivityDashboard.agenda(items, now: now, zone: zone)
        #expect(agenda.count == 1)
        #expect(agenda.first?.line == "S1E1 · 04:00")
        #expect(agenda.first?.heading == "Mon 5 Oct")
    }

    @Test func theAgendaIsCapped() {
        let items = (3...12).map { episode(String(format: "2026-10-%02d", $0), "", "tv:\($0)", "Show \($0)", 1, 1, hasFile: false) }
        #expect(ActivityDashboard.agenda(items, now: now, zone: zone).count == 5)
    }

    @Test func aFilmIsNamedByItsReleaseAndTheAgendaAsksForAMonthAroundToday() {
        let film = CalendarItem(id: "radarr:3", media: MediaRef(type: "movie", title: "Dune: Part Two", key: "tmdb:movie:693134"),
                                date: "2026-10-04", releaseType: "Digital release")
        let agenda = ActivityDashboard.agenda([film], now: now, zone: zone)
        #expect(agenda.first?.line == "Digital release")
        #expect(agenda.first?.heading == "Sun 4 Oct")
        let range = ActivityDashboard.agendaRange(now: now, zone: zone)
        #expect(range.start == "2026-09-18")
        #expect(range.end == "2026-10-19")
    }

    @Test func dayHeadings() {
        #expect(ActivityDashboard.heading("2026-10-02", today: "2026-10-02") == "Today · Fri 2 Oct")
        #expect(ActivityDashboard.heading("2026-10-07", today: "2026-10-02") == "Wed 7 Oct")
    }

    // MARK: What needs attention

    @Test func attentionListsBrokenTransfersThenServicesThatAreDownThenFullDisks() {
        let activity = ActivityResponse(items: [
            ActivityItem(id: "qbit:aa", mediaTitle: "Severance", stage: Stages.stuck,
                         diagnosis: ActivityDiagnosis(title: "Sonarr can't import it", needsAttention: true)),
            ActivityItem(id: "qbit:bb", mediaTitle: "Dune", stage: Stages.downloading),
        ])
        let health = HealthResponse(hub: HubInfo(), services: [
            ServiceHealth(name: "radarr", state: "up"),
            ServiceHealth(name: "qbittorrent", state: "down"),
        ])
        let disks = [HostDisk(name: "C:\\", totalBytes: 500, availableBytes: 100),
                     HostDisk(name: "E:\\", totalBytes: 4_000_000_000_000, availableBytes: 38_000_000_000)]
        let attention = ActivityDashboard.attention(activity, health: health, disks: disks)
        #expect(attention.map(\.title) == ["Severance", "qBittorrent isn't responding", "E: is nearly full"])
        #expect(attention[0].detail == "Sonarr can't import it")
        #expect(attention[0].transferId == "qbit:aa")
        #expect(attention[1].transferId == "")
        #expect(attention[1].detail == "Nothing can download, and transfers can't be shown, until it's running again.")
        #expect(attention[2].detail == "35.4 GB free of 3.6 TB. New downloads may fail.")
    }

    @Test func aBrokenTransferWithoutADiagnosisSaysWhatTheServicesSaid() {
        let activity = ActivityResponse(items: [
            ActivityItem(id: "qbit:cc", title: "The.Expanse.S03.1080p.BluRay.x265-RARBG", stage: Stages.stuck,
                         warnings: ["missingFiles"]),
            ActivityItem(id: "qbit:dd", mediaTitle: "Last Seen", stage: Stages.stuck,
                         arr: ArrRef(service: "sonarr", problem: "Found executable file with extension: '.exe'")),
        ])
        let attention = ActivityDashboard.attention(activity, health: nil, disks: [])
        #expect(attention.map(\.title) == ["The Expanse · Season 3 · 1080p", "Last Seen"])
        #expect(attention.map(\.detail) == ["qBittorrent cannot find the files on disk", "Found executable file with extension: '.exe'"])
        // A service that needs setting up says what the hub found.
        let health = HealthResponse(hub: HubInfo(), services: [
            ServiceHealth(name: "storyteller", state: "misconfigured", lastError: "no API key configured")])
        let services = ActivityDashboard.attention(nil, health: health, disks: [])
        #expect(services.map(\.title) == ["Storyteller needs setting up"])
        #expect(services.map(\.detail) == ["no API key configured"])
    }

    @Test func onlyMovingTransfersGetARow() {
        let activity = ActivityResponse(items: [
            ActivityItem(id: "1", stage: Stages.seeding),
            ActivityItem(id: "2", stage: Stages.downloading),
            ActivityItem(id: "3", stage: Stages.stuck),
            ActivityItem(id: "4", stage: Stages.queued),
        ])
        #expect(ActivityDashboard.transfers(activity).map(\.id) == ["2", "4"])
    }

    @Test func aTransferLineSaysHowFarAndHowLong() {
        let item = ActivityItem(stage: Stages.downloading, sizeBytes: 2 << 30, remainingBytes: 1 << 30, etaSeconds: 240)
        #expect(ActivityDashboard.transferLine(item) == "1.0 GB of 2.0 GB · 4m left")
        #expect(ActivityDashboard.transferLine(ActivityItem(stage: Stages.queued, sizeBytes: 2 << 30)) == "2.0 GB · Queued")
        // Moving with no estimate: its speed.
        #expect(ActivityDashboard.transferLine(ActivityItem(stage: Stages.downloading, speedBps: 1 << 20)) == "1.0 MB/s")
    }

    @Test func serviceLinesAreShort() {
        #expect(ActivityDashboard.serviceMeta(ServiceHealth(name: "radarr", state: "up", latencyMs: 3, version: "6.4.4.10685"))
                == "6.4.4 · 3 ms")
        #expect(ActivityDashboard.serviceMeta(ServiceHealth(name: "qbittorrent", state: "up", latencyMs: 1, version: "v5.0.4"))
                == "5.0.4 · 1 ms")
        #expect(ActivityDashboard.serviceMeta(ServiceHealth(name: "kavita", state: "up", latencyMs: 21)) == "21 ms")
        #expect(ActivityDashboard.serviceMeta(ServiceHealth(name: "qbittorrent", state: "down")) == "Not responding")
        #expect(ActivityDashboard.serviceMeta(ServiceHealth(name: "storyteller", state: "misconfigured")) == "Needs setup")
        let up = HealthResponse(hub: HubInfo(), services: [ServiceHealth(name: "a", state: "up"), ServiceHealth(name: "b", state: "up")])
        #expect(ActivityDashboard.servicesSummary(up) == "All 2 up")
        let one = HealthResponse(hub: HubInfo(), services: [ServiceHealth(name: "a", state: "up"), ServiceHealth(name: "b", state: "down")])
        #expect(ActivityDashboard.servicesSummary(one) == "1 not responding")
    }

    @Test func aLoopbackDashboardAddressCannotBeOpenedFromThisDevice() {
        #expect(ActivityDashboard.reachable("https://ayaneo-media-pc.tail737e96.ts.net:8920"))
        #expect(ActivityDashboard.reachable("http://100.95.23.41:9696"))
        #expect(!ActivityDashboard.reachable("http://127.0.0.1:5000"))
        #expect(!ActivityDashboard.reachable("http://localhost:3000"))
        #expect(!ActivityDashboard.reachable("http://[::1]:8080"))
        #expect(!ActivityDashboard.reachable(""))
    }

    @Test func theHeadingsLineSaysWhatIsMovingWhatNeedsALookAndHowTheServicesAre() {
        let idle = ActivityResponse(items: [ActivityItem(id: "a", stage: Stages.seeding)])
        let health = HealthResponse(hub: HubInfo(), services: (0..<11).map { ServiceHealth(name: "s\($0)", state: "up") }
            + [ServiceHealth(name: "off", state: "disabled")])
        #expect(ActivityDashboard.headline(idle, attention: 1, health: health)
                == "Nothing downloading · 1 thing needs attention · all 11 services up")
        let busy = ActivityResponse(items: [ActivityItem(id: "a", stage: Stages.downloading), ActivityItem(id: "b", stage: Stages.downloading)])
        let down = HealthResponse(hub: HubInfo(), services: [ServiceHealth(name: "a", state: "up"), ServiceHealth(name: "b", state: "down")])
        #expect(ActivityDashboard.headline(busy, attention: 0, health: down) == "2 downloading · 1 service not responding")
        // Before anything has loaded there is nothing to say.
        #expect(ActivityDashboard.headline(nil, attention: 0, health: nil) == "")
        #expect(ActivityDashboard.headline(nil, attention: 3, health: nil) == "3 things need attention")
    }

    @Test func oneTransferNeedsAttentionSeveralNeedIt() {
        #expect(ActivityDashboard.needAttention(1) == "1 transfer needs attention")
        #expect(ActivityDashboard.needAttention(3) == "3 transfers need attention")
    }

    @Test func aDiskUnderATenthFreeIsNearlyFull() {
        #expect(ActivityDashboard.lowSpace(HostDisk(name: "E:\\", totalBytes: 1_000, availableBytes: 99)))
        #expect(!ActivityDashboard.lowSpace(HostDisk(name: "E:\\", totalBytes: 1_000, availableBytes: 100)))
        #expect(!ActivityDashboard.lowSpace(HostDisk(name: "?", totalBytes: 0)))
        #expect(ActivityDashboard.diskName("E:\\") == "E:")
        #expect(ActivityDashboard.diskName("/mnt/media/") == "/mnt/media")
        #expect(HostDisk(name: "E:\\", totalBytes: 1_000, availableBytes: 250).usedFraction == 0.75)
    }

    // MARK: The model

    @Test func anOlderHubsWarningsStillMarkATransferBroken() throws {
        let item = try JSONDecoder().decode(ActivityItem.self, from: Data(#"{"id":"old","warnings":["unmatched_download"]}"#.utf8))
        #expect(item.isBroken)
        #expect(item.diagnosis == nil)
        #expect(item.etaSeconds == -1, "no estimate is -1, never a made-up number")
    }

    @Test func theHubsDiagnosisDecidesWhetherWarningsAreAProblem() throws {
        let item = ActivityItem(diagnosis: ActivityDiagnosis(needsAttention: false), warnings: ["usenet_no_client_detail"])
        #expect(!item.isBroken)
        let stalled = try JSONDecoder().decode(ActivityItem.self, from: Data(
            #"{"stage":"queued","diagnosis":{"code":"waiting_peers","needsAttention":true}}"#.utf8))
        #expect(stalled.isBroken, "a stalled download is a problem whatever stage it was folded into")
    }

    @Test func importingCountsAsMovingSoAnImportIsCaughtLive() {
        #expect(ActivityResponse(items: [ActivityItem(stage: Stages.importing)]).anyActive)
        #expect(!ActivityResponse(items: [ActivityItem(stage: Stages.seeding), ActivityItem(stage: Stages.stopped)]).anyActive)
        #expect(Stages.label("checkingResumeData") == "checkingResumeData", "an unknown stage is shown as it came")
        #expect(Stages.label("") == "Unknown")
    }

    // MARK: Release names

    @Test func anEpisodeReleaseReadsAsTitleEpisodeAndQuality() {
        #expect(ReleaseNames.readable("dark.matter.2024.s02e06.1080p.web.h264-cakes[EZTVx.to].mkv")
                == "Dark Matter (2024) · S2E6 · 1080p")
        #expect(ReleaseNames.readable("Lanterns S01E07 1080p AMZN WEB-DL DD 5 1 H 264-playWEB") == "Lanterns · S1E7 · 1080p")
        #expect(ReleaseNames.readable("Slow.Horses.S06E03.Resurrection.2160p.ATVP.WEB-DL.DDP5.1.DV.HDR.H.265-NTb")
                == "Slow Horses · S6E3 · 2160p")
    }

    @Test func aSeasonPackAndAFilmAnchorOnTheSeasonAndTheYear() {
        #expect(ReleaseNames.readable("The.Mentalist.S01.1080p.BluRay.x264-SHORTBREHD") == "The Mentalist · Season 1 · 1080p")
        #expect(ReleaseNames.readable("UNABOMBER 2026 1080p WEB H264-CUPCAKES") == "UNABOMBER (2026) · 1080p")
        #expect(ReleaseNames.readable("2001.A.Space.Odyssey.1968.2160p.UHD.BluRay") == "2001 A Space Odyssey (1968) · 2160p")
    }

    @Test func aNameWithNothingToAnchorOnIsLeftAlone() {
        #expect(ReleaseNames.readable("Some Concert Recording") == "Some Concert Recording")
        #expect(ReleaseNames.readable("") == "")
    }

    @Test func aTransferWithAnArrTitleKeepsItAndOneWithoutReadsItsReleaseName() {
        #expect(ActivityItem(title: "The.Mentalist.S01.1080p", mediaTitle: "The Mentalist").headline == "The Mentalist")
        #expect(ActivityItem(title: "Ted.Lasso.S04E08.PROPER.1080p.WEB.h264-TRB").headline == "Ted Lasso · S4E8 · 1080p")
        #expect(ActivityItem(title: "Ted.Lasso.S04E08.PROPER.1080p.WEB.h264-TRB").subline == "")
        #expect(ActivityItem(title: "x.s01e01", mediaTitle: "X").subline == "x.s01e01")
    }

    // MARK: A transfer's row

    @Test func aRowsFiguresSayHowMuchHowFastHowLongAndFromWhere() {
        let pack = ActivityItem(stage: Stages.downloading, sizeBytes: 40 << 30, remainingBytes: 30 << 30, speedBps: 2 << 20,
                                etaSeconds: 15_840, seeds: 12, peers: 3, indexer: "Demo Indexer", queueItems: 23)
        #expect(TransferPresentation.stats(pack) == "23 episodes · 10.0 GB of 40.0 GB · 2.0 MB/s · 4h 24m left · 12s/3p · Demo Indexer")
        // Stopped: no time left; nothing at all: the protocol.
        let stopped = ActivityItem(stage: Stages.stopped, sizeBytes: 1 << 30, etaSeconds: 300)
        #expect(TransferPresentation.stats(stopped) == "1.0 GB")
        #expect(TransferPresentation.stats(ActivityItem(protocol: "usenet")) == "usenet")
        #expect(TransferPresentation.trailing(ActivityItem(stage: Stages.seeding, progress: 1, uploadBps: 400 << 10)) == "↑ 400 KB/s")
        #expect(TransferPresentation.trailing(ActivityItem(stage: Stages.downloading, progress: 0.62)) == "62%")
        #expect(TransferPresentation.trailing(ActivityItem(stage: Stages.queued)) == "")
        #expect(!TransferPresentation.showsBar(ActivityItem(stage: Stages.stuck)))
        #expect(TransferPresentation.showsBar(ActivityItem(stage: Stages.stuck, progress: 0.4)))
    }

    @Test func theNoteKeepsTheServicesOwnWords() {
        let blocked = ActivityItem(stage: Stages.stuck,
                                   diagnosis: ActivityDiagnosis(title: "Sonarr won't import it", needsAttention: true),
                                   arr: ArrRef(problem: "Found executable file with extension: '.exe'"), warnings: ["missingFiles"])
        #expect(TransferPresentation.note(blocked)
                == "Sonarr won't import it · Found executable file with extension: '.exe' · qBittorrent cannot find the files on disk")
        // A diagnosis that is not a problem says nothing here; a warning nobody named is shown as it came.
        let fine = ActivityItem(diagnosis: ActivityDiagnosis(title: "Downloading normally"), warnings: ["somethingNew"])
        #expect(TransferPresentation.note(fine) == "somethingNew")
        #expect(TransferPresentation.tone(Stages.importing) == .waiting)
        #expect(TransferPresentation.tone(Stages.done) == .good)
        #expect(TransferPresentation.tone(Stages.stuck) == .bad)
        #expect(TransferPresentation.tone(Stages.queued) == .quiet)
    }

    @Test func theMenuIsBuiltFromTheHubsActionsAndTheDestructiveOnesAreMarked() {
        let item = ActivityItem(stage: Stages.downloading, priority: 2, arr: ArrRef(service: "sonarr", queueId: 501),
                                actions: ["stop", "priority_up", "delete", "delete_with_data", "arr_remove",
                                          "arr_blocklist_and_search", "something_new"])
        let choices = TransferPresentation.choices(item)
        #expect(choices.map(\.label) == ["Check transfer status", "Stop", "Move up queue", "Remove from client",
                                         "Remove and delete files", "Remove from Sonarr", "Blocklist and search again",
                                         "Transfer details"])
        #expect(choices.filter(\.danger).map(\.id) == ["delete", "delete_with_data", "arr_remove", "arr_blocklist_and_search"])
        #expect(choices[2].detail == "Current queue position: 2")
        #expect(TransferPresentation.toggle(item)?.label == "Stop")
        // A read-only token gets no actions, so no buttons that would fail.
        let readOnly = ActivityItem(stage: Stages.stuck, diagnosis: ActivityDiagnosis(needsAttention: true))
        #expect(TransferPresentation.choices(readOnly).map(\.id) == ["diagnosis", "details"])
        #expect(TransferPresentation.choices(readOnly).first?.label == "Why isn't it working?")
        #expect(TransferPresentation.toggle(readOnly) == nil)
        #expect(TransferPresentation.toggle(ActivityItem(actions: ["start", "delete"]))?.label == "Start")
    }

    @Test func whatAnActionDidIsSaidInThePastTense() {
        #expect(TransferPresentation.done("stop", headline: "Severance") == "Stopped Severance")
        #expect(TransferPresentation.done("delete_with_data", headline: "Dune") == "Deleted Dune")
        #expect(TransferPresentation.done("arr_blocklist_and_search", headline: "Dune") == "Blocklisted, searching again for Dune")
        #expect(TransferPresentation.done("priority_down", headline: "Dune") == "Updated queue priority for Dune")
    }

    @Test func theDiagnosisSheetSaysWhatTheHubSaidOrThatItSaidNothing() {
        let said = TransferPresentation.diagnosis(ActivityItem(stage: Stages.stuck, diagnosis: ActivityDiagnosis(
            title: "Sonarr won't import it", explanation: "Why.", evidence: ["Found executable"], nextStep: "Blocklist it.",
            needsAttention: true)))
        #expect(said.title == "Sonarr won't import it")
        #expect(said.nextStep == "Blocklist it.")
        #expect(said.evidence == ["Found executable"])
        let unsaid = TransferPresentation.diagnosis(ActivityItem(stage: Stages.queued))
        #expect(unsaid.title == "Detailed diagnosis unavailable")
        #expect(unsaid.explanation == "This hub does not explain transfers yet. The latest stage is Queued.")
    }

    @Test func thePagesLinesSayHowManyAndWhatIsMoving() {
        let summary = ActivitySummary(downloading: 2, queued: 1, seeding: 3, stuck: 1, downSpeedBytes: 3 << 20, upSpeedBytes: 300 << 10)
        #expect(TransferPresentation.summary(summary) == "2 downloading · 1 queued · 3 seeding · 1 stuck   ↓ 3.0 MB/s  ↑ 300 KB/s")
        #expect(TransferPresentation.summary(ActivitySummary()) == "0 downloading")
        #expect(TransferPresentation.status(shown: 7, total: 7, attentionOnly: false, includeFinished: false) == "7 items")
        #expect(TransferPresentation.status(shown: 9, total: 9, attentionOnly: false, includeFinished: true)
                == "9 items · including finished")
        #expect(TransferPresentation.status(shown: 2, total: 7, attentionOnly: true, includeFinished: false)
                == "2 transfers need attention")
        #expect(TransferPresentation.status(shown: 0, total: 7, attentionOnly: true, includeFinished: false)
                == "No transfers need attention.")
        #expect(TransferPresentation.status(shown: 0, total: 0, attentionOnly: false, includeFinished: false)
                == "Nothing running. Show finished lists what is done.")
        #expect(TransferPresentation.attentionFilter(count: 2, on: false) == "Needs attention · 2")
        #expect(TransferPresentation.attentionFilter(count: 1, on: true) == "Showing 1 that needs attention")
    }

    // MARK: Speed limits

    @Test func aCapReadsInKibPerSecondAndNothingIsUnlimited() {
        #expect(BandwidthPresentation.rate(0) == "Unlimited")
        #expect(BandwidthPresentation.rate(10 * 1024) == "10 KiB/s")
        #expect(BandwidthPresentation.rate(1_536 * 1024) == "1,536 KiB/s")
        #expect(BandwidthPresentation.rate(1_536 * 1024 + 512) == "1,536.5 KiB/s")
        #expect(BandwidthPresentation.field(10 * 1024) == "10")
        #expect(BandwidthPresentation.field(1_536) == "1.5")
        #expect(BandwidthPresentation.field(0) == "0")
    }

    @Test func anEnteredCapIsANumberFromNothingToOneGibPerSecond() {
        #expect(BandwidthPresentation.parse("10") == 10_240)
        #expect(BandwidthPresentation.parse(" 1.5 ") == 1_536)
        #expect(BandwidthPresentation.parse("0") == 0)
        #expect(BandwidthPresentation.parse("1048576") == 1 << 30)
        #expect(BandwidthPresentation.parse("1048577") == nil)
        #expect(BandwidthPresentation.parse("-1") == nil)
        #expect(BandwidthPresentation.parse("fast") == nil)
        #expect(BandwidthPresentation.parse("") == nil)
    }

    @Test func theLimitsNotesSayWhatElseIsTrue() {
        let mine = BandwidthState(queueingEnabled: true, modeSwitchSupported: true, canControl: true)
        #expect(BandwidthPresentation.notes(mine) == ["Each transfer's actions can move it up or down the queue."])
        let old = BandwidthState(schedulerEnabled: true, canControl: true)
        #expect(BandwidthPresentation.notes(old) == [
            "qBittorrent's own schedule is on, so it may switch between these by itself.",
            "Queueing is off in qBittorrent, so transfers have no order to change.",
            "Switching between them here needs qBittorrent 5 or later.",
        ])
        #expect(BandwidthPresentation.notes(BandwidthState(queueingEnabled: true)).last
                == "This token can read these limits but not change them.")
        #expect(BandwidthPresentation.name(quiet: true) == "Quiet")
        #expect(BandwidthState(mode: "alternative").quiet)
    }

    @Test func aChangeWritesOnlyWhatIsSet() throws {
        func keys(_ change: BandwidthChange) throws -> Set<String> {
            let object = try JSONSerialization.jsonObject(with: change.encoded()) as? [String: Any]
            return Set(object?.keys.map { $0 } ?? [])
        }
        #expect(try keys(BandwidthChange(mode: "alternative")) == ["mode"])
        #expect(try keys(BandwidthChange(limitsFor: "normal", downloadBps: 0, uploadBps: 1024))
                == ["limitsFor", "downloadBps", "uploadBps"])
    }

    // MARK: Routes

    @Test func aTransfersIdArrivesWholeAndADeleteSaysWhetherTheFilesGo() {
        let stop = HubEndpoints.downloadAction(id: "qbit:abc", action: "stop")
        #expect(stop.path == "/v1/downloads/qbit%3Aabc/stop")
        #expect(stop.method == .post)
        #expect(HubEndpoints.downloadDelete(id: "qbit:abc", deleteFiles: false).path == "/v1/downloads/qbit%3Aabc?deleteFiles=false")
        #expect(HubEndpoints.downloadDelete(id: "qbit:abc", deleteFiles: true).method == .delete)
        #expect(HubEndpoints.queueRemove(service: "sonarr", queueId: 501, blocklist: true, search: true).path
                == "/v1/queue/sonarr/501/remove?removeFromClient=true&blocklist=true&search=true")
        #expect(HubEndpoints.activity().path == "/v1/activity")
        #expect(HubEndpoints.activity(includeFinished: true).path == "/v1/activity?all=true")
        #expect(HubEndpoints.setBandwidth(BandwidthChange(mode: "normal")).method == .post)
    }
}

/// The demo hub keeps the hub's rules for transfers and limits. One at a
/// time, since they change the demo's own state.
@Suite(.serialized)
struct DemoActivityTests {
    private let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token),
                                screens: DemoTransport(), sleep: { _ in })

    private func activity(all: Bool = false) async throws -> ActivityResponse {
        try await hub.fetch(HubEndpoints.activity(includeFinished: all), as: ActivityResponse.self)
    }

    @Test func everyStateIsThereProblemsFirstAndFinishedOnesOnlyWhenAsked() async throws {
        DemoActivity.reset()
        let shown = try await activity()
        #expect(shown.items.first?.isBroken == true)
        #expect(Set(shown.items.map(\.stage)) == [Stages.stuck, Stages.downloading, Stages.importing, Stages.queued, Stages.stopped])
        #expect(shown.summary.seeding == 1, "the summary counts what is hidden")
        let every = try await activity(all: true)
        #expect(Set(every.items.map(\.stage)).isSuperset(of: [Stages.seeding, Stages.done]))
        #expect(every.items.contains { $0.queueItems == 23 })
        #expect(every.items.contains { $0.mediaTitle.isEmpty && $0.warnings == ["missingFiles"] })
    }

    @Test func stoppingOffersStartAndStartingOffersStop() async throws {
        DemoActivity.reset()
        let moving = try #require(try await activity().items.first { $0.mediaTitle == "Severance" })
        #expect(moving.can("stop"))
        try await hub.send(HubEndpoints.downloadAction(id: moving.id, action: "stop"))
        let stopped = try #require(try await activity().items.first { $0.id == moving.id })
        #expect(stopped.stage == Stages.stopped)
        #expect(stopped.can("start") && !stopped.can("stop"))
        #expect(stopped.speedBps == 0)
        try await hub.send(HubEndpoints.downloadAction(id: moving.id, action: "start"))
        #expect(try await activity().items.first { $0.id == moving.id }?.stage == Stages.downloading)
        // An action a transfer does not offer is refused.
        await #expect(throws: HubFailure.self) {
            try await hub.send(HubEndpoints.downloadAction(id: moving.id, action: "start"))
        }
    }

    @Test func aRemovedTransferIsGoneAndAQueueRowGoesWithItsTransfer() async throws {
        DemoActivity.reset()
        let items = try await activity().items
        let broken = try #require(items.first { $0.warnings == ["missingFiles"] })
        try await hub.send(HubEndpoints.downloadDelete(id: broken.id, deleteFiles: true))
        let blocked = try #require(items.first { $0.mediaTitle == "Last Seen" })
        let arr = try #require(blocked.arr)
        try await hub.send(HubEndpoints.queueRemove(service: arr.service, queueId: arr.queueId, blocklist: true, search: true))
        let after = try await activity().items.map(\.id)
        #expect(!after.contains(broken.id) && !after.contains(blocked.id))
        // Gone is gone.
        await #expect(throws: HubFailure.self) {
            try await hub.send(HubEndpoints.downloadDelete(id: broken.id, deleteFiles: false))
        }
        DemoActivity.reset()
    }

    @Test func theLimitsChangeAsTheHubWouldAndRefuseWhatItWouldRefuse() async throws {
        DemoActivity.reset()
        let before = try await hub.fetch(HubEndpoints.bandwidth, as: BandwidthState.self)
        #expect(!before.quiet && before.canControl && before.modeSwitchSupported)
        let quiet = try await hub.fetch(HubEndpoints.setBandwidth(BandwidthChange(mode: "alternative")), as: BandwidthState.self)
        #expect(quiet.quiet)
        let capped = try await hub.fetch(HubEndpoints.setBandwidth(BandwidthChange(limitsFor: "alternative", downloadBps: 10_240,
                                                                                    uploadBps: 0)), as: BandwidthState.self)
        #expect(capped.alternativeDownloadBps == 10_240 && capped.alternativeUploadBps == 0)
        #expect(capped.downloadBps == before.downloadBps, "the other set is left alone")
        // Past 1 GiB/s, caps with no set named, or nothing to change: the hub's 400.
        for change in [BandwidthChange(limitsFor: "normal", downloadBps: (1 << 30) + 1), BandwidthChange(downloadBps: 5),
                       BandwidthChange(), BandwidthChange(mode: "fast")] {
            await #expect(throws: HubFailure.self) {
                _ = try await hub.fetch(HubEndpoints.setBandwidth(change), as: BandwidthState.self)
            }
        }
        DemoActivity.reset()
    }

    @Test func thePCHasANearlyFullDiskAndTheAgendaAMonthAroundToday() async throws {
        let monitor = try await hub.fetch(HubEndpoints.monitor, as: ServerMonitor.self)
        #expect(monitor.host.disks.map(\.name) == ["C:\\", "E:\\", "F:\\"])
        #expect(monitor.host.disks.filter(ActivityDashboard.lowSpace).map(\.name) == ["E:\\"])
        let zone = TimeZone(identifier: "Asia/Jerusalem")!
        let range = ActivityDashboard.agendaRange(now: Date(), zone: zone)
        let month = try await hub.fetch(HubEndpoints.calendar(start: range.start, end: range.end, timezone: zone.identifier),
                                        as: CalendarResponse.self)
        let agenda = ActivityDashboard.agenda(month.items, now: Date(), zone: zone)
        #expect(agenda.first?.heading.hasPrefix("Missed") == true)
        #expect(agenda.contains { $0.state == .inLibrary })
        // The Upcoming page's week is still the Discover demo's.
        let week = try await hub.fetch(HubEndpoints.calendar(start: "2026-10-05", end: "2026-10-12", timezone: "UTC"),
                                       as: CalendarResponse.self)
        #expect(week.items.first?.media.title == "Severance")
        #expect(week.items.first?.date == "2026-10-05")
    }
}

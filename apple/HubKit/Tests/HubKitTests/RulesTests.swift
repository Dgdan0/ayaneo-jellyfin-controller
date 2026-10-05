import Foundation
import Testing
@testable import HubKit

/// The cases of Android's `HubConnectionValidationTest`, plus the Apple
/// address rule.
struct ConnectionTests {
    let token = String(repeating: "a", count: 43)

    @Test func httpsAddressAndCompleteTokenAreAccepted() {
        #expect(HubConnectionValidation.error(normalizedAddress: "https://ayaneo.example.ts.net", token: token) == nil)
    }

    @Test func missingTokenIsExplainedBeforeAnyNetworkRequest() {
        #expect(HubConnectionValidation.error(normalizedAddress: "https://ayaneo.example.ts.net", token: "")
            == "Paste the Hub access token")
    }

    @Test func shortTokenIsNotSentToTheHub() {
        #expect(HubConnectionValidation.error(normalizedAddress: "https://ayaneo.example.ts.net", token: "too-short")
            == "The Hub access token is incomplete")
    }

    @Test func remotePlainHttpIsRefused() {
        #expect(HubConnectionValidation.error(normalizedAddress: "http://ayaneo.example.ts.net", token: token)
            == "Enter a complete HTTPS address")
    }

    @Test func loopbackHttpIsAllowedForDevelopment() {
        #expect(HubConnectionValidation.error(normalizedAddress: "http://127.0.0.1:8791", token: token) == nil)
    }

    @Test func blankEditKeepsTheStoredTokenOutOfTheEditableField() {
        #expect(HubConnectionValidation.effectiveToken(stored: token, entered: "   ") == token)
    }

    @Test func newTokenReplacesTheStoredTokenAfterTrimming() {
        let replacement = String(repeating: "b", count: 43)
        #expect(HubConnectionValidation.effectiveToken(stored: token, entered: " \(replacement) ") == replacement)
    }

    @Test(arguments: [
        ("https://ayaneo-media-pc.tail737e96.ts.net/", "https://ayaneo-media-pc.tail737e96.ts.net"),
        ("  ayaneo-media-pc.tail737e96.ts.net  ", "https://ayaneo-media-pc.tail737e96.ts.net"),
        ("myjellydan.duckdns.org:55886", "https://myjellydan.duckdns.org:55886"),
        ("127.0.0.1:8791", "http://127.0.0.1:8791"),
        ("localhost:8791//", "http://localhost:8791"),
        ("http://10.100.102.8", "http://10.100.102.8"),
        ("", ""),
    ])
    func aTypedAddressIsNormalised(input: String, expected: String) {
        #expect(HubEndpoints.normaliseBase(input) == expected)
    }

    /// A new device's Address field starts with the media PC's tailnet
    /// address, which needs no change before Save and test.
    @Test func theSuggestedAddressIsAlreadyNormalised() {
        #expect(HubEndpoints.normaliseBase(HubEndpoints.suggestedAddress) == HubEndpoints.suggestedAddress)
        #expect(HubConnectionValidation.error(normalizedAddress: HubEndpoints.suggestedAddress, token: token) == nil)
    }
}

struct EndpointTests {
    @Test func joinPutsExactlyOneSlashBetween() {
        #expect(HubEndpoints.join("https://hub/", "/v1/health") == "https://hub/v1/health")
        #expect(HubEndpoints.join("https://hub", "v1/health") == "https://hub/v1/health")
        #expect(HubEndpoints.join("https://hub//", "/v1/health") == "https://hub/v1/health")
    }

    @Test func aSpaceIsPercentTwentyNeverPlus() {
        // Jellyseerr answered every multi-word search with a bare 400 when a
        // space was written as "+".
        #expect(HubEndpoints.encode("gran torino") == "gran%20torino")
        #expect(HubEndpoints.encode("a+b") == "a%2Bb")
        #expect(HubEndpoints.encode("Amélie") == "Am%C3%A9lie")
        #expect(HubEndpoints.encode("safe-_.~") == "safe-_.~")
    }

    @Test func libraryPagesLeaveTheDefaultsOutOfTheQuery() {
        #expect(HubEndpoints.libraryItems(viewId: "abc").path == "/v1/library/abc/items")
        #expect(HubEndpoints.libraryItems(viewId: "abc", page: 2, sort: "added", order: "desc").path
            == "/v1/library/abc/items?sort=added&order=desc&page=2")
    }

    /// Android's `HubEndpointsTest` cases for #14: a library's own search
    /// names the library; the root's does not.
    @Test func aLibrarysOwnSearchNamesItsLibrary() {
        #expect(HubEndpoints.librarySearch("star wars", page: 2).path == "/v1/library/search?q=star%20wars&page=2")
        let view = "0123456789abcdef0123456789abcdef"
        #expect(HubEndpoints.librarySearch("dark", viewId: view).path == "/v1/library/search?q=dark&viewId=\(view)")
        #expect(HubEndpoints.librarySearch("dark", page: 3, viewId: view).path
            == "/v1/library/search?q=dark&page=3&viewId=\(view)")
    }

    @Test func episodesNameTheirSeason() {
        #expect(HubEndpoints.libraryEpisodes(seriesId: "s", seasonId: "t").path == "/v1/library/series/s/episodes?seasonId=t")
        #expect(HubEndpoints.libraryEpisodes(seriesId: "s", seasonId: "t", page: 3).path
            == "/v1/library/series/s/episodes?seasonId=t&page=3")
    }

    @Test func aJellyfinImageAsksForItsWidth() {
        #expect(HubEndpoints.sized("/v1/img/jf/abc/Backdrop?tag=x", width: 1920) == "/v1/img/jf/abc/Backdrop?tag=x&w=1920")
        #expect(HubEndpoints.sized("/v1/img/jf/abc/Backdrop", width: 1920) == "/v1/img/jf/abc/Backdrop?w=1920")
        #expect(HubEndpoints.sized("/v1/img/jf/abc/Primary?w=540", width: 1920) == "/v1/img/jf/abc/Primary?w=540")
        #expect(HubEndpoints.sized("", width: 1920) == "")
    }

    @Test func aTMDBImageNamesItsSizeInThePath() {
        #expect(HubEndpoints.sized("/v1/img/tmdb/w342/abc.jpg", width: 1920) == "/v1/img/tmdb/w1280/abc.jpg")
        #expect(HubEndpoints.sized("/v1/img/tmdb/w342/abc.jpg", width: 500) == "/v1/img/tmdb/w780/abc.jpg")
        #expect(HubEndpoints.sized("/v1/img/tmdb/abc.jpg", width: 500) == "/v1/img/tmdb/abc.jpg")
    }

    /// Android's `HubEndpointsTest` case for `smallest`.
    @Test func theSmallestPictureIsW92FromTMDBW180FromJellyfinAndAnythingElseAsItIs() {
        #expect(HubEndpoints.smallest("/v1/img/tmdb/w780/abc.jpg") == "/v1/img/tmdb/w92/abc.jpg")
        #expect(HubEndpoints.smallest("/v1/img/tmdb/original/abc.jpg") == "/v1/img/tmdb/w92/abc.jpg")
        #expect(HubEndpoints.smallest("/v1/img/jf/a/Backdrop") == "/v1/img/jf/a/Backdrop?w=180")
        #expect(HubEndpoints.smallest("/v1/img/jf/a/Backdrop?tag=t") == "/v1/img/jf/a/Backdrop?tag=t&w=180")
        // A width already asked for is replaced, not doubled.
        #expect(HubEndpoints.smallest("/v1/img/jf/a/Backdrop?tag=t&w=1280") == "/v1/img/jf/a/Backdrop?tag=t&w=180")
        #expect(HubEndpoints.smallest("/v1/img/jf/a/Primary?w=1920&tag=t") == "/v1/img/jf/a/Primary?tag=t&w=180")
        // A reading cover has one size; a malformed path and a blank one are left alone.
        #expect(HubEndpoints.smallest("/v1/img/reading/kavita/12") == "/v1/img/reading/kavita/12")
        #expect(HubEndpoints.smallest("/v1/img/tmdb/abc.jpg") == "/v1/img/tmdb/abc.jpg")
        #expect(HubEndpoints.smallest("") == "")
    }

    @Test func onlyGetsAreIdempotent() {
        #expect(HubEndpoints.health.idempotent)
        #expect(!HubEndpoints.scanJellyfinLibrary.idempotent)
    }
}

/// The cases of Android's `FmtTest`.
struct FmtTests {
    @Test func bytesUseBinaryUnits() {
        #expect(Fmt.bytes(0) == "0 B")
        #expect(Fmt.bytes(512) == "512 B")
        #expect(Fmt.bytes(1024) == "1.0 KB")
        #expect(Fmt.bytes(1024 * 1024) == "1.0 MB")
        #expect(Fmt.bytes(1024 * 1024 * 1536) == "1.5 GB")
    }

    @Test func aNegativeSizeDoesNotProduceNonsense() {
        #expect(Fmt.bytes(-1) == "0 B")
    }

    @Test func largeValuesDropTheDecimal() {
        #expect(Fmt.bytes(1024 * 1024 * 1023) == "1023 MB")
    }

    @Test func zeroSpeedIsADashNotZero() {
        #expect(Fmt.speed(0) == "—")
        #expect(Fmt.speed(-5) == "—")
        #expect(Fmt.speed(4 * 1024 * 1024) == "4.0 MB/s")
    }

    @Test func anUnknownEtaIsADash() {
        #expect(Fmt.eta(-1) == "—")
    }

    @Test func etaReadsInTheLargestSensibleUnit() {
        #expect(Fmt.eta(45) == "45s")
        #expect(Fmt.eta(8 * 60) == "8m")
        #expect(Fmt.eta(150 * 60) == "2h 30m")
        #expect(Fmt.eta((3 * 24 + 2) * 3600) == "3d 2h")
        #expect(Fmt.eta(0) == "0s")
    }

    @Test func percentRefusesToInventANumber() {
        #expect(Fmt.percent(0) == "0%")
        #expect(Fmt.percent(0.6312) == "63%")
        #expect(Fmt.percent(1) == "100%")
        #expect(Fmt.percent(-1) == "—")
    }

    @Test func clockCarriesHoursInsteadOfLettingMinutesRunPastSixty() {
        #expect(Fmt.clock(0) == "0:00")
        #expect(Fmt.clock(9_999) == "0:09")
        #expect(Fmt.clock((17 * 60 + 12) * 1_000) == "17:12")
        #expect(Fmt.clock((75 * 60 + 30) * 1_000) == "1:15:30")
        #expect(Fmt.clock(10 * 3_600_000) == "10:00:00")
        #expect(Fmt.clock(-5_000) == "0:00")
    }

    @Test func runtimeReadsInMinutesUntilItNeedsHours() {
        #expect(Fmt.runtime(24 * 60) == "24 min")
        #expect(Fmt.runtime(59 * 60 + 59) == "59 min")
        #expect(Fmt.runtime(3_600) == "1h 0m")
        #expect(Fmt.runtime(2 * 3_600 + 5 * 60) == "2h 5m")
        #expect(Fmt.runtime(0) == "")
        #expect(Fmt.runtime(-1) == "")
    }

    @Test func bitrateIsMegabitsWithOneDecimal() {
        #expect(Fmt.mbps(40_000_000) == "40.0 Mbps")
        #expect(Fmt.mbps(2_500_000) == "2.5 Mbps")
        #expect(Fmt.mbps(12_345_678) == "12.3 Mbps")
        #expect(Fmt.mbps(0) == "")
    }

    @Test func uptimeCountsDaysAndHoursInWordsSingularWhenOne() {
        #expect(Fmt.uptime(86_400 + 120) == "1 day")
        #expect(Fmt.uptime(2 * 86_400 + 4 * 3_600) == "2 days 4 hours")
        #expect(Fmt.uptime(3_600 + 12 * 60) == "1 hour 12 min")
        #expect(Fmt.uptime(8 * 60 + 5) == "8 min")
        #expect(Fmt.uptime(0) == "")
    }
}

/// The cases of Android's `StatusTextTest`. "Select retries" is a gamepad
/// hint there; here the same rule sets `offersRetry`.
struct StatusTextTests {
    @Test func loadingSaysRefreshingOnceThereIsSomethingOnScreen() {
        #expect(StatusText.loading("episodes", refreshing: false) == StatusMessage("Loading episodes…"))
        #expect(StatusText.loading("episodes", refreshing: true) == StatusMessage("Refreshing episodes…"))
        #expect(StatusText.loading("libraries", refreshing: true).tone == .normal)
    }

    @Test func freshDataCarriesNoCaveatCachedOrNot() {
        #expect(StatusText.loaded("24 rows") == StatusMessage("24 rows"))
        #expect(StatusText.loaded("24 rows", cache: CacheInfo(hit: true, ageSeconds: 40)) == StatusMessage("24 rows"))
    }

    @Test func staleDataSaysHowOldItIs() {
        #expect(StatusText.loaded("24 rows", cache: CacheInfo(hit: true, ageSeconds: 250, stale: true))
            == StatusMessage("24 rows · updated 4 min ago"))
    }

    @Test func degradedDataIsAWarningNotPresentedAsCurrent() {
        #expect(StatusText.loaded("24 rows", cache: CacheInfo(hit: true, ageSeconds: 7_300, stale: true, degraded: true))
            == StatusMessage("24 rows · couldn't refresh, showing data from 2 h ago", tone: .warning))
    }

    @Test func servicesThatDidNotAnswerAreNamedNotOnlyColoured() {
        #expect(StatusText.loaded("12 of 40", unavailable: ["sonarr", "qbittorrent", "sonarr"])
            == StatusMessage("12 of 40 · Sonarr, qBittorrent unavailable", tone: .warning))
    }

    @Test func theCaveatAloneIsEmptyWhenEverythingIsFreshAndComplete() {
        #expect(StatusText.caveat(CacheInfo(hit: true, ageSeconds: 5)) == StatusMessage(""))
        #expect(StatusText.caveat(CacheInfo(ageSeconds: 150, stale: true)) == StatusMessage("updated 2 min ago"))
    }

    @Test func anEmptySummaryDoesNotLeaveADanglingSeparator() {
        #expect(StatusText.loaded("", cache: CacheInfo(ageSeconds: 61, stale: true)) == StatusMessage("updated 1 min ago"))
    }

    @Test func aRetryableFailureWithNothingOnScreenOffersTheRetry() {
        #expect(StatusText.failed("Can't reach the hub", kind: .noNetwork, hasData: false)
            == StatusMessage("Can't reach the hub", tone: .error, offersRetry: true))
    }

    @Test func aFailedRefreshSaysTheEarlierResultsAreStillThere() {
        #expect(StatusText.failed("The hub took too long", kind: .timeout, hasData: true)
            == StatusMessage("The hub took too long · showing earlier results", tone: .error, offersRetry: true))
    }

    @Test func aRejectedTokenNeverPromisesThatRetryingHelps() {
        // Retrying a 401 only moves the device closer to the hub's source ban.
        #expect(StatusText.failed("This token was rejected", kind: .unauthorized, hasData: false)
            == StatusMessage("This token was rejected", tone: .error, offersRetry: false))
    }

    @Test func aScreenWithoutARetryActionDoesNotAdvertiseOne() {
        #expect(!StatusText.failed("A service behind the hub is down", kind: .upstreamDown, hasData: false, canRetry: false)
            .offersRetry)
    }

    @Test func agesReadInTheLargestSensibleUnit() {
        #expect(StatusText.age(30) == "moments ago")
        #expect(StatusText.age(60) == "1 min ago")
        #expect(StatusText.age(3_599) == "59 min ago")
        #expect(StatusText.age(3 * 3_600) == "3 h ago")
        #expect(StatusText.age(2 * 86_400) == "2 d ago")
    }
}

struct ServiceRowsTests {
    let health = HealthResponse(
        hub: HubInfo(version: "0.9.4", uptimeSeconds: 2 * 86_400 + 4 * 3_600, tokenCount: 3),
        services: [
            ServiceHealth(name: "sonarr", state: "up", latencyMs: 12, version: "4.0.19.2979"),
            ServiceHealth(name: "qbittorrent", state: "up", notes: ["reachable; session auth not yet established"]),
            ServiceHealth(name: "jellyfin", state: "up", dashboardUrl: "https://pc.ts.net:8920", latencyMs: 40, version: "10.11.8"),
            ServiceHealth(name: "bazarr", state: "down", lastError: "connection refused"),
            ServiceHealth(name: "kavita", state: "misconfigured"),
        ])

    @Test func theHubComesFirstThenTheMonitorThenServicesInServiceNamesOrder() {
        let rows = ServiceRows.rows(health: health, address: "https://pc.ts.net")
        #expect(rows.map(\.id) == ["hub", "monitor", "jellyfin", "sonarr", "qbittorrent", "bazarr", "kavita"])
    }

    @Test func theHubRowSaysWhereItIsAndHowLongItHasBeenUp() {
        let hub = ServiceRows.rows(health: health, address: "https://pc.ts.net")[0]
        #expect(hub.detail == "https://pc.ts.net · v0.9.4 · up 2 days 4 hours · 3 access tokens")
        #expect(hub.stateWord == "Running")
        #expect(ServiceRows.hubRow(HubInfo(tokenCount: 1), address: "", state: "up").detail
            == "No address configured · 1 access token")
    }

    @Test func beforeHealthLoadsTheHubIsChecking() {
        let rows = ServiceRows.rows(health: nil, address: "https://pc.ts.net")
        #expect(rows.map(\.id) == ["hub", "monitor"])
        #expect(rows[0].stateWord == "Checking")
        #expect(rows[0].detail == "https://pc.ts.net")
    }

    @Test func aServiceDetailJoinsVersionLatencyErrorAndNotes() {
        let rows = ServiceRows.rows(health: health, address: "")
        #expect(rows.first { $0.id == "jellyfin" }?.detail == "v10.11.8 · 40 ms")
        #expect(rows.first { $0.id == "bazarr" }?.detail == "connection refused")
        #expect(rows.first { $0.id == "qbittorrent" }?.detail == "reachable; session auth not yet established")
        #expect(rows.first { $0.id == "kavita" }?.detail == "No additional information")
    }

    @Test func aVersionThatAlreadyStartsWithVIsNotDoubled() {
        #expect(ServiceRows.row(ServiceHealth(name: "qbittorrent", state: "up", version: "v5.0.4")).detail == "v5.0.4")
        #expect(ServiceRows.row(ServiceHealth(name: "sonarr", state: "up", version: "4.0.19.2979")).detail == "v4.0.19.2979")
    }

    @Test func namesAndLogosFollowTheService() {
        let rows = ServiceRows.rows(health: health, address: "")
        #expect(rows.first { $0.id == "qbittorrent" }?.name == "qBittorrent")
        #expect(rows.first { $0.id == "qbittorrent" }?.logo == "logo_qbittorrent")
        #expect(rows.first { $0.id == "kavita" }?.logo == "logo_kavita")
        #expect(ServiceRows.row(ServiceHealth(name: "storyteller", state: "up")).logo == "logo_storyteller")
        #expect(ServiceRows.row(ServiceHealth(name: "bookkeeprr", state: "up")).logo == "logo_bookkeeprr")
        // A service the app has no logo for falls back to the hub mark.
        #expect(ServiceRows.row(ServiceHealth(name: "lidarr", state: "up")).logo == nil)
    }

    @Test func statesReadAsWordsInTheirColour() {
        #expect(ServiceRows.stateWord("misconfigured") == "Needs setup")
        #expect(ServiceRows.stateWord("down") == "Unavailable")
        #expect(ServiceRows.stateWord("") == "Unknown")
        #expect(ServiceRows.stateWord("sleeping") == "Sleeping")
        #expect(ServiceRows.tone("up") == .available)
        #expect(ServiceRows.tone("misconfigured") == .pending)
        #expect(ServiceRows.tone("disabled") == .muted)
        #expect(ServiceRows.tone("down") == .danger)
        #expect(!ServiceRows.monitorRow.showsDot)
    }

    @Test func onlyARunningJellyfinOrReadingServerOffersAScan() {
        let rows = ServiceRows.rows(health: health, address: "")
        #expect(rows.filter(\.canScan).map(\.id) == ["jellyfin"])
    }

    @Test func theSummaryCountsProblemsOrEverythingRunning() {
        #expect(ServiceRows.summary(ServiceRows.rows(health: health, address: ""))
            == StatusMessage("2 services need attention", tone: .warning))
        let healthy = HealthResponse(hub: HubInfo(), services: [ServiceHealth(name: "jellyfin", state: "up")])
        #expect(ServiceRows.summary(ServiceRows.rows(health: healthy, address: "")) == StatusMessage("All 2 running"))
    }
}

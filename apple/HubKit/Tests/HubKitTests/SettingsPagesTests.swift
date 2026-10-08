import Foundation
import Testing
@testable import HubKit

/// The Settings pages (#38): accents per side, the subtitle words, the
/// licences, the controller test and the last answer kept. Android's
/// `AppearancePolicyTest` and `PersistentResponseCacheTest` case for case, then
/// the rest.
struct AccentSettingsTests {
    private func defaults() throws -> (UserDefaults, () -> Void) {
        let suite = "accent-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        return (defaults, { defaults.removePersistentDomain(forName: suite) })
    }

    @Test func tenPresetsEachWithAStableIdAndInkThatReadsOnIt() {
        #expect(AccentPreset.allCases.count == 10)
        #expect(Set(AccentPreset.allCases.map(\.rawValue)).count == 10)
        // The dark page and card surfaces Android checks against, and the label on a Play button.
        for preset in AccentPreset.allCases {
            #expect(GlassColors.contrast(preset.color, 0xFF0A_0D12) >= 4.5, "\(preset.label) on the page")
            #expect(GlassColors.contrast(preset.color, 0xFF13_1821) >= 4.5, "\(preset.label) on a card")
            #expect(GlassColors.contrast(preset.color, preset.ink) >= 4.5, "\(preset.label)'s ink")
        }
        #expect(AccentPreset.fromStored("retired") == .teal)
    }

    @Test func booksDefaultToGoldAndTheFirstPalettesChoicesKeepTheirNearestPastel() {
        #expect(AccentPreset.defaultFor(.books) == .gold && AccentPreset.defaultFor(.media) == .teal)
        #expect(AccentPreset.fromStored(nil, fallback: .gold) == .gold)
        #expect(AccentPreset.fromStored("blue", fallback: .gold) == .sky)
        #expect(AccentPreset.fromStored("coral") == .peach)
        #expect(AccentPreset.fromStored("rose") == .rose)
        #expect(["indigo", "violet", "amber", "olive", "green", "cyan"].map { AccentPreset.fromStored($0) }
            == [.lavender, .lilac, .gold, .sage, .mint, .teal])
    }

    @Test func aHubAndProfileAndSideAreOneScopeHoweverTheAddressIsWritten() {
        let scope = PreferenceScope.key(hub: "HTTPS://HOST:443/", profile: "alice", side: .books)
        #expect(scope == PreferenceScope.key(hub: "https://host", profile: "alice", side: .books))
        #expect(scope != PreferenceScope.key(hub: "https://host", profile: "bob", side: .books))
        #expect(scope != PreferenceScope.key(hub: "https://host", profile: "alice", side: .media))
        #expect(scope != PreferenceScope.key(hub: "https://other", profile: "alice", side: .books))
        #expect(PreferenceScope.key(hub: "http://host:80", profile: "", side: .media) == PreferenceScope.key(hub: "http://host", profile: " ", side: .media))
        #expect(PreferenceScope.key(hub: "https://host:8443", profile: "", side: .media)
            != PreferenceScope.key(hub: "https://host", profile: "", side: .media))
    }

    @Test func eachSideKeepsItsOwnChoiceForEachProfileOnEachHub() throws {
        let (defaults, clean) = try defaults()
        defer { clean() }
        let hub = "https://hub.example"
        #expect(AccentSettings.accent(for: .media, hub: hub, profile: "a", in: defaults) == .teal)
        #expect(AccentSettings.accent(for: .books, hub: hub, profile: "a", in: defaults) == .gold)
        AccentSettings.set(.rose, for: .media, hub: hub, profile: "a", in: defaults)
        AccentSettings.set(.sage, for: .books, hub: hub, profile: "a", in: defaults)
        #expect(AccentSettings.accent(for: .media, hub: hub, profile: "a", in: defaults) == .rose)
        #expect(AccentSettings.accent(for: .books, hub: hub + "/", profile: "a", in: defaults) == .sage)
        // Another profile or another hub starts again.
        #expect(AccentSettings.accent(for: .media, hub: hub, profile: "b", in: defaults) == .teal)
        #expect(AccentSettings.accent(for: .media, hub: "https://elsewhere", profile: "a", in: defaults) == .teal)
    }

    @Test func aStoredNameThatIsGoneStillFindsItsColour() throws {
        let (defaults, clean) = try defaults()
        defer { clean() }
        defaults.set("amber", forKey: "accent." + PreferenceScope.key(hub: "https://h", profile: "", side: .books))
        #expect(AccentSettings.accent(for: .books, hub: "https://h", profile: "", in: defaults) == .gold)
    }

    @Test func eachSideSaysWhatItsColourIsFor() {
        #expect(AccentSettings.title(for: .media) == "Movies and TV" && AccentSettings.title(for: .books) == "Books")
        #expect(AccentSettings.sample(for: .media) == "Play" && AccentSettings.sample(for: .books) == "Continue reading")
        #expect(AccentSettings.hint(for: .media).hasPrefix("Play buttons"))
        #expect(AccentSettings.hint(for: .books).hasPrefix("The same places"))
    }
}

struct SubtitleLookWordsTests {
    @Test func theLookIsNamedTheSameInTheSheetAndInSettings() {
        #expect(SubtitleLookWords.style(.outline) == "Outline" && SubtitleLookWords.style(.box) == "Box")
        #expect(SubtitleLookWords.styleDetail(.outline) == "White words with a black edge")
        #expect(SubtitleLookWords.styleDetail(.box) == "White words on a dark box")
        #expect(SubtitleLookWords.size(.small) == "Small" && SubtitleLookWords.size(.large) == "Large")
        #expect(SubtitleLookWords.liftTitle == "Above the controls")
        #expect(SubtitleLookWords.controlsToggle(shown: true) == "Hide controls")
        #expect(SubtitleLookWords.controlsToggle(shown: false) == "Show controls")
        #expect(SubtitleLookWords.styleHint(.outline) != SubtitleLookWords.styleHint(.box))
    }

    @Test func theWordsSitAtTheSizesBottomUntilTheControlsAreOverThemAndLifted() {
        var look = SubtitleLook(style: .outline, size: .medium, liftWithControls: false)
        #expect(SubtitleLookWords.previewPlacement(look, controlsShown: true) == SubtitleSize.medium.bottomFraction)
        #expect(SubtitleLookWords.previewPlacement(look, controlsShown: false) == SubtitleSize.medium.bottomFraction)
        look.liftWithControls = true
        #expect(SubtitleLookWords.previewPlacement(look, controlsShown: false) == SubtitleSize.medium.bottomFraction)
        // Android's 64 of 176, and the gap above the controls.
        let lifted = SubtitleLookWords.previewPlacement(look, controlsShown: true)
        #expect(abs(lifted - (64.0 / 176.0 + 0.02)) < 1e-9)
        #expect(lifted > SubtitleSize.medium.bottomFraction)
    }

    @Test func theWordsAreAShareOfTheShortSideAndNeverBelowTwelve() {
        #expect(SubtitleLookWords.fontSize(SubtitleLook(size: .medium), shortSide: 400) == 400 * 0.054)
        #expect(SubtitleLookWords.fontSize(SubtitleLook(size: .large), shortSide: 400) > SubtitleLookWords.fontSize(SubtitleLook(size: .small), shortSide: 400))
        #expect(SubtitleLookWords.fontSize(SubtitleLook(size: .small), shortSide: 100) == 12)
    }

    @Test func aLookSurvivesItsStoredForm() throws {
        let look = SubtitleLook(style: .box, size: .large, liftWithControls: true)
        let data = try JSONEncoder().encode(look)
        #expect(try JSONDecoder().decode(SubtitleLook.self, from: data) == look)
    }
}

struct LicencesTests {
    /// `apple/Hub/Resources/Licenses`, from where this file is.
    private var folder: URL {
        URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().appendingPathComponent("Hub/Resources/Licenses")
    }

    @Test func everyLicenceTheAppShipsIsOnTheirPageAndEveryEntryHasItsText() throws {
        let files = try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".txt") }
            .map { String($0.dropLast(4)) }
        #expect(Set(files) == Set(Licences.all.map(\.file)), "the page and the folder disagree")
        for entry in Licences.all {
            let text = try String(contentsOf: folder.appendingPathComponent(entry.file + ".txt"), encoding: .utf8)
            #expect(text.count > 200, "\(entry.name)'s text is empty")
        }
    }

    @Test func theFontsAreAskedForFirstAndEachEntryIsComplete() {
        #expect(Licences.fonts.map(\.name) == ["Figtree", "Bricolage Grotesque", "Literata", "Atkinson Hyperlegible Next"])
        #expect(Licences.software.count == Licences.all.count - 4)
        #expect(Set(Licences.all.map(\.id)).count == Licences.all.count)
        for entry in Licences.all {
            #expect(!entry.name.isEmpty && !entry.role.isEmpty && !entry.terms.isEmpty)
            #expect(entry.line == entry.role + " · " + entry.terms)
        }
        #expect(Licences.all.map(\.kind).prefix(4).allSatisfy { $0 == .font })
    }
}

struct ControllerProbeTests {
    private func sample(_ down: [ControllerControl: Float] = [:], left: StickPosition = StickPosition(),
                        right: StickPosition = StickPosition()) -> ControllerSample {
        var values = Dictionary(uniqueKeysWithValues: ControllerControl.allCases.filter { $0 != .home }.map { ($0, Float(0)) })
        for (control, value) in down { values[control] = value }
        return ControllerSample(name: "Xbox Wireless Controller", category: "Extended gamepad", values: values, left: left, right: right)
    }

    @Test func aPressCountsOnceHoweverLongItIsHeldAndAgainAfterLettingGo() {
        var probe = ControllerProbe()
        probe.observe(sample([.a: 1]))
        probe.observe(sample([.a: 1]))
        #expect(probe.presses[.a] == 1 && probe.down == [.a])
        probe.observe(sample())
        #expect(probe.down.isEmpty)
        probe.observe(sample([.a: 1, .r2: 0.8]))
        #expect(probe.presses[.a] == 2 && probe.presses[.r2] == 1)
        #expect(probe.log == [.r2, .a, .a], "newest first: \(probe.log)")
    }

    @Test func aTriggerPulledLessThanHalfIsNotAPressButItsPeakIsKept() {
        var probe = ControllerProbe()
        probe.observe(sample([.l2: 0.3]))
        #expect(probe.presses[.l2] == nil && probe.triggerPeak[.l2] == 0.3)
        probe.observe(sample([.l2: 0.9]))
        probe.observe(sample([.l2: 0.2]))
        #expect(probe.presses[.l2] == 1 && probe.triggerPeak[.l2] == 0.9)
    }

    @Test func theLogKeepsTheLastEightNewestFirst() {
        var probe = ControllerProbe()
        let order: [ControllerControl] = [.a, .b, .x, .y, .l1, .r1, .l3, .r3, .menu, .options]
        for control in order {
            probe.observe(sample([control: 1]))
            probe.observe(sample())
        }
        #expect(probe.log.count == ControllerProbe.logLength)
        #expect(probe.log == Array(order.reversed().prefix(8)))
        #expect(probe.lastLine == "Last: Options, Menu, R3, L3, R1, L1, Y, X")
    }

    @Test func aStickInsideItsDeadZoneIsAtRestAndPeaksAreOnlyPushesOutOfIt() {
        var probe = ControllerProbe()
        probe.observe(sample(left: StickPosition(x: 0.05, y: -0.05)))
        #expect(probe.leftPeak == 0 && probe.rightPeak == 0)
        probe.observe(sample(left: StickPosition(x: 0.6, y: 0.8), right: StickPosition(x: -0.3, y: 0)))
        probe.observe(sample(left: StickPosition(x: 0.1, y: 0), right: StickPosition(x: 0, y: 0)))
        #expect(abs(probe.leftPeak - 1) < 1e-5 && abs(probe.rightPeak - 0.3) < 1e-5)
        #expect(ControllerProbe.stickLine(StickPosition(x: 0.05, y: 0.05)) == "Centred")
        #expect(ControllerProbe.stickLine(StickPosition(x: 0.43, y: -0.2)) == "x +0.43  y −0.20")
    }

    @Test func resetForgetsEverything() {
        var probe = ControllerProbe()
        probe.observe(sample([.x: 1], left: StickPosition(x: 1, y: 0)))
        probe.reset()
        #expect(probe == ControllerProbe())
        #expect(probe.lastLine == "Nothing pressed yet")
    }

    @Test func whatTheControllerHasIsOnlyWhatItReports() {
        let reading = sample()
        #expect(reading.controls.count == ControllerControl.allCases.count - 1, "this one has no Home")
        var probe = ControllerProbe()
        probe.observe(sample([.a: 1, .b: 1, .home: 1]))
        #expect(probe.tried(reading) == 2, "a Home it does not report is not among its controls")
        #expect(probe.progressLine(reading) == "2 of 16 controls tried")
    }

    @Test func theWordsNameWhatIsAndIsNotConnected() {
        #expect(ControllerProbe.header(count: 0) == "No controller connected")
        #expect(ControllerProbe.header(count: 1) == "1 controller connected")
        #expect(ControllerProbe.header(count: 2) == "2 controllers connected")
        #expect(ControllerProbe.title(sample()) == "Xbox Wireless Controller · Extended gamepad")
        #expect(ControllerProbe.title(ControllerSample(name: "")) == "Game controller")
        #expect(ControllerProbe.batteryLine(0.8) == "Battery 80%" && ControllerProbe.batteryLine(nil) == "")
        #expect(ControllerProbe.triggerLine(0.456) == "46%")
        #expect(ControllerControl.l2.isTrigger && !ControllerControl.l1.isTrigger && ControllerControl.up.isDpad)
        #expect(ControllerControl.allCases.map(\.label).count == 17)
    }
}

struct AnswerKeeperTests {
    private func folder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("answers-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private final class Clock: @unchecked Sendable {
        var seconds: TimeInterval = 1_000
        var date: Date { Date(timeIntervalSince1970: seconds) }
    }

    @Test func aBodySurvivesANewKeeperWithoutExposingItsKey() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let clock = Clock()
        let key = AnswerKeeper.key(path: "/v1/home", address: "https://hub", token: "secret-token", user: "u1")
        AnswerKeeper(directory: root, now: { clock.date }).put(key, body: Data(#"{"rows":[1]}"#.utf8))

        let files = try FileManager.default.contentsOfDirectory(atPath: root.path)
        #expect(files.count == 1)
        #expect(files[0].range(of: "^[0-9a-f]{64}\\.answer$", options: .regularExpression) != nil)
        let raw = try String(contentsOf: root.appendingPathComponent(files[0]), encoding: .utf8)
        #expect(!raw.contains("secret-token") && !raw.contains("/v1/home"))

        clock.seconds += 30
        let restored = AnswerKeeper(directory: root, now: { clock.date }).read(key, maxAge: 60)
        #expect(restored.map { String(decoding: $0.body, as: UTF8.self) } == #"{"rows":[1]}"#)
        #expect(restored?.age == 30)
    }

    @Test func differentIdentitiesCannotReadEachOthersAnswers() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = AnswerKeeper(directory: root)
        func key(token: String, user: String) -> String {
            AnswerKeeper.key(path: "/v1/home", address: "https://hub", token: token, user: user)
        }
        keeper.put(key(token: "a", user: "u"), body: Data("alpha".utf8))
        #expect(keeper.read(key(token: "b", user: "u"), maxAge: 60) == nil)
        #expect(keeper.read(key(token: "a", user: "v"), maxAge: 60) == nil)
        #expect(keeper.read(key(token: "a", user: "u"), maxAge: 60).map { String(decoding: $0.body, as: UTF8.self) } == "alpha")
    }

    @Test func expiredAndDamagedAnswersAreIgnored() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let clock = Clock()
        let keeper = AnswerKeeper(directory: root, now: { clock.date })
        keeper.put("expired", body: Data("old".utf8))
        clock.seconds += 61
        #expect(keeper.read("expired", maxAge: 60) == nil)

        keeper.put("damaged", body: Data("valid".utf8))
        let file = try #require(try FileManager.default.contentsOfDirectory(atPath: root.path).first)
        try Data("not an entry".utf8).write(to: root.appendingPathComponent(file))
        #expect(keeper.read("damaged", maxAge: 60) == nil)
    }

    @Test func theOldestFilesGoFirstToFitTheByteBudget() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let clock = Clock()
        // Each file is its two header lines (20 bytes) and 20 of body.
        let keeper = AnswerKeeper(directory: root, maxBytes: 100, maxEntries: 10, now: { clock.date })
        keeper.put("first", body: Data(repeating: 97, count: 20))
        clock.seconds += 1
        keeper.put("second", body: Data(repeating: 98, count: 20))
        clock.seconds += 1
        keeper.put("third", body: Data(repeating: 99, count: 20))
        clock.seconds += 1
        keeper.put("fourth", body: Data(repeating: 100, count: 20))
        #expect(keeper.read("first", maxAge: .infinity) == nil)
        #expect(keeper.read("fourth", maxAge: .infinity) != nil)
        let total = try FileManager.default.contentsOfDirectory(atPath: root.path)
            .reduce(0) { $0 + ((try? FileManager.default.attributesOfItem(atPath: root.appendingPathComponent($1).path)[.size] as? Int) ?? 0) }
        #expect(total <= 100)
    }

    @Test func tooBigAnEntryOrNothingIsNotKept() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = AnswerKeeper(directory: root, maxEntryBytes: 10)
        keeper.put("big", body: Data(repeating: 1, count: 11))
        keeper.put("empty", body: Data())
        keeper.put("", body: Data("x".utf8))
        #expect((try? FileManager.default.contentsOfDirectory(atPath: root.path))?.isEmpty ?? true)
    }

    @Test func removeAllForgetsEveryAnswer() throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = AnswerKeeper(directory: root)
        keeper.put("one", body: Data("1".utf8))
        keeper.removeAll()
        #expect(keeper.read("one", maxAge: 60) == nil)
    }

    @Test func aHubAnswerIsKeptOnlyWhenItIsWorthItAndFoundAgainForTheSameIdentity() async throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = AnswerKeeper(directory: root)
        let creds = HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token, userId: "u1")
        let hub = HubClient(credentials: creds, screens: DemoTransport())
        let request = HubEndpoints.users
        #expect(await hub.lastAnswer(request, as: UsersResponse.self, keeper: keeper) == nil)

        // Not worth keeping: nothing is written.
        _ = try await hub.fetchKept(request, as: UsersResponse.self, keeper: keeper, keep: { _ in false })
        #expect(await hub.lastAnswer(request, as: UsersResponse.self, keeper: keeper) == nil)

        let answer = try await hub.fetchKept(request, as: UsersResponse.self, keeper: keeper)
        let kept = try #require(await hub.lastAnswer(request, as: UsersResponse.self, keeper: keeper))
        #expect(kept.value.users.map(\.name) == answer.users.map(\.name))
        #expect(kept.ageSeconds >= 0 && kept.ageSeconds < 5)

        // Another profile on the same hub does not see it.
        await hub.update(HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token, userId: "u2"))
        #expect(await hub.lastAnswer(request, as: UsersResponse.self, keeper: keeper) == nil)
    }

    @Test func aKeptAnswerThatNoLongerReadsIsForgotten() async throws {
        let root = try folder()
        defer { try? FileManager.default.removeItem(at: root) }
        let keeper = AnswerKeeper(directory: root)
        let hub = HubClient(credentials: HubCredentials(baseURL: DemoTransport.address, token: DemoTransport.token), screens: DemoTransport())
        let key = AnswerKeeper.key(path: HubEndpoints.users.path, address: DemoTransport.address, token: DemoTransport.token, user: "")
        keeper.put(key, body: Data("[1,2,3]".utf8))
        #expect(await hub.lastAnswer(HubEndpoints.users, as: UsersResponse.self, keeper: keeper) == nil)
        #expect(keeper.read(key, maxAge: 60) == nil, "a damaged answer is dropped, not asked about again")
    }

    @Test func theWordsSayHowOldTheAnswerIsAndWhatDeservesKeeping() {
        #expect(LastAnswer.status(ageSeconds: 30).text == "Showing the last answer, from moments ago · updating")
        #expect(LastAnswer.status(ageSeconds: 4 * 60).text == "Showing the last answer, from 4 min ago · updating")
        #expect(LastAnswer.status(ageSeconds: 3 * 3_600).text == "Showing the last answer, from 3 h ago · updating")
        #expect(LastAnswer.worthKeeping(rows: 3, unavailable: 0))
        #expect(!LastAnswer.worthKeeping(rows: 0, unavailable: 0) && !LastAnswer.worthKeeping(rows: 3, unavailable: 1))
        #expect(AnswerKeeper.longest == 14 * 24 * 3_600)
    }
}

import Foundation
import Testing
@testable import HubKit

/// The Glass shell's layout and back pill.
struct ShellLayoutTests {
    @Test(arguments: [
        (1024.0, true), // iPad Pro 12.9-inch (4th generation), portrait: the user's
        (1366.0, true), // and landscape
        (1133.0, true), // iPad mini, landscape
        (1280.0, true), // the Mac window as it opens
        (744.0, false), // iPad mini, portrait: a phone's tab bar
        (440.0, false), // iPhone 17 Pro Max
        (832.0, false), // and landscape, inside the notch's insets
        (639.0, false), // the 12.9-inch's Split View, two thirds upright
        (981.0, true),  // and two thirds sideways
    ])
    func theTopCapsuleNeedsTheWidthOfAnIPadOrAMacWindow(width: Double, wide: Bool) {
        #expect(ShellLayout.isWide(width: width) == wide)
    }

    @Test(arguments: [
        (440.0, true),   // iPhone 17 Pro Max, landscape
        (402.0, true),   // iPhone 17 Pro, landscape
        (956.0, false),  // the Pro Max upright
        (744.0, false),  // iPad mini, landscape
        (1024.0, false), // iPad Pro 12.9-inch, landscape
        (520.0, false),  // a small Mac window
    ])
    func aPhoneTurnedSidewaysIsShort(height: Double, short: Bool) {
        #expect(ShellLayout.isShort(height: height) == short)
    }

    @Test(arguments: [
        (440.0, 956.0, false, false),  // iPhone 17 Pro Max upright
        (956.0, 440.0, true, true),    // and sideways
        (744.0, 1133.0, false, false), // iPad mini upright, a tab bar with room
        (1024.0, 1366.0, false, false), // iPad Pro upright
        (1366.0, 1024.0, false, false), // and sideways
        (760.0, 560.0, false, true),   // a small Mac window: the bar covered Play
        (760.0, 700.0, false, false),  // a taller one
        (1440.0, 560.0, false, false), // a wide one: the capsule is at the top
    ])
    func aTitlesWordsStartUnderTheBarsWhereTheTabBarWouldCoverItsActions(
        width: Double, height: Double, short: Bool, under: Bool
    ) {
        #expect(ShellLayout.titleWordsUnderBars(width: width, height: height, short: short) == under)
    }

    @Test func theBackPillNamesThePageUnderneath() {
        #expect(ShellLayout.backTitle(pages: ["Bleach"], root: "Library") == "Library")
        #expect(ShellLayout.backTitle(pages: ["Bleach", "Beat the Invisible Enemy!"], root: "Library") == "Bleach")
        #expect(ShellLayout.backTitle(pages: [], root: "Home") == "Home")
    }
}

/// Which artwork the Glass page shows as pages come and go.
struct AmbientStackTests {
    let home = UUID()
    let title = UUID()
    let other = UUID()

    @Test func aPushedPageCoversThePageUnderItUntilItIsPopped() {
        var ambient = AmbientStack()
        ambient.select(stack: "media:home")
        ambient.show("/v1/img/jf/hero/Backdrop", token: home, stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
        ambient.show("/v1/img/jf/title/Backdrop", token: title, stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/title/Backdrop")
        // Home's hero changing underneath does not show through the title.
        ambient.show("/v1/img/jf/next/Backdrop", token: home, stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/title/Backdrop")
        ambient.remove(token: title)
        #expect(ambient.displayed == "/v1/img/jf/next/Backdrop")
    }

    @Test func aPageStillLoadingKeepsThePictureBeforeIt() {
        var ambient = AmbientStack()
        ambient.select(stack: "media:home")
        ambient.show("/v1/img/jf/hero/Backdrop", token: home, stack: "media:home")
        ambient.show("", token: title, stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
        ambient.show("/v1/img/jf/title/Backdrop", token: title, stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/title/Backdrop")
    }

    @Test func eachSectionHasItsOwnAndOneWithoutAnyKeepsTheLastShown() {
        var ambient = AmbientStack()
        ambient.select(stack: "media:home")
        ambient.show("/v1/img/jf/hero/Backdrop", token: home, stack: "media:home")
        ambient.select(stack: "services")
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
        ambient.select(stack: "media:library")
        ambient.show("/v1/img/jf/title/Backdrop", token: title, stack: "media:library")
        #expect(ambient.displayed == "/v1/img/jf/title/Backdrop")
        ambient.select(stack: "media:home")
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
        ambient.remove(token: home)
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
    }

    @Test func aSectionOutOfSightDoesNotRetintThePage() {
        var ambient = AmbientStack()
        ambient.select(stack: "media:home")
        ambient.show("/v1/img/jf/hero/Backdrop", token: home, stack: "media:home")
        ambient.show("/v1/img/jf/title/Backdrop", token: other, stack: "media:library")
        #expect(ambient.displayed == "/v1/img/jf/hero/Backdrop")
        #expect(ambient.lastShown == "/v1/img/jf/hero/Backdrop")
    }
}

/// Android's `ProfileAvatarTest`, case for case, so a profile has one colour
/// on the Pocket and on Apple devices; then which profile is this device's.
struct ProfilesTests {
    let household = [
        HubUser(id: "d1", name: "Dgdan", selected: true),
        HubUser(id: "h2", name: "Horim"),
        HubUser(id: "a3", name: "Adirimo"),
        HubUser(id: "h4", name: "Hadas"),
    ]

    @Test func theFourProfilesTakeThePrototypesColoursWhateverOrderTheHubSends() {
        let expected: [String: UInt32] = ["a3": 0xFF8B_7BFF, "d1": 0xFF2C_C4AD, "h4": 0xFFF2_A541, "h2": 0xFFFF_6B7D]
        #expect(Profiles.colors(household) == expected)
        #expect(Profiles.colors(household.reversed()) == expected)
        #expect(Profiles.color(of: "d1", in: household) == 0xFF2C_C4AD)
        #expect(Profiles.color(of: "gone", in: household) == nil)
    }

    @Test func everyProfileHasAColourDistinctUntilThePaletteRunsOut() {
        let many = (1...10).map { HubUser(id: "id\($0)", name: "Profile \(Character(UnicodeScalar(UInt8(65 + $0))))") }
        let colors = Profiles.colors(many)
        #expect(colors.count == 10)
        let inOrder = Profiles.ordered(many).compactMap { colors[$0.id] }
        #expect(Set(inOrder.prefix(Profiles.palette.count)).count == Profiles.palette.count)
        // Two profiles with one name still differ by id, and in a stable order.
        let twins = Profiles.colors([HubUser(id: "b", name: "Sam"), HubUser(id: "a", name: "Sam")])
        #expect(twins["a"] == Profiles.palette[0])
        #expect(twins["b"] == Profiles.palette[1])
    }

    @Test func theInitialIsTheFirstLetterOrDigitInCapitals() {
        #expect(Profiles.initial("Dgdan") == "D")
        #expect(Profiles.initial("  hadas") == "H")
        #expect(Profiles.initial("דן") == "ד")
        #expect(Profiles.initial("42") == "4")
        #expect(Profiles.initial("🙂 Bob") == "B")
        #expect(Profiles.initial("🙂") == "🙂")
        #expect(Profiles.initial("   ") == "?")
    }

    @Test func thisDevicesChoiceWinsThenTheHubsDefault() {
        #expect(Profiles.current(household, chosen: "")?.name == "Dgdan")
        #expect(Profiles.current(household, chosen: "h2")?.name == "Horim")
        #expect(Profiles.current(household, chosen: "removed")?.name == "Dgdan")
        #expect(Profiles.current([HubUser(id: "a", name: "Adirimo")], chosen: "") == nil)
    }
}

/// The accents, as Android's `AccentPreset`.
struct AccentTests {
    @Test func tealForMediaAndGoldForBooks() {
        #expect(AccentPreset.defaultFor(.media) == .teal)
        #expect(AccentPreset.defaultFor(.books) == .gold)
        #expect(AccentPreset.teal.color == 0xFF3D_DBC6)
        #expect(AccentPreset.gold.color == 0xFFE9_C46A)
    }

    @Test func inkIsAFifthOfEachChannelTruncatedAsOnAndroid() {
        #expect(AccentPreset.teal.ink == 0xFF0C_2B27)
        #expect(AccentPreset.gold.ink == 0xFF2E_2715)
    }

    @Test func inkReadsOnEveryAccent() {
        for accent in AccentPreset.allCases {
            #expect(GlassColors.contrast(accent.ink, accent.color) >= 4.5, "\(accent.label)")
        }
    }
}

/// What the Glass bell counts.
struct NotificationsTests {
    @Test func theHubsAttentionCountAndItsColumnsDecode() throws {
        let json = #"""
        {"generatedAt":"2026-10-04T09:00:00Z","attentionCount":17,
         "sections":[{"service":"sonarr","state":"up","items":[
            {"id":"sonarr:health:aca0","service":"sonarr","kind":"health","severity":"warning",
             "title":"IndexerLongTermStatusCheck","detail":"Indexers unavailable","active":true},
            {"id":"sonarr:history:1","service":"sonarr","kind":"grabbed","title":"Bleach","occurredAt":"2026-10-04T08:00:00Z",
             "timeLabel":"1 hour ago"}]}],
         "partial":[],"cache":{"hit":true,"ageSeconds":12,"stale":false}}
        """#
        let response = try JSONDecoder().decode(NotificationsResponse.self, from: Data(json.utf8))
        #expect(response.attentionCount == 17)
        #expect(response.sections.first?.state == "up")
        let health = try #require(response.sections.first?.items.first)
        #expect(health.active && health.severity == "warning")
        #expect(response.sections.first?.items.last?.severity == "info")
        #expect(response.cache.ageSeconds == 12)
        #expect(try JSONDecoder().decode(NotificationsResponse.self, from: Data("{}".utf8)) == NotificationsResponse())
    }

    @Test func theBellAsksWithAndroidsHistoryLengths() {
        #expect(HubEndpoints.notifications().path == "/v1/notifications?sonarrLimit=60&radarrLimit=20&bazarrLimit=40")
    }
}

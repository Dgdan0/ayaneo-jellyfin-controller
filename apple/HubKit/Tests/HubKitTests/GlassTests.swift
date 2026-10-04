import Foundation
import Testing
@testable import HubKit

/// The Glass colours and asking rules, held to the same numbers as Android's
/// `GlassColorsTest` and `ArtworkColorBookTest`, so both apps tint alike.
struct GlassTests {
    let red = ArtworkPalette(dominant: 0xFFD6_2828, dark: 0xFF2C_0807, vivid: 0xFFDD_2722, light: 0xFFFF_DBD6)
    let blue = ArtworkPalette(dominant: 0xFF1D_4FA8, dark: 0xFF0C_1331, vivid: 0xFF55_6FDE, light: 0xFFDA_E4FF)
    let gold = ArtworkPalette(dominant: 0xFFD0_B366, dark: 0xFF1D_1500, vivid: 0xFFD0_B366, light: 0xFFF2_E4BF)

    @Test func theResponseDecodesAndEchoesEachSource() throws {
        let json = #"""
        {"colors":{"/v1/img/jf/abc/Backdrop?tag=t&w=1280":
            {"dominant":"#d0b366","dark":"#1d1500","vivid":"#d0b366","light":"#f2e4bf"}},
         "pending":["/v1/img/tmdb/w342/a.jpg"],"missing":[],"later":"ignored"}
        """#
        let response = try JSONDecoder().decode(ArtworkColorsResponse.self, from: Data(json.utf8))
        let set = try #require(response.colors["/v1/img/jf/abc/Backdrop?tag=t&w=1280"])
        #expect(ArtworkPalette(set) == gold)
        #expect(response.pending == ["/v1/img/tmdb/w342/a.jpg"])
        #expect(response.missing.isEmpty)
        // A hub without the endpoint's fields still decodes, to nothing.
        #expect(try JSONDecoder().decode(ArtworkColorsResponse.self, from: Data("{}".utf8)) == ArtworkColorsResponse())
    }

    @Test func eachImagePathIsSentWholeAndEncoded() {
        #expect(HubEndpoints.artworkColors(["/v1/img/jf/abc/Backdrop?tag=t&w=1280", "/v1/img/tmdb/w342/a.jpg"]).path
            == "/v1/img/colors?src=%2Fv1%2Fimg%2Fjf%2Fabc%2FBackdrop%3Ftag%3Dt%26w%3D1280&src=%2Fv1%2Fimg%2Ftmdb%2Fw342%2Fa.jpg")
    }

    @Test func hubColoursParseAndAnythingElseIsRefused() {
        #expect(GlassColors.parse("#d0b366") == 0xFFD0_B366)
        #expect(GlassColors.parse("#0B0D12") == 0xFF0B_0D12)
        for bad in ["", "d0b366", "#d0b36", "#d0b3666", "#zzzzzz"] { #expect(GlassColors.parse(bad) == nil) }
        #expect(ArtworkPalette(ArtworkColorSet(dominant: "#d0b366", dark: "", vivid: "#d0b366", light: "#f2e4bf")) == nil)
        #expect(gold.hexes == ["#d0b366", "#1d1500", "#d0b366", "#f2e4bf"])
    }

    @Test func aPanelIsTheBaseTintedByTheArtworkAtEightyPercentLikeAndroid() {
        let panel = GlassColors.panel(gold)
        #expect(GlassColors.alpha(panel) == 0xCC)
        #expect(GlassColors.red(panel) == 0x4B)
        #expect(GlassColors.green(panel) == 0x44)
        #expect(GlassColors.blue(panel) == 0x32)
        #expect(GlassColors.mix(0xFF00_0000, 0xFFFF_FFFF, 0.5) == 0xFF80_8080)
    }

    @Test func aSheetIsThatPanelNearlySolidAndInkIsThePrototypesNearBlack() {
        let sheet = GlassColors.sheet(gold)
        #expect(GlassColors.alpha(sheet) == 0xF2)
        #expect(GlassColors.red(sheet) == 0x4B)
        #expect(GlassColors.green(sheet) == 0x44)
        #expect(GlassColors.blue(sheet) == 0x32)
        #expect(GlassColors.ink == 0xFF0B_0D12)
    }

    @Test func whiteTypeStaysReadableOnAPanelOverAnyArtwork() {
        let recursion = ArtworkPalette(dominant: 0xFFFD_F010, dark: 0xFF19_1700, vivid: 0xFFE2_D700, light: 0xFFE9_E7C1)
        for p in [gold, recursion, red, ArtworkPalette.neutral] {
            let panel = GlassColors.over(GlassColors.panel(p), p.dark)
            #expect(GlassColors.contrast(0xFFFF_FFFF, panel) >= 7)
        }
        #expect(abs(GlassColors.contrast(0xFF00_0000, 0xFFFF_FFFF) - 21) < 0.01)
    }

    @Test func asksOnceAndNeverAgainForWhatIsKnownOrMissing() {
        var book = ArtworkColorBook()
        #expect(book.toAsk(["/a", "/b", "/a", ""], now: 0) == ["/a", "/b"])
        #expect(book.toAsk(["/a", "/b"], now: 0).isEmpty)
        #expect(book.answered(["/a", "/b"], colors: ["/a": red], missing: ["/b"], now: 0) == ["/a"])
        #expect(book.palette("/a") == red)
        #expect(book.isMissing("/b"))
        #expect(book.toAsk(["/a", "/b"], now: 99_999).isEmpty)
    }

    @Test func aRequestAsksForAtMostSixty() {
        var book = ArtworkColorBook()
        let sources = (1...75).map { "/p\($0)" }
        #expect(book.toAsk(sources, now: 0).count == 60)
        #expect(book.toAsk(sources, now: 0) == (61...75).map { "/p\($0)" })
    }

    @Test func pendingIsAskedAgainAfterThreeTenAndThirtySecondsThenLeft() {
        var book = ArtworkColorBook()
        var now: TimeInterval = 0
        func pendingRound() {
            let asked = book.toAsk(["/slow"], now: now)
            #expect(asked == ["/slow"])
            book.answered(asked, colors: [:], missing: [], now: now)
        }
        pendingRound()
        #expect(book.nextDueAt == 3)
        #expect(book.toAsk(["/slow"], now: 2.9).isEmpty)
        now = 3; #expect(book.due(now: now) == ["/slow"]); pendingRound()
        #expect(book.nextDueAt == 13)
        now = 13; pendingRound()
        #expect(book.nextDueAt == 43)
        now = 43; pendingRound()
        #expect(book.isMissing("/slow"))
        #expect(book.nextDueAt == nil)
    }

    @Test func aFailedRequestWaitsAndALaterAnswerClearsIt() {
        var book = ArtworkColorBook()
        book.failed(book.toAsk(["/a"], now: 0), now: 0)
        #expect(book.nextDueAt == 3)
        let again = book.toAsk(["/a"], now: 3)
        #expect(book.answered(again, colors: ["/a": red], missing: [], now: 3) == ["/a"])
        #expect(book.nextDueAt == nil)
    }

    @Test func theLeastRecentlyUsedColoursAreDroppedFirst() {
        var book = ArtworkColorBook(capacity: 2)
        book.restore([("/old", red), ("/kept", blue)])
        #expect(book.palette("/old") == red)
        book.answered(book.toAsk(["/new"], now: 0), colors: ["/new": blue], missing: [], now: 0)
        #expect(book.palette("/kept") == nil)
        #expect(book.snapshot.map(\.0) == ["/old", "/new"])
    }
}

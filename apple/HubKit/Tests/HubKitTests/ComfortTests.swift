import Foundation
import Testing
@testable import HubKit

/// Comfort (#37): Android's `ScreenComfortTest`, case for case, and where it
/// is kept and how a black page is drawn.
struct ComfortTests {
    @Test func theDimIsClearAtFullBrightnessAndBoundedAtTheDimmest() {
        #expect(ScreenComfort.dimAlpha(1) == 0)
        #expect(ScreenComfort.dimAlpha(0) == 0.85)
        #expect(abs(ScreenComfort.dimAlpha(0.5) - 0.425) < 0.001)
        #expect(ScreenComfort.dimAlpha(9) == 0)
        #expect(abs(ScreenComfort(brightness: ScreenComfort.minBrightness).dimAlpha - 0.765) < 0.001)
    }

    @Test func warmthMultipliesWhiteTowardsCandlelightAndLeavesBlackBlack() {
        #expect(ScreenComfort.warmColor(0) == ScreenComfort.white)
        #expect(ScreenComfort.warmColor(1) == ScreenComfort.candle)
        #expect(ScreenComfort.warmColor(3) == ScreenComfort.candle)
        let half = ScreenComfort.warmColor(0.5)
        #expect((half >> 16) & 0xFF == 255)
        #expect(abs(Double((half >> 8) & 0xFF) - Double(255 + 0xB4) / 2) <= 1)
        #expect(abs(Double(half & 0xFF) - Double(255 + 0x6B) / 2) <= 1)
        // Red stays full and blue drops most: amber, never a grey that dims as well.
        #expect(half & 0xFF < (half >> 8) & 0xFF)
        // Multiplied, black is black at any warmth.
        func multiply(_ page: UInt32, _ over: UInt32) -> UInt32 { ((page & 0xFF) * (over & 0xFF)) / 255 }
        #expect(multiply(0, ScreenComfort.warmColor(1)) == 0)
    }

    @Test func nothingIsDrawnUntilSomethingIsChosen() {
        #expect(ScreenComfort().drawsNothing)
        #expect(!ScreenComfort(brightness: 0.9).drawsNothing)
        #expect(!ScreenComfort(warmth: 0.1).drawsNothing)
    }

    @Test func theScreenStaysOnOnlyWhileNarrationPlaysAndOnlyIfAsked() {
        #expect(ScreenComfort().keepsScreenOn(narrating: true))
        #expect(!ScreenComfort().keepsScreenOn(narrating: false))
        #expect(!ScreenComfort(awakeWhileNarrating: false).keepsScreenOn(narrating: true))
    }

    @Test func labels() {
        #expect(ScreenComfort.brightnessLabel(1) == "100%")
        #expect(ScreenComfort.brightnessLabel(0.35) == "35%")
        #expect(ScreenComfort.warmthLabel(0) == "Off")
        #expect(ScreenComfort.warmthLabel(0.4) == "40%")
    }

    @Test func keptForEveryReaderWithinItsBounds() throws {
        let defaults = try #require(UserDefaults(suiteName: "comfort-tests-\(UUID().uuidString)"))
        let store = ComfortStore(defaults: defaults)
        #expect(store.load() == ScreenComfort())
        store.save(ScreenComfort(brightness: 0.4, warmth: 0.3, awakeWhileNarrating: false))
        #expect(store.load() == ScreenComfort(brightness: 0.4, warmth: 0.3, awakeWhileNarrating: false))
        store.save(ScreenComfort(brightness: 0.01, warmth: 4))
        #expect(store.load().brightness == ScreenComfort.minBrightness)
        #expect(store.load().warmth == 1)
    }

    /// Comfort's black page went when Dark came (#47): a device that had it on is on Dark, and its
    /// brightness and warmth stay as they were.
    @Test func aDeviceThatHadTheBlackPageIsOnDarkAndKeepsTheRestOfItsComfort() throws {
        let defaults = try #require(UserDefaults(suiteName: "comfort-migrate-\(UUID().uuidString)"))
        defaults.set(Data(#"{"brightness":0.4,"warmth":0.3,"blackPage":true,"awakeWhileNarrating":false}"#.utf8), forKey: "reader.comfort")
        let look = EpubAppearanceStore.load(defaults)
        #expect(look.theme == .black)
        #expect(ComfortStore(defaults: defaults).load() == ScreenComfort(brightness: 0.4, warmth: 0.3, awakeWhileNarrating: false))
        // Taken out of what is stored, so a later theme of its own is not undone.
        let stored = try #require(defaults.data(forKey: "reader.comfort"))
        #expect(!String(decoding: stored, as: UTF8.self).contains("blackPage"))
        var chosen = look
        chosen.theme = .light
        EpubAppearanceStore.save(chosen, to: defaults)
        #expect(EpubAppearanceStore.load(defaults).theme == .light)
        // One that never had it is left alone.
        let other = try #require(UserDefaults(suiteName: "comfort-migrate-\(UUID().uuidString)"))
        other.set(Data(#"{"brightness":0.8,"warmth":0,"blackPage":false,"awakeWhileNarrating":true}"#.utf8), forKey: "reader.comfort")
        #expect(EpubAppearanceStore.load(other).theme == .sepia)
    }
}

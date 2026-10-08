import Foundation
import Testing
@testable import HubKit

/// The storage bar (#48): the device's real numbers split into other apps,
/// JellyHub, what is coming and free; its words; and when it is up.
struct StorageBarTests {
    private let gigabyte: Int64 = 1 << 30

    @Test func thePartsAddUpToTheDevicesCapacity() {
        // 256 GB, 100 free, JellyHub holds 6.
        let bar = StorageBar(capacity: 256 * gigabyte, free: 100 * gigabyte, app: 6 * gigabyte, coming: 4 * gigabyte)
        #expect(bar.app == 6 * gigabyte)
        #expect(bar.coming == 4 * gigabyte)
        #expect(bar.free == 96 * gigabyte, "what is coming will come out of free space")
        #expect(bar.other == 150 * gigabyte)
        #expect(Part.allCases.reduce(Int64(0)) { $0 + bar.bytes($1) } == bar.capacity)
    }

    @Test func aPreviewAddsToWhatIsComingWithoutMoreThanThereIsRoomFor() {
        let bar = StorageBar(capacity: 100 * gigabyte, free: 10 * gigabyte, app: 0, coming: 2 * gigabyte, adding: 30 * gigabyte)
        #expect(bar.coming == 10 * gigabyte, "no more than free space")
        #expect(bar.free == 0)
        #expect(Part.allCases.reduce(Int64(0)) { $0 + bar.bytes($1) } == bar.capacity)
    }

    @Test func oddNumbersFromTheDeviceNeverMakeANegativePart() {
        // JellyHub reports more than the used space, and free is more than the capacity.
        let bar = StorageBar(capacity: 100, free: 150, app: 500)
        #expect(Part.allCases.allSatisfy { bar.bytes($0) >= 0 })
        #expect(Part.allCases.reduce(Int64(0)) { $0 + bar.bytes($1) } == bar.capacity)
        let empty = StorageBar(capacity: 0, free: 0, app: 0)
        #expect(empty.capacity == 1, "never divides by nothing")
    }

    @Test func widthsFollowTheBytesOverTheWidthGiven() {
        let bar = StorageBar(capacity: 100 * gigabyte, free: 40 * gigabyte, app: 10 * gigabyte, coming: 10 * gigabyte)
        let widths = bar.widths(over: 400)
        #expect(widths[.other] == 200 && widths[.app] == 40 && widths[.coming] == 40 && widths[.free] == 120)
    }

    @Test func aSmallDownloadIsStillAFewPointsWideTakenFromFreeSpace() {
        // 200 KB of 256 GB is a hair; the bar still shows it, six points.
        let bar = StorageBar(capacity: 256 * gigabyte, free: 100 * gigabyte, app: 0, coming: 200 << 10)
        let widths = bar.widths(over: 600)
        #expect(widths[.coming] == 6)
        #expect(abs((widths.values.reduce(0, +)) - 600) < 0.0001, "the bar is still its full width")
        #expect((widths[.free] ?? 0) < 600 * Double(100) / 256)
    }

    @Test func nothingComingMeansNoThinSegmentAtAll() {
        let bar = StorageBar(capacity: 100, free: 50, app: 10)
        #expect(bar.widths(over: 100)[.coming] == 0)
    }

    @Test func aFullDeviceGivesTheThinSegmentFromOtherApps() {
        let bar = StorageBar(capacity: 1_000_000, free: 100, app: 0, coming: 100)
        let widths = bar.widths(over: 1_000)
        #expect(widths[.coming] == 6 && (widths[.free] ?? -1) == 0)
        #expect(abs(widths.values.reduce(0, +) - 1_000) < 0.0001)
    }

    @Test func theWordsOverTheBarSayWhatIsComingAndWhatIsHere() {
        #expect(StorageBarWords.line(coming: 2, onDevice: 1, device: "iPad") == "2 coming · 1 on this iPad")
        #expect(StorageBarWords.line(coming: 2, onDevice: 0, device: "iPad") == "2 coming")
        #expect(StorageBarWords.line(coming: 0, onDevice: 3, device: "iPhone") == "3 on this iPhone")
        #expect(StorageBarWords.line(coming: 0, onDevice: 0, device: "Mac") == "Nothing downloaded")
    }

    @Test func thePreviewSaysWhatWouldBeAddedAndWhatIsLeft() {
        #expect(StorageBarWords.preview(adding: 2 * gigabyte, free: 10 * gigabyte) == "Adds 2.0 GB · 8.0 GB free after")
        #expect(StorageBarWords.preview(adding: 0, free: 10) == "Nothing to add")
        #expect(StorageBarWords.preview(adding: 20, free: 10) == "Adds 20 B · 0 B free after")
    }

    // MARK: When it is up

    @Test func theBarRisesWithTheFirstDownloadAndFadesThreeSecondsAfterTheLast() {
        var state = StorageBarVisibility()
        let start = Date(timeIntervalSince1970: 1_000)
        #expect(!state.isVisible(at: start), "nothing is coming: no bar")
        state.update(coming: 2, now: start)
        #expect(state.isVisible(at: start))
        state.update(coming: 1, now: start.addingTimeInterval(60))
        #expect(state.isVisible(at: start.addingTimeInterval(60)))
        // The last one finishes.
        let done = start.addingTimeInterval(120)
        state.update(coming: 0, now: done)
        #expect(state.isVisible(at: done.addingTimeInterval(2.9)), "it lingers")
        #expect(!state.isVisible(at: done.addingTimeInterval(3.0)), "and fades")
        #expect(state.nextChange == done.addingTimeInterval(3))
    }

    @Test func aNewDownloadBeforeItFadesKeepsItUp() {
        var state = StorageBarVisibility()
        let start = Date(timeIntervalSince1970: 0)
        state.update(coming: 1, now: start)
        state.update(coming: 0, now: start.addingTimeInterval(10))
        state.update(coming: 1, now: start.addingTimeInterval(11))
        #expect(state.isVisible(at: start.addingTimeInterval(20)))
        #expect(state.nextChange == nil)
    }

    @Test func asksWithNothingComingNeverStartTheBar() {
        var state = StorageBarVisibility()
        let start = Date(timeIntervalSince1970: 0)
        state.update(coming: 0, now: start)
        state.update(coming: 0, now: start.addingTimeInterval(5))
        #expect(!state.isVisible(at: start.addingTimeInterval(6)) && state.nextChange == nil)
    }

    private typealias Part = StorageBar.Part
}

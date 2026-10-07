import Foundation
import Testing
@testable import HubKit

/// A comic's page list kept for an outage (#37; Android's
/// `ReadingManifestCacheTest`): it survives the cache being made again, and
/// never opens for another account or another edition.
struct ReadingManifestCacheTests {
    private let root = FileManager.default.temporaryDirectory
        .appendingPathComponent("reading-manifests-\(UUID().uuidString)", isDirectory: true)
    private let key = ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "edition", kind: "pages")
    private let answer = Data(#"{"workId":"work","sourceItemId":"edition","pageCount":12,"currentPage":3}"#.utf8)

    @Test func aManifestSurvivesRecreationAndCannotCrossAccountsOrEditions() throws {
        defer { try? FileManager.default.removeItem(at: root) }
        try ReadingManifestCache(root: root).save(key, answer: answer)
        let reopened = ReadingManifestCache(root: root)
        let kept = try #require(reopened.read(key))
        #expect(kept == ReadingPublicationManifest(workId: "work", sourceItemId: "edition", pageCount: 12, currentPage: 3))
        #expect(reopened.read(ReadingCheckpointKey(scope: "other", workId: "work", sourceItemId: "edition", kind: "pages")) == nil)
        #expect(reopened.read(ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "other", kind: "pages")) == nil)
        reopened.remove(key)
        #expect(reopened.read(key) == nil)
    }

    @Test func anAnswerForAnotherEditionIsNotKept() {
        defer { try? FileManager.default.removeItem(at: root) }
        let other = ReadingCheckpointKey(scope: "profile", workId: "work", sourceItemId: "other", kind: "pages")
        #expect(throws: (any Error).self) { try ReadingManifestCache(root: root).save(other, answer: answer) }
        #expect(throws: (any Error).self) { try ReadingManifestCache(root: root).save(key, answer: Data("{".utf8)) }
        #expect(ReadingManifestCache(root: root).read(other) == nil)
    }
}

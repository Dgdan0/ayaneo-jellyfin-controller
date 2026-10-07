import Testing
@testable import HubKit

/// Whose title and buttons the lock screen shows (#33).
struct NowPlayingOwnersTests {
    @Test func theLastToPlayIsInFrontAndTheOneBehindComesBack() {
        var owners = NowPlayingOwners()
        #expect(owners.current == nil)
        owners.take(.audiobook)
        #expect(owners.current == .audiobook)
        // A video over a paused audiobook: the video's.
        owners.take(.video)
        #expect(owners.current == .video)
        #expect(owners.holds(.audiobook))
        // The audiobook played again from its mini player: its.
        owners.take(.audiobook)
        #expect(owners.current == .audiobook)
        owners.take(.video)
        // The video closed: the audiobook's again.
        owners.release(.video)
        #expect(owners.current == .audiobook)
        owners.release(.audiobook)
        #expect(owners.current == nil)
    }

    @Test func releasingOneBehindLeavesTheOneInFront() {
        var owners = NowPlayingOwners()
        owners.take(.audiobook)
        owners.take(.video)
        owners.release(.audiobook)
        #expect(owners.current == .video)
        #expect(!owners.holds(.audiobook))
        // Released twice, or never held: nothing changes.
        owners.release(.audiobook)
        owners.release(.narration)
        #expect(owners.current == .video)
    }
}

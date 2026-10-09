import Foundation

/// The ebook reader's hub paths (#25, phase 4): Android's
/// `downloadReadingEpub`, `readingEpubPosition` and `saveReadingEpubPosition`,
/// with the same paths. In a file of their own beside the page reader's.
extension HubEndpoints {
    /// An edition's EPUB, whole (`…/file`). Read along will ask for
    /// `format=readaloud` with `audio=omit`: the slim edition, its narration
    /// streamed from the audiobook's tracks instead.
    public static func readingEpubFile(workId: String, sourceItemId: String, format: String = "",
                                       omitAudio: Bool = false) -> HubRequest {
        var query: [String] = []
        if !format.isEmpty { query.append("format=" + encode(format)) }
        if omitAudio { query.append("audio=omit") }
        return HubRequest(publicationPath(workId: workId, sourceItemId: sourceItemId) + "/file"
                          + (query.isEmpty ? "" : "?" + query.joined(separator: "&")), slow: true)
    }

    /// Where the person is in the book, as Storyteller keeps it: a Readium locator, or none.
    public static func readingEpubPosition(workId: String, sourceItemId: String) -> HubRequest {
        HubRequest(publicationPath(workId: workId, sourceItemId: sourceItemId) + "/position")
    }

    /// The place reached, sent with the place the hub had (`checkBase`), so a
    /// place another device moved is a 409 and a question, never an
    /// overwrite. Never retried here: the keeper sends it again on its own terms.
    public static func saveReadingEpubPosition(workId: String, sourceItemId: String,
                                               _ body: EpubPositionBody) -> HubRequest {
        HubRequest(publicationPath(workId: workId, sourceItemId: sourceItemId) + "/position", method: .post,
                   body: body.encoded())
    }
}

/// The hub's answer to `…/position`: the locator as JSON, or nil when the
/// book has no place yet.
public struct EpubPosition: Equatable, Sendable {
    public let locator: String?
    public let timestamp: Int64
    public let updatedAt: String
    /// When the book was last started over (#60); 0 when it never was.
    public let resetAt: Int64

    public init(locator: String?, timestamp: Int64 = 0, updatedAt: String = "", resetAt: Int64 = 0) {
        self.locator = locator
        self.timestamp = timestamp
        self.updatedAt = updatedAt
        self.resetAt = resetAt
    }

    /// Read as the hub writes it; the locator kept as it came, in one line.
    public static func decode(_ data: Data) -> EpubPosition? {
        guard let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { return nil }
        let locator = (object["locator"] as? [String: Any]).flatMap(BookLocator.canonical)
        return EpubPosition(locator: locator, timestamp: (object["timestamp"] as? NSNumber)?.int64Value ?? 0,
                            updatedAt: object["updatedAt"] as? String ?? "",
                            resetAt: (object["resetAt"] as? NSNumber)?.int64Value ?? 0)
    }
}

/// A place sent to `…/position`: the locator, and the one the hub had when
/// this device last read it (`expectedLocator`, nil for none), which the hub
/// checks while `checkBase` is on.
public struct EpubPositionBody: Equatable, Sendable {
    public let locator: String
    public let timestamp: Int64
    public let checkBase: Bool
    public let expectedLocator: String?
    /// The start over this device last applied (#60); nil sends nothing.
    public let resetSeen: Int64?

    public init(locator: String, timestamp: Int64, checkBase: Bool = true, expectedLocator: String?, resetSeen: Int64? = nil) {
        self.locator = locator
        self.timestamp = timestamp
        self.checkBase = checkBase
        self.expectedLocator = expectedLocator
        self.resetSeen = resetSeen
    }

    /// The hub refuses fields it does not know, and takes the locators as objects.
    public func encoded() -> Data {
        var fields: [String: Any] = ["locator": BookLocator.object(locator) ?? [:], "checkBase": checkBase]
        if timestamp > 0 { fields["timestamp"] = timestamp }
        if checkBase { fields["expectedLocator"] = expectedLocator.flatMap(BookLocator.object) ?? NSNull() }
        if let resetSeen { fields["resetSeen"] = NSNumber(value: resetSeen) }
        return (try? JSONSerialization.data(withJSONObject: fields, options: [.sortedKeys, .withoutEscapingSlashes]))
            ?? Data("{}".utf8)
    }
}

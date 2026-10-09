import Foundation

// A profile's highlights and notes of a book (#62): the hub's four routes,
// scope `reading`, the profile from `X-Jellyfin-User`; `workId` is the
// work's, never an edition's.
extension HubEndpoints {
    /// `GET …/annotations[?since=n]`: without `since`, the live ones, oldest
    /// first; with it (even 0), every version stored after that `syncedAt`,
    /// tombstones included.
    public static func readingAnnotations(workId: String, since: Int64? = nil) -> HubRequest {
        HubRequest(annotationsPath(workId) + (since.map { "?since=\(max(0, $0))" } ?? ""))
    }

    /// `PUT …/annotations/{id}`: keeps one under its own id; one the hub has not seen is made.
    public static func saveReadingAnnotation(workId: String, _ annotation: ReadingAnnotation) -> HubRequest {
        HubRequest(annotationsPath(workId) + "/" + encode(annotation.id), method: .put, body: AnnotationBodies.of(annotation))
    }

    /// `DELETE …/annotations/{id}?updatedAt=ms`: a tombstone stamped `updatedAt`,
    /// also for an id the hub never saw.
    public static func deleteReadingAnnotation(workId: String, id: String, updatedAt: Int64) -> HubRequest {
        HubRequest(annotationsPath(workId) + "/" + encode(id) + "?updatedAt=\(max(0, updatedAt))", method: .delete)
    }

    private static func annotationsPath(_ workId: String) -> String {
        "/v1/reading/works/" + encode(workId) + "/annotations"
    }
}

/// `GET …/annotations`: a book's highlights, and the hub's clock as it answered.
public struct ReadingAnnotationsResponse: Decodable, Equatable, Sendable {
    public var workId: String
    public var annotations: [ReadingAnnotation]
    public var serverTime: Int64

    public init(workId: String = "", annotations: [ReadingAnnotation] = [], serverTime: Int64 = 0) {
        self.workId = workId
        self.annotations = annotations
        self.serverTime = serverTime
    }

    enum CodingKeys: String, CodingKey { case workId, annotations, serverTime }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), annotations: c.value(.annotations, []), serverTime: c.value(.serverTime, 0))
    }
}

/// Every write's answer: what the hub holds under that id now, and whether
/// this write is it. A write older than what is held changed nothing and
/// answers what won: adopt it.
public struct ReadingAnnotationWritten: Decodable, Equatable, Sendable {
    public var workId: String
    public var annotation: ReadingAnnotation
    public var applied: Bool

    public init(workId: String = "", annotation: ReadingAnnotation, applied: Bool) {
        self.workId = workId
        self.annotation = annotation
        self.applied = applied
    }

    enum CodingKeys: String, CodingKey { case workId, annotation, applied }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(workId: c.value(.workId, ""), annotation: try c.decode(ReadingAnnotation.self, forKey: .annotation),
                  applied: c.value(.applied, false))
    }
}

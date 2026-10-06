import Foundation

/// The page reader's hub paths (#25, phase 3): Android's `HubEndpoints`
/// `readingPublication…` routes, with the same paths and encoding. Kept in a
/// file of their own beside the Books side's `ReadingEndpoints`.
extension HubEndpoints {
    /// An issue or volume's pages and where the person is in it. The hub may
    /// have Kavita open the archive first, which it gives two minutes, so this
    /// takes the long budget.
    public static func readingPublication(workId: String, sourceItemId: String) -> HubRequest {
        HubRequest(publicationPath(workId: workId, sourceItemId: sourceItemId), slow: true)
    }

    /// A page as Kavita serves it, from 0: the path the image loader asks for.
    public static func readingPublicationPage(workId: String, sourceItemId: String, page: Int) -> String {
        publicationPath(workId: workId, sourceItemId: sourceItemId) + "/pages/\(max(0, page))"
    }

    /// A page's thumbnail beside its page (#16, C4): `…/pages/{page}/thumb?w=`,
    /// scaled by the hub, which keeps each width between 64 and 512.
    public static func readingPublicationThumb(workId: String, sourceItemId: String, page: Int, width: Int) -> String {
        readingPublicationPage(workId: workId, sourceItemId: sourceItemId, page: page)
            + "/thumb?w=\(min(512, max(64, width)))"
    }

    /// The page the person is on, saved to Kavita. Never retried here: the
    /// reader's outbox sends it again on its own terms.
    public static func saveReadingPublicationProgress(workId: String, sourceItemId: String,
                                                      _ body: ReadingPublicationProgressBody) -> HubRequest {
        HubRequest(publicationPath(workId: workId, sourceItemId: sourceItemId) + "/progress", method: .post,
                   body: json(body))
    }

    private static func publicationPath(workId: String, sourceItemId: String) -> String {
        "/v1/reading/works/" + encode(workId) + "/publications/" + encode(sourceItemId)
    }
}

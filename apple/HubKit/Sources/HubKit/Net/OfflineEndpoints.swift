import Foundation

/// Offline downloads' hub routes (#5): Android's `selectOffline`,
/// `prepareOffline`, `renewOffline` and `syncOfflineProgress`, with the same
/// paths, and the routes an Apple download adds (status, release, retry). The
/// file is fetched from the route a manifest names (`mediaUrl`), which
/// carries the grant.
extension HubEndpoints {
    /// Every season and episode of a series, what can be downloaded and its
    /// size; with `format` "apple", the MP4's size and what will not come across.
    public static func offlineSelection(seriesId: String, format: String = "") -> HubRequest {
        HubRequest("/v1/offline/series/" + encode(seriesId) + "/selection" + (format.isEmpty ? "" : "?format=" + encode(format)))
    }

    /// Grants for the items in `body`; the same keys again give the same grants.
    public static func prepareOffline(_ body: OfflinePrepareBody) -> HubRequest {
        HubRequest("/v1/offline/prepare", method: .post, body: json(body), slow: true)
    }

    /// Where an Apple download's MP4 stands on the PC: queued, preparing (with
    /// how far), ready (its exact size and ETag) or failed. Asking for one that
    /// is not going (released, aged out, lost in a restart) queues it again.
    public static func offlineStatus(grantId: String) -> HubRequest {
        HubRequest(grant(grantId) + "/status")
    }

    /// A grant's file, by the route its manifest names. Through the client
    /// only for the demo hub: a real hub's goes through the background session.
    public static func offlineMedia(_ mediaUrl: String) -> HubRequest {
        HubRequest(mediaUrl)
    }

    /// The PC's MP4 let go once this device has its copy, or a download
    /// removed before it finished: its job stops and its file is freed. The
    /// grant stays good; asking for its status later starts again.
    public static func releaseOffline(grantId: String) -> HubRequest {
        HubRequest(grant(grantId) + "/media", method: .delete)
    }

    /// A failed MP4 made again; any other state is left as it is. Answers the status.
    public static func retryOffline(grantId: String) -> HubRequest {
        HubRequest(grant(grantId) + "/retry", method: .post)
    }

    /// An expired grant made good again, under the same routes; 409 `source_changed`
    /// when the file on the server is no longer the one being downloaded.
    public static func renewOffline(grantId: String) -> HubRequest {
        HubRequest(grant(grantId) + "/renew", method: .post)
    }

    /// Watches made on this device, sent once the hub can be reached, as the
    /// profile that made them (`user`; nil is the client's current one).
    public static func syncOfflineProgress(_ body: OfflineProgressSyncBody, user: String? = nil) -> HubRequest {
        HubRequest("/v1/offline/progress/sync", method: .post, body: json(body), user: user)
    }

    private static func grant(_ grantId: String) -> String {
        "/v1/offline/grants/" + encode(grantId)
    }
}

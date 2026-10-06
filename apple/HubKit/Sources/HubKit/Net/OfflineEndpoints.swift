import Foundation

/// Offline downloads' hub routes (#5): Android's `selectOffline`,
/// `prepareOffline`, `renewOffline` and `syncOfflineProgress`, with the same
/// paths. The file and its subtitles are fetched from the routes a manifest
/// names (`mediaUrl`, `subtitles[].url`), which carry the grant.
extension HubEndpoints {
    /// Every season and episode of a series, what can be downloaded and its size.
    public static func offlineSelection(seriesId: String) -> HubRequest {
        HubRequest("/v1/offline/series/" + encode(seriesId) + "/selection")
    }

    /// Grants for the items in `body`; the same keys again give the same grants.
    public static func prepareOffline(_ body: OfflinePrepareBody) -> HubRequest {
        HubRequest("/v1/offline/prepare", method: .post, body: json(body), slow: true)
    }

    /// An expired grant made good again, under the same routes; 409 `source_changed`
    /// when the file on the server is no longer the one being downloaded.
    public static func renewOffline(grantId: String) -> HubRequest {
        HubRequest("/v1/offline/grants/" + encode(grantId) + "/renew", method: .post)
    }

    /// Watches made on this device, sent once the hub can be reached.
    public static func syncOfflineProgress(_ body: OfflineProgressSyncBody) -> HubRequest {
        HubRequest("/v1/offline/progress/sync", method: .post, body: json(body))
    }
}

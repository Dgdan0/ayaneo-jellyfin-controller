import Foundation

/// The Activity tab's routes (#29): the joined transfers, what may be done to
/// one, and qBittorrent's speed limits. Android's `HubEndpoints` for the same.
extension HubEndpoints {
    /// The transfers, problems first; finished ones only with `includeFinished`.
    public static func activity(includeFinished: Bool = false) -> HubRequest {
        HubRequest("/v1/activity" + (includeFinished ? "?all=true" : ""))
    }

    /// "stop", "start", "priority_up" or "priority_down". The id is encoded,
    /// although a colon is legal in a path segment: "qbit:…" must arrive whole.
    public static func downloadAction(id: String, action: String) -> HubRequest {
        HubRequest("/v1/downloads/" + encode(id) + "/" + action, method: .post)
    }

    /// Takes the transfer out of the client. `deleteFiles` is always written
    /// out, never left to the hub's default: whether the data still exists
    /// afterwards must be explicit where it is asked for.
    public static func downloadDelete(id: String, deleteFiles: Bool) -> HubRequest {
        HubRequest("/v1/downloads/" + encode(id) + "?deleteFiles=" + (deleteFiles ? "true" : "false"), method: .delete)
    }

    /// Drops an *arr queue row. All three switches are sent, removeFromClient
    /// especially, whose default on the hub is true.
    public static func queueRemove(service: String, queueId: Int, removeFromClient: Bool = true, blocklist: Bool = false,
                                   search: Bool = false) -> HubRequest {
        HubRequest("/v1/queue/" + encode(service) + "/\(queueId)/remove?removeFromClient=\(removeFromClient)"
                       + "&blocklist=\(blocklist)&search=\(search)", method: .post)
    }

    public static let bandwidth = HubRequest("/v1/downloads/bandwidth")

    public static func setBandwidth(_ change: BandwidthChange) -> HubRequest {
        HubRequest("/v1/downloads/bandwidth", method: .post, body: change.encoded())
    }
}

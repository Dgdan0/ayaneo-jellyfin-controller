import Foundation

/// One artwork's Glass colours from `GET /v1/img/colors`, each "#rrggbb"
/// (GLASS_PLAN.md, issue #10). The hub works them out; the app never analyses
/// pictures.
public struct ArtworkColorSet: Decodable, Equatable, Sendable {
    public var dominant: String
    public var dark: String
    public var vivid: String
    public var light: String

    public init(dominant: String = "", dark: String = "", vivid: String = "", light: String = "") {
        self.dominant = dominant
        self.dark = dark
        self.vivid = vivid
        self.light = light
    }

    enum CodingKeys: String, CodingKey { case dominant, dark, vivid, light }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(dominant: c.value(.dominant, ""), dark: c.value(.dark, ""), vivid: c.value(.vivid, ""), light: c.value(.light, ""))
    }
}

/// Keys echo each `src` exactly as sent. `pending` is still being worked out on
/// the hub: ask again shortly. `missing` cannot be read: do not ask again.
public struct ArtworkColorsResponse: Decodable, Equatable, Sendable {
    public var colors: [String: ArtworkColorSet]
    public var pending: [String]
    public var missing: [String]

    public init(colors: [String: ArtworkColorSet] = [:], pending: [String] = [], missing: [String] = []) {
        self.colors = colors
        self.pending = pending
        self.missing = missing
    }

    enum CodingKeys: String, CodingKey { case colors, pending, missing }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(colors: c.value(.colors, [:]), pending: c.value(.pending, []), missing: c.value(.missing, []))
    }
}

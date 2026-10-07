import Foundation

/// `GET /v1/manage/monitor`: the media PC itself, its disks, containers and
/// what is playing (Android's `model/ServerMonitor`). Activity reads its
/// disks (#29); the server monitor page reads the rest.
public struct ServerMonitor: Decodable, Equatable, Sendable {
    public var host: HostSnapshot
    public var containers: [HostContainer]
    public var sessions: [HostSession]
    public var dockerWarning: String
    public var sessionWarning: String
    public var checkedAt: String

    public init(host: HostSnapshot = HostSnapshot(), containers: [HostContainer] = [], sessions: [HostSession] = [],
                dockerWarning: String = "", sessionWarning: String = "", checkedAt: String = "") {
        self.host = host
        self.containers = containers
        self.sessions = sessions
        self.dockerWarning = dockerWarning
        self.sessionWarning = sessionWarning
        self.checkedAt = checkedAt
    }

    enum CodingKeys: String, CodingKey { case host, containers, sessions, dockerWarning, sessionWarning, checkedAt }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(host: c.value(.host, HostSnapshot()), containers: c.value(.containers, []), sessions: c.value(.sessions, []),
                  dockerWarning: c.value(.dockerWarning, ""), sessionWarning: c.value(.sessionWarning, ""),
                  checkedAt: c.value(.checkedAt, ""))
    }
}

public struct HostSnapshot: Decodable, Equatable, Sendable {
    public var os: String
    /// Nil until the PC has been sampled twice.
    public var cpuPercent: Double?
    public var memoryTotalBytes: Int64
    public var memoryAvailableBytes: Int64
    public var uptimeSeconds: Int64
    public var disks: [HostDisk]
    public var warnings: [String]

    public init(os: String = "", cpuPercent: Double? = nil, memoryTotalBytes: Int64 = 0, memoryAvailableBytes: Int64 = 0,
                uptimeSeconds: Int64 = 0, disks: [HostDisk] = [], warnings: [String] = []) {
        self.os = os
        self.cpuPercent = cpuPercent
        self.memoryTotalBytes = memoryTotalBytes
        self.memoryAvailableBytes = memoryAvailableBytes
        self.uptimeSeconds = uptimeSeconds
        self.disks = disks
        self.warnings = warnings
    }

    enum CodingKeys: String, CodingKey { case os, cpuPercent, memoryTotalBytes, memoryAvailableBytes, uptimeSeconds, disks, warnings }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(os: c.value(.os, ""), cpuPercent: c.optional(.cpuPercent), memoryTotalBytes: c.value(.memoryTotalBytes, 0),
                  memoryAvailableBytes: c.value(.memoryAvailableBytes, 0), uptimeSeconds: c.value(.uptimeSeconds, 0),
                  disks: c.value(.disks, []), warnings: c.value(.warnings, []))
    }
}

/// A drive of the media PC: "E:\" and its size and free space, in bytes.
public struct HostDisk: Decodable, Equatable, Sendable, Identifiable {
    public var name: String
    public var totalBytes: Int64
    public var availableBytes: Int64

    public var id: String { name }

    public init(name: String, totalBytes: Int64 = 0, availableBytes: Int64 = 0) {
        self.name = name
        self.totalBytes = totalBytes
        self.availableBytes = availableBytes
    }

    enum CodingKeys: String, CodingKey { case name, totalBytes, availableBytes }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(name: c.value(.name, ""), totalBytes: c.value(.totalBytes, 0), availableBytes: c.value(.availableBytes, 0))
    }

    /// How much of it is used, 0…1.
    public var usedFraction: Double {
        totalBytes > 0 ? min(max(Double(totalBytes - availableBytes) / Double(totalBytes), 0), 1) : 0
    }
}

public struct HostContainer: Decodable, Equatable, Sendable {
    public var name: String
    public var image: String
    public var state: String
    public var status: String

    public init(name: String = "", image: String = "", state: String = "", status: String = "") {
        self.name = name
        self.image = image
        self.state = state
        self.status = status
    }

    enum CodingKeys: String, CodingKey { case name, image, state, status }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(name: c.value(.name, ""), image: c.value(.image, ""), state: c.value(.state, ""), status: c.value(.status, ""))
    }
}

public struct HostSession: Decodable, Equatable, Sendable {
    public var title: String
    public var device: String
    public var client: String
    public var method: String
    public var paused: Bool

    public init(title: String = "", device: String = "", client: String = "", method: String = "", paused: Bool = false) {
        self.title = title
        self.device = device
        self.client = client
        self.method = method
        self.paused = paused
    }

    enum CodingKeys: String, CodingKey { case title, device, client, method, paused }

    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(title: c.value(.title, ""), device: c.value(.device, ""), client: c.value(.client, ""),
                  method: c.value(.method, ""), paused: c.value(.paused, false))
    }
}

// swift-tools-version: 6.0
// HubKit holds everything that is not a view: talking to the hub, its response
// models and the rules shared with the Android client. It never imports SwiftUI
// or UIKit, so `swift test` runs it on the Mac in seconds, the way the Kotlin
// logic runs as plain JVM tests.
import PackageDescription

let package = Package(
    name: "HubKit",
    platforms: [.iOS(.v18), .macOS(.v15)],
    products: [
        .library(name: "HubKit", targets: ["HubKit"]),
    ],
    targets: [
        .target(name: "HubKit"),
        .testTarget(name: "HubKitTests", dependencies: ["HubKit"]),
    ]
)

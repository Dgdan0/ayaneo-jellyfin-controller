import CoreMedia
import Foundation
import HubKit
import VideoToolbox
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// This device as Jellyfin is told about it: a name for its dashboard, an id
/// that stays the same while the app is installed, and what AVPlayer can open
/// on this screen (`PlaybackProfile`).
@MainActor
enum PlaybackDeviceInfo {
    private static let idKey = "playback.deviceId"

    static func device() -> PlaybackDevice {
        let defaults = UserDefaults.standard
        let id = defaults.string(forKey: idKey) ?? {
            let made = "hub-apple-" + UUID().uuidString.lowercased()
            defaults.set(made, forKey: idKey)
            return made
        }()
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
        return PlaybackDevice(id: id, name: PlaybackProfile.deviceName(kind), version: version)
    }

    static func capabilities() -> PlaybackCapabilities {
        let (width, height, hdr) = screen
        return PlaybackProfile.capabilities(width: width, height: height,
                                            hevc: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC),
                                            hdrTypes: hdr ? ["HDR10", "HLG"] : [])
    }

    private static var kind: String {
        #if os(iOS)
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        #else
        "Mac"
        #endif
    }

    /// The screen in pixels, the long side first as the picture is shown, and
    /// whether it can show HDR.
    private static var screen: (Int, Int, Bool) {
        #if os(iOS)
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        guard let screen = scene?.screen else { return (1_920, 1_080, false) }
        let size = screen.nativeBounds.size
        return (Int(max(size.width, size.height)), Int(min(size.width, size.height)), screen.potentialEDRHeadroom > 1)
        #else
        guard let screen = NSScreen.main else { return (1_920, 1_080, false) }
        let size = screen.frame.size
        let scale = screen.backingScaleFactor
        return (Int(size.width * scale), Int(size.height * scale),
                screen.maximumPotentialExtendedDynamicRangeColorComponentValue > 1)
        #endif
    }
}

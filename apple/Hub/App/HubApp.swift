import HubKit
import SwiftUI
#if os(macOS)
import AppKit
#elseif os(iOS)
import UIKit
#endif

@main
struct HubApp: App {
    @State private var model = AppModel()

    init() {
        // A finished download's notification shows while the app is open too; nothing is asked here (#43).
        DownloadAlerts.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .font(HubType.body())
                .tint(.accentColor)
                // Glass is always dark: the page is the artwork's own dark
                // colour under its blurred picture (GLASS_PLAN.md).
                .preferredColorScheme(.dark)
        }
        // The Playback menu while a video is open (#33).
        .commands { PlayerCommands() }
        #if os(iOS)
        // Downloads finishing while the app was away: the system wakes it to
        // hand over their files, then waits for the background session's events.
        .backgroundTask(.urlSession(OfflineDownloader.sessionIdentifier)) {
            await OfflineLibrary.shared.backgroundEvents()
        }
        #endif
        #if os(macOS)
        .defaultSize(Self.windowSize)
        // The Glass page runs to the top of the window, the window buttons over
        // it, as the bars float over it on an iPad (the shell keeps clear of them).
        .windowStyle(.hiddenTitleBar)
        #endif
    }
}

extension HubApp {
    /// A new Mac window's size. Debug builds take HUB_WINDOW=900x620 from
    /// `scripts/mac.sh mac-shot`, which opens a fresh window each run: resizing
    /// one after it opened left the snapshot's scrolled content drawn where it
    /// had been (2026-10-04).
    static var windowSize: CGSize {
        #if DEBUG && os(macOS)
        if let size = DebugWindow.requestedSize { return size }
        #endif
        return CGSize(width: 1280, height: 820)
    }
}

/// The first run asks for the hub; after that, the app.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.isConfigured {
                MainView()
            } else {
                WelcomeView()
            }
        }
        #if DEBUG && os(iOS)
        .modifier(DebugWidth())
        .task { DebugOrientation.apply() }
        #elseif DEBUG && os(macOS)
        .task { await DebugWindow.apply() }
        #endif
        #if DEBUG
        // HUB_READ opens the comic reader at launch, and HUB_BOOK the ebook reader, with -demo only.
        .modifier(ComicReaderDebugLaunch())
        .modifier(BookReaderDebugLaunch())
        #endif
    }
}

#if DEBUG && os(iOS)
/// HUB_WIDTH=375 lays the app out in a window that wide at the screen's
/// leading edge, as Split View or Slide Over gives it, for screenshots of the
/// iPad's narrow windows without driving the system's multitasking. Its width
/// class follows Apple's table for the 12.9-inch iPad Pro: compact at a third
/// (375) and at two thirds upright (639), regular from half sideways (678).
struct DebugWidth: ViewModifier {
    private let width = ProcessInfo.processInfo.environment["HUB_WIDTH"].flatMap(Double.init) ?? 0

    func body(content: Content) -> some View {
        if width > 0 {
            content
                .frame(width: width)
                .environment(\.horizontalSizeClass, width < 660 ? .compact : .regular)
                .overlay(alignment: .trailing) { Rectangle().fill(.white.opacity(0.25)).frame(width: 1) }
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.black.ignoresSafeArea())
        } else {
            content
        }
    }
}
#endif

#if DEBUG && os(iOS)
/// HUB_ORIENT=landscape turns a phone's app sideways for screenshots
/// (`scripts/mac.sh turn landscape`). With Xcode 27 the iPhone simulator
/// comes back upright once the test that turned it ends, while an iPad stays
/// turned; an app may still turn its own window on a phone, so it asks to.
@MainActor
enum DebugOrientation {
    static func apply() {
        guard ProcessInfo.processInfo.environment["HUB_ORIENT"] == "landscape",
              UIDevice.current.userInterfaceIdiom == .phone,
              let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene
        else { return }
        // Interface right is the device turned left, as `DeviceTurn` turns it.
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: .landscapeRight)) { _ in }
    }
}
#endif

#if DEBUG && os(macOS)
/// The Mac app at a window size of a script's choosing: HUB_WINDOW=900x620
/// is the new window's size (`HubApp.windowSize`; the script turns off window
/// restoration so each run opens one), and HUB_SNAPSHOT=<seconds> draws it
/// into hub-window.png in the app's container that many seconds later, then
/// quits through the app's own path (`scripts/mac.sh mac-shot`). A screenshot
/// taken over SSH is refused without Screen Recording, a setting left alone here.
@MainActor
enum DebugWindow {
    nonisolated static var requestedSize: CGSize? {
        ProcessInfo.processInfo.environment["HUB_WINDOW"].flatMap(size(of:))
    }

    static func apply() async {
        let environment = ProcessInfo.processInfo.environment
        try? await Task.sleep(for: .milliseconds(400))
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.contentView != nil && $0.sheetParent == nil })
        else { return }
        guard let seconds = environment["HUB_SNAPSHOT"].flatMap(Double.init), seconds > 0 else { return }
        try? await Task.sleep(for: .seconds(seconds))
        if let picture = picture(of: window) {
            let file = URL(fileURLWithPath: NSHomeDirectory()).appendingPathComponent("hub-window.png")
            try? picture.representation(using: .png, properties: [:])?.write(to: file)
        }
        // An open sheet refuses the app's quit (ending it first did not help),
        // and the script then waited out its 90 seconds. Nothing plays in a
        // picture run (mac-shot opens the player only on the demo hub), so a
        // run with a sheet simply ends.
        if window.attachedSheet != nil { exit(0) }
        NSApp.terminate(nil)
    }

    /// The window as drawn, and a sheet open on it drawn over it where it
    /// sits: on the Mac a sheet is a window of its own, which drawing the
    /// window alone left out (the profiles and the request form, #12).
    private static func picture(of window: NSWindow) -> NSBitmapImageRep? {
        guard let view = window.contentView, let base = view.bitmapImageRepForCachingDisplay(in: view.bounds) else {
            return nil
        }
        view.cacheDisplay(in: view.bounds, to: base)
        guard let sheet = window.attachedSheet, let sheetView = sheet.contentView,
              let top = sheetView.bitmapImageRepForCachingDisplay(in: sheetView.bounds),
              let out = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: base.pixelsWide, pixelsHigh: base.pixelsHigh,
                                         bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                         colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
        else { return base }
        sheetView.cacheDisplay(in: sheetView.bounds, to: top)
        out.size = view.bounds.size
        // The window's content fills the window (the title bar is hidden), so
        // the sheet's place in window coordinates is its place in the picture.
        let origin = window.convertPoint(fromScreen: sheet.frame.origin)
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: out)
        base.draw(in: view.bounds)
        top.draw(in: NSRect(origin: origin, size: sheet.frame.size))
        NSGraphicsContext.restoreGraphicsState()
        return out
    }

    /// "900x620".
    nonisolated private static func size(of text: String) -> NSSize? {
        let parts = text.lowercased().split(separator: "x").compactMap { Double($0) }
        return parts.count == 2 ? NSSize(width: parts[0], height: parts[1]) : nil
    }
}
#endif

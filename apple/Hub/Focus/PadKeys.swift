import HubKit
import SwiftUI
#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// The keyboard's way into the focus (#46): the arrows step, Return and Space
/// press (Ⓐ), Escape is Ⓑ, each through `PadFocusCenter.route`, as the
/// controller's are (#46, A).
///
/// On the iPad and iPhone the arrows, Return and Space are UIKit key commands
/// with priority over the system's own keyboard behaviour: no SwiftUI shortcut
/// or key handler heard Return at all (the simulator, iOS 26; there XCUITest's
/// Return arrives only typed as a newline). Escape reaches no key command; a
/// focused view's key handler hears it, as the player's does (XCUITest's Escape
/// arrives nowhere). The commands step aside while the player or a reader is
/// open (they read their own keys), while a text field is being typed in, and
/// while an alert shows (its Return and Escape are its own). A sheet keeps
/// them: it is a page of its own (`padPage(modal:)`).
///
/// On the Mac a key goes to the window's first responder, so a monitor reads
/// it first and leaves it alone while a text field is being edited.
///
/// The pointer is watched too: a touch, a click or a scroll hides the ring
/// until the next press (`PadInput`).
struct PadKeys: View {
    #if os(macOS)
    @State private var monitors: [Any] = []
    #else
    /// Escape reaches no key command on the iPad or iPhone; a focused view's
    /// key handler does (the player's has since #33). This one holds the
    /// keyboard focus while the ring moves, never under the player or a
    /// reader, which read their own.
    @FocusState private var escape: Bool
    private var center: PadFocusCenter { PadFocusCenter.shared }
    #endif

    var body: some View {
        #if os(iOS)
        ZStack {
            Color.clear
                .frame(width: 1, height: 1)
                .focusable(!center.covered)
                .focusEffectDisabled()
                .focused($escape)
                .onKeyPress(.escape) {
                    center.route(.back)
                    return .handled
                }
                .accessibilityHidden(true)
            #if DEBUG
            PadFocusProbe()
            #endif
        }
        .background { WindowProbe() }
        .onAppear { escape = true }
        .onChange(of: center.focus) { _, _ in if !center.covered { escape = true } }
        #else
        ZStack {
            Color.clear
                .frame(width: 0, height: 0)
                .accessibilityHidden(true)
            #if DEBUG
            PadFocusProbe()
            #endif
        }
        .onAppear(perform: watch)
        .onDisappear {
            for monitor in monitors { NSEvent.removeMonitor(monitor) }
            monitors = []
        }
        #endif
    }

    #if os(macOS)
    private func watch() {
        guard monitors.isEmpty else { return }
        monitors.append(NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            guard !PadFocusCenter.shared.covered, let action = Self.action(event), !Self.typing(in: event.window) else {
                return event
            }
            PadFocusCenter.shared.route(action)
            return nil
        } as Any)
        monitors.append(NSEvent.addLocalMonitorForEvents(matching: [.leftMouseDown, .rightMouseDown, .scrollWheel]) { event in
            PadFocusCenter.shared.pointerUsed()
            return event
        } as Any)
    }

    /// The key's press, without ⌘, ⌥ or ⌃ (those are the menus').
    private static func action(_ event: NSEvent) -> PadAction? {
        guard event.modifierFlags.intersection([.command, .option, .control]).isEmpty else { return nil }
        switch event.keyCode {
        case 126: return .step(.up)
        case 125: return .step(.down)
        case 123: return .step(.left)
        case 124: return .step(.right)
        case 36, 76, 49: return .activate
        case 53: return .back
        default: return nil
        }
    }

    /// A text field or view has the keyboard, or a sheet or an alert is over the window.
    private static func typing(in window: NSWindow?) -> Bool {
        guard let window, window.attachedSheet == nil, window.isKeyWindow else { return true }
        return window.firstResponder is NSText
    }
    #endif
}

#if os(iOS)
/// The keys as UIKit key commands on the window's root controller, with
/// priority over the system's keyboard focus, while nothing else should have
/// them (see `PadKeys`).
@MainActor
final class SystemKeys {
    static let shared = SystemKeys()

    private weak var host: UIViewController?
    private var installed = false
    private var editing = 0
    private var observers: [NSObjectProtocol] = []
    private var checking: Timer?

    private lazy var commands: [UIKeyCommand] = [
        UIKeyCommand.inputUpArrow, UIKeyCommand.inputDownArrow, UIKeyCommand.inputLeftArrow,
        UIKeyCommand.inputRightArrow, "\r", " ", UIKeyCommand.inputEscape,
    ].map { input in
        let command = UIKeyCommand(input: input, modifierFlags: [], action: #selector(UIApplication.padKey(_:)))
        command.wantsPriorityOverSystemBehavior = true
        return command
    }

    static func action(_ input: String) -> PadAction? {
        switch input {
        case UIKeyCommand.inputUpArrow: .step(.up)
        case UIKeyCommand.inputDownArrow: .step(.down)
        case UIKeyCommand.inputLeftArrow: .step(.left)
        case UIKeyCommand.inputRightArrow: .step(.right)
        case "\r", " ": .activate
        case UIKeyCommand.inputEscape: .back
        default: nil
        }
    }

    func attach(to window: UIWindow) {
        guard let root = window.rootViewController, root !== host else { return }
        if let host, installed { commands.forEach(host.removeKeyCommand) }
        installed = false
        host = root
        if observers.isEmpty {
            let center = NotificationCenter.default
            for name in [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification] {
                observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in
                    MainActor.assumeIsolated { SystemKeys.shared.editing += 1; SystemKeys.shared.check() }
                })
            }
            for name in [UITextField.textDidEndEditingNotification, UITextView.textDidEndEditingNotification] {
                observers.append(center.addObserver(forName: name, object: nil, queue: .main) { _ in
                    MainActor.assumeIsolated {
                        SystemKeys.shared.editing = max(0, SystemKeys.shared.editing - 1)
                        SystemKeys.shared.check()
                    }
                })
            }
            // An alert, the player or a reader comes and goes without a word: looked at often.
            checking = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
                MainActor.assumeIsolated { SystemKeys.shared.check() }
            }
        }
        check()
    }

    /// The commands on while the keys are the focus's.
    func check() {
        guard let host else { return }
        var top: UIViewController? = host
        while let presented = top?.presentedViewController { top = presented }
        let wanted = editing == 0 && !PadFocusCenter.shared.covered && !(top is UIAlertController)
        guard wanted != installed else { return }
        installed = wanted
        if wanted { commands.forEach(host.addKeyCommand) } else { commands.forEach(host.removeKeyCommand) }
    }
}

extension UIApplication {
    /// A key command of `SystemKeys`: the end of the responder chain, where it lands.
    @objc func padKey(_ command: UIKeyCommand) {
        guard let input = command.input, let action = SystemKeys.action(input) else { return }
        MainActor.assumeIsolated { PadFocusCenter.shared.route(action) }
    }
}

/// Finds the window, for the key commands, and watches its touches.
private struct WindowProbe: UIViewRepresentable {
    func makeUIView(context: Context) -> Probe { Probe() }
    func updateUIView(_ view: Probe, context: Context) {}

    final class Probe: UIView {
        private let watcher = TouchWatcher()

        override func didMoveToWindow() {
            super.didMoveToWindow()
            guard let window else { return }
            #if DEBUG
            let off = ProcessInfo.processInfo.environment["HUB_PAD_OFF"] ?? ""
            if !off.contains("keys") { SystemKeys.shared.attach(to: window) }
            guard !off.contains("watch") else { return }
            #else
            SystemKeys.shared.attach(to: window)
            #endif
            guard watcher.view !== window else { return }
            watcher.view?.removeGestureRecognizer(watcher)
            window.addGestureRecognizer(watcher)
        }
    }

    /// Hears each touch as UIKit offers it and declines it, so it never takes
    /// part in recognising anything: a recogniser that took the touches and
    /// failed still upset a menu's (a picker's menu chose the wrong year, and a
    /// context menu's choice presented nothing after it; the simulator).
    final class TouchWatcher: UIGestureRecognizer, UIGestureRecognizerDelegate {
        init() {
            super.init(target: nil, action: nil)
            cancelsTouchesInView = false
            delaysTouchesBegan = false
            delaysTouchesEnded = false
            delegate = self
        }

        func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
            PadFocusCenter.shared.pointerUsed()
            return false
        }
    }
}
#endif

#if DEBUG
/// For the UI tests: where the focus is ("books-home · hero-resume", "none"),
/// and whether the ring shows, as a line they can read.
private struct PadFocusProbe: View {
    private var center: PadFocusCenter { PadFocusCenter.shared }

    var body: some View {
        Text(center.probeLine)
            .font(.system(size: 1))
            .opacity(0.02)
            .allowsHitTesting(false)
            .accessibilityIdentifier("pad-focus")
    }
}
#endif

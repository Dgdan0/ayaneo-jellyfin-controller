#if os(iOS)
import HubKit
import UIKit

/// Readium's navigator inside a controller that takes the keyboard from it
/// (#25). Readium makes its navigator the first responder once the page is
/// on screen (`InputObservableViewController.viewDidAppear`) and reads every
/// press itself from then on, so the reader's own key handlers stopped
/// hearing them half a second after a book opened, and Readium passes no
/// Delete on: its press reading has no case for that key (the simulator,
/// 2026-10-07: the arrows came through Readium, Delete not at all). Key
/// commands on this controller never fired either: the navigator handles
/// the presses before UIKit looks for a command.
///
/// So this controller becomes the first responder once the navigator has
/// appeared, and every time the app comes back, and reads the presses
/// itself: each key a book uses becomes a `ReaderKey`, once, and any other
/// press goes on as before. A held arrow repeats, as the screen's keys do. A
/// page that takes the keyboard back (a tap that selects text) sends its keys
/// through Readium's script, which the reader hears too (`BookNavigator`).
final class BookKeysController: UIViewController {
    private let content: UIViewController
    private let onKey: (ReaderKey) -> Void
    /// The presses read here, by key, so their ends are not passed on either.
    private var held: Set<UIKeyboardHIDUsage> = []
    private var repeating: Task<Void, Never>?
    private var activeObserver: NSObjectProtocol?

    /// A held arrow repeats after this long, this often.
    private static let repeatDelay: Duration = .milliseconds(420)
    private static let repeatEvery: Duration = .milliseconds(110)

    init(content: UIViewController, onKey: @escaping (ReaderKey) -> Void) {
        self.content = content
        self.onKey = onKey
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        addChild(content)
        content.view.frame = view.bounds
        content.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(content.view)
        content.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        takeKeys()
        // Back from the background, the navigator claims the keyboard again.
        if activeObserver == nil {
            activeObserver = NotificationCenter.default.addObserver(
                forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
            ) { [weak self] _ in
                MainActor.assumeIsolated { self?.takeKeys() }
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopRepeating()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if let activeObserver { NotificationCenter.default.removeObserver(activeObserver) }
        activeObserver = nil
    }

    override var canBecomeFirstResponder: Bool { true }

    /// After the navigator's own claim, which its appearance makes.
    private func takeKeys() {
        Task { @MainActor [weak self] in
            await Task.yield()
            guard let self, self.viewIfLoaded?.window != nil, !self.isFirstResponder else { return }
            self.becomeFirstResponder()
            #if DEBUG
            NSLog("book: the page's container holds the keys")
            #endif
        }
    }

    // MARK: Delete

    /// Delete, the menu's key, comes as the system's delete action rather than
    /// as a press: UIKit turns the key into `delete:` for the first responder
    /// in the chain that takes it, and the press is never sent.
    override func delete(_ sender: Any?) {
        #if DEBUG
        NSLog("book: key delete from the page's container, as the delete action")
        #endif
        onKey(.delete)
    }

    override func canPerformAction(_ action: Selector, withSender sender: Any?) -> Bool {
        action == #selector(delete(_:)) ? true : super.canPerformAction(action, withSender: sender)
    }

    // MARK: Presses

    override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        var others = Set<UIPress>()
        for press in presses {
            guard let key = press.key, let readerKey = Self.readerKey(key) else {
                others.insert(press)
                continue
            }
            held.insert(key.keyCode)
            #if DEBUG
            NSLog("book: key %@ from the page's container", String(describing: readerKey))
            #endif
            onKey(readerKey)
            if Self.repeats(readerKey) { startRepeating(readerKey) }
        }
        if !others.isEmpty { super.pressesBegan(others, with: event) }
    }

    override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let others = release(presses)
        if !others.isEmpty { super.pressesEnded(others, with: event) }
    }

    override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let others = release(presses)
        if !others.isEmpty { super.pressesCancelled(others, with: event) }
    }

    override func pressesChanged(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
        let others = presses.filter { press in press.key.map { !held.contains($0.keyCode) } ?? true }
        if !others.isEmpty { super.pressesChanged(others, with: event) }
    }

    /// The presses that ended, less the ones read here.
    private func release(_ presses: Set<UIPress>) -> Set<UIPress> {
        var others = Set<UIPress>()
        for press in presses {
            if let code = press.key?.keyCode, held.remove(code) != nil {
                stopRepeating()
            } else {
                others.insert(press)
            }
        }
        return others
    }

    private func startRepeating(_ key: ReaderKey) {
        repeating?.cancel()
        repeating = Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.repeatDelay)
            while !Task.isCancelled {
                self?.onKey(key)
                try? await Task.sleep(for: Self.repeatEvery)
            }
        }
    }

    private func stopRepeating() {
        repeating?.cancel()
        repeating = nil
    }

    // MARK: Keys

    /// A press as a reader's key (`ReaderKeyboard` reads it on); nil for any
    /// other key, and for one held with Command, Control or Option, which are
    /// the system's and the app's shortcuts.
    static func readerKey(_ key: UIKey) -> ReaderKey? {
        if !key.modifierFlags.isDisjoint(with: [.command, .control, .alternate]) { return nil }
        switch key.keyCode {
        case .keyboardRightArrow: return .right
        case .keyboardLeftArrow: return .left
        case .keyboardUpArrow: return .up
        case .keyboardDownArrow: return .down
        case .keyboardSpacebar: return .space
        case .keyboardReturnOrEnter, .keypadEnter, .keyboardReturn: return .returnKey
        case .keyboardDeleteOrBackspace, .keyboardDeleteForward: return .delete
        case .keyboardEscape: return .escape
        case .keyboardPageUp: return .pageUp
        case .keyboardPageDown: return .pageDown
        case .keyboardHyphen, .keypadHyphen: return .minus
        case .keyboardEqualSign, .keypadPlus: return .plus
        default: return nil
        }
    }

    static func repeats(_ key: ReaderKey) -> Bool {
        switch key {
        case .left, .right, .up, .down: true
        default: false
        }
    }
}
#endif

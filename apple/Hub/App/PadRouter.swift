import GameController
import HubKit
import Observation

/// A game controller, for the whole app (APPLE_PLAN.md, "Input"): its
/// buttons, D-pad and sticks as the Pocket's `PadAction`s. Ⓐ Ⓑ Ⓧ Ⓨ, the
/// shoulders, the triggers, Menu (the Pocket's Start), Options (its Select)
/// and the sticks pressed in. The D-pad and the left stick repeat while held,
/// as the Pocket's do; the right stick pans for as long as it is pushed.
///
/// One per process (`shared`), since a controller's buttons take one handler
/// each: the screen on top claims the presses (`PadClaim`) and gives them
/// back when it goes. (HubKit's `PadInput`, the #46 engine's, is another
/// thing: whether the ring shows.) The shell claims them first and keeps its claim, then
/// the player and the readers each claim theirs while open.
@MainActor
@Observable
final class PadRouter {
    static let shared = PadRouter()

    /// A controller is connected: the readers show their cursor and key hints.
    private(set) var connected = false

    /// The claims, the last on top: only it is sent a press.
    @ObservationIgnored private var claims: [(id: UUID, send: (PadAction) -> Void)] = []
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []
    @ObservationIgnored private var started = false
    @ObservationIgnored private var repeating: Task<Void, Never>?
    @ObservationIgnored private var held: PadDirection?
    @ObservationIgnored private var stick = (x: Float(0), y: Float(0))
    @ObservationIgnored private var panning: Task<Void, Never>?

    /// A stick at rest reads under this: the Pocket's measured 0.12.
    static let deadZone: Float = 0.12
    /// The left stick pushed past this is a D-pad press.
    static let stepAt: Float = 0.5

    /// Presses go to `send` until the claim is let go; a claim made later
    /// takes them meanwhile.
    func claim(_ send: @escaping (PadAction) -> Void) -> UUID {
        start()
        let id = UUID()
        claims.append((id, send))
        stopRepeating()
        return id
    }

    func release(_ id: UUID) {
        claims.removeAll { $0.id == id }
        stopRepeating()
    }

    /// A press, to the claim on top.
    func dispatch(_ action: PadAction) {
        claims.last?.send(action)
    }

    /// The controllers are listened to from the first claim on, for good.
    private func start() {
        guard !started else { return }
        started = true
        attachAll()
        let center = NotificationCenter.default
        // A controller connected: every one connected is given the handlers
        // again (setting them twice is harmless), so nothing crosses actors.
        observers.append(center.addObserver(forName: .GCControllerDidConnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.attachAll() }
        })
        observers.append(center.addObserver(forName: .GCControllerDidDisconnect, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.refresh() }
        })
        refresh()
        #if DEBUG
        playScript()
        #endif
    }

    /// A held direction or a pushed stick belongs to the screen it began on.
    private func stopRepeating() {
        repeating?.cancel()
        repeating = nil
        held = nil
        panning?.cancel()
        panning = nil
        stick = (0, 0)
    }

    #if DEBUG
    /// The UI tests have no controller: HUB_PAD="R1,B" presses those, one a
    /// second, HUB_PAD_DELAY seconds after launch (4 by default).
    private func playScript() {
        let environment = ProcessInfo.processInfo.environment
        let actions = PadScript.actions(environment["HUB_PAD"] ?? "")
        guard !actions.isEmpty else { return }
        let delay = Double(environment["HUB_PAD_DELAY"] ?? "") ?? 4
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            for action in actions {
                self?.dispatch(action)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
    #endif

    private func attachAll() {
        for controller in GCController.controllers() { attach(controller) }
    }

    private func refresh() {
        connected = GCController.controllers().contains { $0.extendedGamepad != nil }
    }

    private func attach(_ controller: GCController) {
        guard let pad = controller.extendedGamepad else { return }
        controller.handlerQueue = .main
        press(pad.buttonA, .activate)
        press(pad.buttonB, .back)
        press(pad.buttonX, .primary)
        press(pad.buttonY, .secondary)
        press(pad.leftShoulder, .section(-1))
        press(pad.rightShoulder, .section(1))
        press(pad.leftTrigger, .page(.up))
        press(pad.rightTrigger, .page(.down))
        press(pad.buttonMenu, .menu)
        if let options = pad.buttonOptions { press(options, .refresh) }
        if let left = pad.leftThumbstickButton { click(left, .left) }
        if let right = pad.rightThumbstickButton { click(right, .right) }
        direction(pad.dpad.up, .up)
        direction(pad.dpad.down, .down)
        direction(pad.dpad.left, .left)
        direction(pad.dpad.right, .right)
        pad.leftThumbstick.valueChangedHandler = { [weak self] _, x, y in
            MainActor.assumeIsolated { self?.leftStick(x: x, y: y) }
        }
        pad.rightThumbstick.valueChangedHandler = { [weak self] _, x, y in
            MainActor.assumeIsolated { self?.rightStick(x: x, y: y) }
        }
        refresh()
    }

    private func press(_ button: GCControllerButtonInput, _ action: PadAction) {
        button.pressedChangedHandler = { [weak self] _, _, pressed in
            MainActor.assumeIsolated {
                if pressed { self?.dispatch(action) }
            }
        }
    }

    /// A stick pressed in: down and up both, for L3's magnifier while held.
    private func click(_ button: GCControllerButtonInput, _ stick: PadStick) {
        button.pressedChangedHandler = { [weak self] _, _, pressed in
            MainActor.assumeIsolated { self?.dispatch(.click(stick, down: pressed)) }
        }
    }

    private func direction(_ button: GCControllerButtonInput, _ direction: PadDirection) {
        button.pressedChangedHandler = { [weak self] _, _, pressed in
            MainActor.assumeIsolated { self?.hold(pressed ? direction : nil, releasing: direction) }
        }
    }

    private func leftStick(x: Float, y: Float) {
        let direction: PadDirection? = if max(abs(x), abs(y)) < Self.stepAt {
            nil
        } else if abs(x) >= abs(y) {
            x > 0 ? .right : .left
        } else {
            y > 0 ? .up : .down
        }
        if direction != held { hold(direction, releasing: held) }
    }

    /// A direction held repeats, after a pause, as the Pocket's D-pad does
    /// (its `AnalogRepeater`); let go, it stops.
    private func hold(_ direction: PadDirection?, releasing: PadDirection?) {
        if direction == nil && held != releasing { return }
        repeating?.cancel()
        held = direction
        guard let direction else { return }
        dispatch(.step(direction))
        repeating = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(380))
            while !Task.isCancelled {
                self?.dispatch(.step(direction))
                try? await Task.sleep(for: .milliseconds(120))
            }
        }
    }

    /// The right stick pans while pushed: how far, sixty times a second, in stick-seconds.
    private func rightStick(x: Float, y: Float) {
        stick = (abs(x) < Self.deadZone ? 0 : x, abs(y) < Self.deadZone ? 0 : y)
        guard stick.x != 0 || stick.y != 0 else {
            panning?.cancel()
            panning = nil
            return
        }
        guard panning == nil else { return }
        panning = Task { [weak self] in
            let tick = 1.0 / 60
            while !Task.isCancelled, let self, self.stick.x != 0 || self.stick.y != 0 {
                // A stick's up is the page's up: the view moves towards smaller y.
                self.dispatch(.pan(dx: Double(self.stick.x) * tick, dy: -Double(self.stick.y) * tick))
                try? await Task.sleep(for: .milliseconds(16))
            }
            self?.panning = nil
        }
    }
}

/// A screen's hold on the controller while it is open: `start` claims the
/// presses (sending them to `send`), `stop` gives them back.
@MainActor
@Observable
final class PadClaim {
    @ObservationIgnored private var id: UUID?

    /// A controller is connected.
    var connected: Bool { PadRouter.shared.connected }

    func start(_ send: @escaping (PadAction) -> Void) {
        stop()
        id = PadRouter.shared.claim(send)
    }

    func stop() {
        if let id { PadRouter.shared.release(id) }
        id = nil
    }
}

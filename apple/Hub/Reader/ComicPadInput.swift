import GameController
import HubKit
import Observation

/// A game controller in a reader (#25; APPLE_PLAN.md, "Input"): its buttons,
/// D-pad and sticks as the Pocket's `PadAction`s, for `ReaderPadMap`. Ⓐ Ⓑ Ⓧ
/// Ⓨ, the shoulders, the triggers, Menu (the Pocket's Start), Options (its
/// Select) and the sticks pressed in. The D-pad and the left stick repeat
/// while held, as the Pocket's do; the right stick pans for as long as it is
/// pushed. Only while a reader is open: nothing else in the app reads a pad.
@MainActor
@Observable
final class ComicPadInput {
    /// A controller is connected: the reader shows its cursor and key hints.
    private(set) var connected = false

    @ObservationIgnored private var send: (PadAction) -> Void = { _ in }
    @ObservationIgnored private var observers: [any NSObjectProtocol] = []
    @ObservationIgnored private var repeating: Task<Void, Never>?
    @ObservationIgnored private var held: PadDirection?
    @ObservationIgnored private var stick = (x: Float(0), y: Float(0))
    @ObservationIgnored private var panning: Task<Void, Never>?

    /// A stick at rest reads under this: the Pocket's measured 0.12.
    static let deadZone: Float = 0.12
    /// The left stick pushed past this is a D-pad press.
    static let stepAt: Float = 0.5

    func start(_ send: @escaping (PadAction) -> Void) {
        self.send = send
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
    }

    func stop() {
        for controller in GCController.controllers() { detach(controller) }
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        repeating?.cancel()
        panning?.cancel()
        held = nil
    }

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

    private func detach(_ controller: GCController) {
        guard let pad = controller.extendedGamepad else { return }
        for button in [pad.buttonA, pad.buttonB, pad.buttonX, pad.buttonY, pad.leftShoulder, pad.rightShoulder,
                       pad.leftTrigger, pad.rightTrigger, pad.buttonMenu, pad.dpad.up, pad.dpad.down, pad.dpad.left,
                       pad.dpad.right] {
            button.pressedChangedHandler = nil
        }
        pad.buttonOptions?.pressedChangedHandler = nil
        pad.leftThumbstickButton?.pressedChangedHandler = nil
        pad.rightThumbstickButton?.pressedChangedHandler = nil
        pad.leftThumbstick.valueChangedHandler = nil
        pad.rightThumbstick.valueChangedHandler = nil
    }

    private func press(_ button: GCControllerButtonInput, _ action: PadAction) {
        button.pressedChangedHandler = { [weak self] _, _, pressed in
            MainActor.assumeIsolated {
                if pressed { self?.send(action) }
            }
        }
    }

    /// A stick pressed in: down and up both, for L3's magnifier while held.
    private func click(_ button: GCControllerButtonInput, _ stick: PadStick) {
        button.pressedChangedHandler = { [weak self] _, _, pressed in
            MainActor.assumeIsolated { self?.send(.click(stick, down: pressed)) }
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
        send(.step(direction))
        repeating = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(380))
            while !Task.isCancelled {
                self?.send(.step(direction))
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
                self.send(.pan(dx: Double(self.stick.x) * tick, dy: -Double(self.stick.y) * tick))
                try? await Task.sleep(for: .milliseconds(16))
            }
            self?.panning = nil
        }
    }
}

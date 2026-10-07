import Foundation

/// Every control a game controller can report (#38; Android's `PadTestScreen`,
/// what the Pocket's pad reports, for any controller an iPad, iPhone or Mac can
/// use). The names are the ones the readers' key tables use.
public enum ControllerControl: String, CaseIterable, Sendable {
    case a, b, x, y, l1, r1, l2, r2, l3, r3, menu, options, home, up, down, left, right

    public var label: String {
        switch self {
        case .a: "A"
        case .b: "B"
        case .x: "X"
        case .y: "Y"
        case .l1: "L1"
        case .r1: "R1"
        case .l2: "L2"
        case .r2: "R2"
        case .l3: "L3"
        case .r3: "R3"
        case .menu: "Menu"
        case .options: "Options"
        case .home: "Home"
        case .up: "Up"
        case .down: "Down"
        case .left: "Left"
        case .right: "Right"
        }
    }

    /// The two triggers report how far they are pulled, not only whether.
    public var isTrigger: Bool { self == .l2 || self == .r2 }
    public var isDpad: Bool { [.up, .down, .left, .right].contains(self) }
}

public struct StickPosition: Equatable, Sendable {
    public var x: Float
    public var y: Float

    public init(x: Float = 0, y: Float = 0) {
        self.x = x
        self.y = y
    }

    /// How far from the middle, 0 to about 1.4 at a corner.
    public var deflection: Float { (x * x + y * y).squareRoot() }
}

/// What a controller says right now: one reading of all of it.
public struct ControllerSample: Equatable, Sendable {
    public var name: String
    /// "Extended gamepad", "Micro gamepad".
    public var category: String
    /// 0 to 1 for each control it has; one it has not is absent.
    public var values: [ControllerControl: Float]
    public var left: StickPosition
    public var right: StickPosition
    /// 0 to 1, when it says.
    public var battery: Float?

    public init(name: String, category: String = "", values: [ControllerControl: Float] = [:], left: StickPosition = StickPosition(),
                right: StickPosition = StickPosition(), battery: Float? = nil) {
        self.name = name
        self.category = category
        self.values = values
        self.left = left
        self.right = right
        self.battery = battery
    }

    /// The controls it reports at all.
    public var controls: [ControllerControl] { ControllerControl.allCases.filter { values[$0] != nil } }

    public func isDown(_ control: ControllerControl) -> Bool { (values[control] ?? 0) >= ControllerProbe.pressedAt }
}

/// What the page has seen a controller do since it opened: which controls are
/// down, how often each was pressed, the last presses in order, how far the
/// triggers were pulled and how far the sticks were pushed. Fed one reading at
/// a time, so it is a plain value to test.
public struct ControllerProbe: Equatable, Sendable {
    /// A control reading this much is held down. Half way: a trigger pulled
    /// less than that is not a press.
    public static let pressedAt: Float = 0.5
    /// A stick at rest reads under this: the Pocket's measured dead zone.
    public static let deadZone: Float = 0.12
    public static let logLength = 8

    public private(set) var down: Set<ControllerControl> = []
    public private(set) var presses: [ControllerControl: Int] = [:]
    /// Newest first.
    public private(set) var log: [ControllerControl] = []
    public private(set) var triggerPeak: [ControllerControl: Float] = [:]
    public private(set) var leftPeak: Float = 0
    public private(set) var rightPeak: Float = 0

    public init() {}

    public mutating func observe(_ sample: ControllerSample) {
        var now = Set<ControllerControl>()
        for (control, value) in sample.values where value >= Self.pressedAt { now.insert(control) }
        for control in ControllerControl.allCases where now.contains(control) && !down.contains(control) {
            presses[control, default: 0] += 1
            log.insert(control, at: 0)
        }
        if log.count > Self.logLength { log.removeLast(log.count - Self.logLength) }
        down = now
        for control in [ControllerControl.l2, .r2] {
            if let value = sample.values[control] { triggerPeak[control] = max(triggerPeak[control] ?? 0, value) }
        }
        if sample.left.deflection >= Self.deadZone { leftPeak = max(leftPeak, sample.left.deflection) }
        if sample.right.deflection >= Self.deadZone { rightPeak = max(rightPeak, sample.right.deflection) }
    }

    /// Forgets everything seen.
    public mutating func reset() { self = ControllerProbe() }

    /// How many of the controller's controls have been pressed at least once.
    public func tried(_ sample: ControllerSample) -> Int {
        sample.controls.filter { (presses[$0] ?? 0) > 0 }.count
    }

    // MARK: Words

    public static func header(count: Int) -> String {
        switch count {
        case 0: "No controller connected"
        case 1: "1 controller connected"
        default: "\(count) controllers connected"
        }
    }

    public static let emptyHint = "Connect a game controller over Bluetooth, or plug one in, then press a button."

    /// "Xbox Wireless Controller · Extended gamepad".
    public static func title(_ sample: ControllerSample) -> String {
        [sample.name.isEmpty ? "Game controller" : sample.name, sample.category].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    public static func batteryLine(_ level: Float?) -> String {
        level.map { "Battery \(Int(($0 * 100).rounded()))%" } ?? ""
    }

    /// "x +0.43  y −0.20", or "Centred" inside the dead zone.
    public static func stickLine(_ stick: StickPosition) -> String {
        if stick.deflection < deadZone { return "Centred" }
        func signed(_ value: Float) -> String {
            let text = String(format: "%.2f", locale: Locale(identifier: "en_US_POSIX"), abs(value))
            return (value < 0 ? "−" : "+") + text
        }
        return "x \(signed(stick.x))  y \(signed(stick.y))"
    }

    public static func triggerLine(_ value: Float) -> String { "\(Int((value * 100).rounded()))%" }

    /// How the last presses read: "Last: A, R2, A".
    public var lastLine: String {
        log.isEmpty ? "Nothing pressed yet" : "Last: " + log.map(\.label).joined(separator: ", ")
    }

    /// "5 of 17 controls tried": what is left to check on a new controller.
    public func progressLine(_ sample: ControllerSample) -> String {
        "\(tried(sample)) of \(sample.controls.count) controls tried"
    }
}

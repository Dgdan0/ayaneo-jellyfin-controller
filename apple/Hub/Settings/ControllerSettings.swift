import GameController
import HubKit
import Observation
import SwiftUI

/// The game controllers connected and what each says, read thirty times a
/// second while the page shows (#38). Polled rather than handled: the readers
/// set their own handlers on the same controls, and a page that took them
/// over would leave a reader deaf. The demo hub shows a controller of its own,
/// since a simulator has none.
@MainActor
@Observable
final class ControllerWatcher {
    struct Entry: Identifiable {
        let id: String
        var sample: ControllerSample
        var probe: ControllerProbe
    }

    private(set) var entries: [Entry] = []

    /// Reads until the page goes: its task is cancelled.
    func run(demo: Bool) async {
        while !Task.isCancelled {
            let samples = demo ? [Self.demoSample] : GCController.controllers().compactMap(Self.sample)
            update(samples)
            try? await Task.sleep(for: .milliseconds(33))
        }
    }

    func reset() {
        entries = entries.map { Entry(id: $0.id, sample: $0.sample, probe: ControllerProbe()) }
    }

    private func update(_ samples: [ControllerSample]) {
        var next: [Entry] = []
        for (index, sample) in samples.enumerated() {
            let id = "\(index):\(sample.name)"
            var probe = entries.first { $0.id == id }?.probe ?? ControllerProbe()
            probe.observe(sample)
            next.append(Entry(id: id, sample: sample, probe: probe))
        }
        // Only a change redraws the page: most readings are the last one again.
        let same = next.count == entries.count && zip(next, entries).allSatisfy { $0.id == $1.id && $0.sample == $1.sample && $0.probe == $1.probe }
        if !same { entries = next }
    }

    /// One controller as a value, from its extended or micro profile.
    private static func sample(_ controller: GCController) -> ControllerSample? {
        let name = controller.vendorName ?? ""
        let category = controller.productCategory
        let battery = controller.battery.map { Float($0.batteryLevel) }
        if let pad = controller.extendedGamepad {
            var values: [ControllerControl: Float] = [
                .a: pad.buttonA.value, .b: pad.buttonB.value, .x: pad.buttonX.value, .y: pad.buttonY.value,
                .l1: pad.leftShoulder.value, .r1: pad.rightShoulder.value, .l2: pad.leftTrigger.value, .r2: pad.rightTrigger.value,
                .menu: pad.buttonMenu.value, .up: pad.dpad.up.value, .down: pad.dpad.down.value,
                .left: pad.dpad.left.value, .right: pad.dpad.right.value,
            ]
            if let button = pad.leftThumbstickButton { values[.l3] = button.value }
            if let button = pad.rightThumbstickButton { values[.r3] = button.value }
            if let button = pad.buttonOptions { values[.options] = button.value }
            if let button = pad.buttonHome { values[.home] = button.value }
            return ControllerSample(name: name, category: category, values: values,
                                    left: StickPosition(x: pad.leftThumbstick.xAxis.value, y: pad.leftThumbstick.yAxis.value),
                                    right: StickPosition(x: pad.rightThumbstick.xAxis.value, y: pad.rightThumbstick.yAxis.value),
                                    battery: battery)
        }
        if let pad = controller.microGamepad {
            return ControllerSample(name: name, category: category, values: [
                .a: pad.buttonA.value, .x: pad.buttonX.value, .menu: pad.buttonMenu.value,
                .up: pad.dpad.up.value, .down: pad.dpad.down.value, .left: pad.dpad.left.value, .right: pad.dpad.right.value,
            ], left: StickPosition(x: pad.dpad.xAxis.value, y: pad.dpad.yAxis.value), battery: battery)
        }
        return nil
    }

    /// What the demo hub's controller says: A held, the right trigger part way, the left stick pushed.
    private static let demoSample = ControllerSample(
        name: "Demo Controller", category: "Extended gamepad",
        values: [.a: 1, .b: 0, .x: 0, .y: 0, .l1: 0, .r1: 0, .l2: 0, .r2: 0.62, .l3: 0, .r3: 0, .menu: 0, .options: 0,
                 .up: 0, .down: 0, .left: 0, .right: 0],
        left: StickPosition(x: 0.43, y: -0.2), right: StickPosition(), battery: 0.8)
}

/// Settings › Controller test (#38; Android's `PadTestScreen`): every connected
/// game controller's buttons, triggers and sticks live, what was pressed last,
/// and how many of its controls have been tried.
struct ControllerSettings: View {
    @Environment(AppModel.self) private var model
    @Environment(\.isEnabled) private var isEnabled
    @State private var watcher = ControllerWatcher()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                Text(ControllerProbe.header(count: watcher.entries.count))
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                    .accessibilityIdentifier("controller-header")
                Spacer(minLength: 8)
                if !watcher.entries.isEmpty {
                    Button("Reset") { watcher.reset() }
                        .buttonStyle(GlassControlStyle())
                        .accessibilityIdentifier("controller-reset")
                }
            }
            .padding(.horizontal, 4)
            if watcher.entries.isEmpty {
                emptyCard
            }
            ForEach(watcher.entries) { entry in
                ControllerCard(entry: entry)
            }
        }
        .task(id: isEnabled) {
            guard isEnabled else { return }
            await watcher.run(demo: model.isDemo)
        }
    }

    private var emptyCard: some View {
        HStack(spacing: 14) {
            Image(systemName: "gamecontroller")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 56, height: 56)
                .glassPanel(Circle())
            Text(ControllerProbe.emptyHint)
                .font(HubType.body(14.5, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.72))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("controller-empty")
    }
}

/// One controller: its name and battery, every control as a chip that lights
/// while it is held, the sticks drawn, and what was pressed last.
struct ControllerCard: View {
    let entry: ControllerWatcher.Entry
    @Environment(\.glassAccent) private var accent

    private var sample: ControllerSample { entry.sample }
    private var probe: ControllerProbe { entry.probe }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(ControllerProbe.title(sample))
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Text([ControllerProbe.batteryLine(sample.battery), probe.progressLine(sample)].filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.62))
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 76, maximum: 120), spacing: 8)], alignment: .leading, spacing: 8) {
                ForEach(sample.controls, id: \.self) { control in chip(control) }
            }
            HStack(alignment: .top, spacing: 18) {
                stick("Left stick", sample.left, peak: probe.leftPeak)
                stick("Right stick", sample.right, peak: probe.rightPeak)
            }
            Text(probe.lastLine)
                .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.8))
                .accessibilityIdentifier("controller-last")
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .accessibilityElement(children: .contain)
    }

    /// A control: lit white while down, a trigger also shows how far.
    private func chip(_ control: ControllerControl) -> some View {
        let down = sample.isDown(control)
        let value = sample.values[control] ?? 0
        let count = probe.presses[control] ?? 0
        return VStack(spacing: 3) {
            Text(control.label)
                .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
            if control.isTrigger {
                Text(ControllerProbe.triggerLine(value))
                    .font(HubType.body(11.5, relativeTo: .caption2))
                    .monospacedDigit()
            }
        }
        .foregroundStyle(down ? Color.glassInk : .white)
        .frame(maxWidth: .infinity, minHeight: 40)
        .background {
            if down {
                Capsule().fill(.white)
            } else {
                Capsule().fill(.clear).glassPanel(Capsule())
            }
        }
        .overlay(alignment: .bottom) {
            if control.isTrigger && !down {
                Capsule().fill(accent.tint).frame(width: max(0, 56 * CGFloat(value)), height: 3).offset(y: -4)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 10)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(control.label), \(down ? "down" : "up")" + (count > 0 ? ", pressed \(count)" : ""))
        .accessibilityValue(control.isTrigger ? ControllerProbe.triggerLine(value) : "")
        .accessibilityIdentifier("control-\(control.rawValue)")
    }

    private func stick(_ title: String, _ position: StickPosition, peak: Float) -> some View {
        let radius: CGFloat = 46
        let centred = position.deflection < ControllerProbe.deadZone
        return VStack(alignment: .leading, spacing: 6) {
            GlassLabel(text: title)
            ZStack {
                Circle().strokeBorder(.white.opacity(0.3), lineWidth: 1.5)
                // The dead zone: a stick inside it counts as at rest.
                Circle().strokeBorder(.white.opacity(0.18), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
                    .frame(width: radius * 2 * CGFloat(ControllerProbe.deadZone), height: radius * 2 * CGFloat(ControllerProbe.deadZone))
                Circle().fill(centred ? Color.white.opacity(0.5) : accent.tint)
                    .frame(width: 14, height: 14)
                    .offset(x: CGFloat(position.x) * (radius - 7), y: -CGFloat(position.y) * (radius - 7))
            }
            .frame(width: radius * 2, height: radius * 2)
            .accessibilityHidden(true)
            Text(ControllerProbe.stickLine(position))
                .font(HubType.body(12.5, relativeTo: .caption))
                .monospacedDigit()
                .foregroundStyle(.white.opacity(0.7))
            if peak > 0 {
                Text("Furthest \(Int((min(peak, 1) * 100).rounded()))%")
                    .font(HubType.body(12, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.5))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title), \(ControllerProbe.stickLine(position))")
        .accessibilityIdentifier("stick-\(title == "Left stick" ? "left" : "right")")
    }
}

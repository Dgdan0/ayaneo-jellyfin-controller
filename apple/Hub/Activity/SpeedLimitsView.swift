import HubKit
import SwiftUI

/// qBittorrent's two sets of limits by the names Activity uses (#29;
/// Android's `BandwidthScreen`): Normal speed and Quiet, its "alternative"
/// limits. The picker at the top chooses which is in use; each card shows
/// its download and upload caps and edits them. Every change is read back
/// from qBittorrent before it is shown as saved.
struct SpeedLimitsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.glassAccent) private var accent

    @State private var state: BandwidthState?
    @State private var status = StatusMessage("")
    @State private var saving = false
    @State private var editing: Editing?
    @State private var loads = 0

    /// One set's caps being edited, as typed.
    struct Editing: Identifiable {
        /// "normal" or "alternative".
        let limits: String
        let name: String
        var download: String
        var upload: String
        var error = ""
        var id: String { limits }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                PageHeading(title: "Speed limits") {
                    StatusLine(message: status.text.isEmpty
                               ? StatusMessage(state == nil ? "Asking qBittorrent…" : "qBittorrent · these limits apply to every transfer")
                               : status) { loads += 1 }
                }
                if let state {
                    if state.canControl && state.modeSwitchSupported {
                        DashboardCard(title: "In use", trailing: { EmptyView() }) {
                            GlassCapsulePicker(items: [.init(id: "normal", title: "Normal speed"), .init(id: "alternative", title: "Quiet")],
                                               selection: state.mode, pad: "mode") { chosen in
                                guard chosen != state.mode else { return }
                                Task { await save(BandwidthChange(mode: chosen)) }
                            }
                            .disabled(saving)
                            .padding(.horizontal, 8)
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("limits-mode")
                            Text("Quiet uses qBittorrent's alternative limits: slower downloads that leave room for everything else.")
                                .font(HubType.body(13, relativeTo: .footnote))
                                .foregroundStyle(.white.opacity(0.66))
                                .padding(.horizontal, 8)
                                .padding(.top, 4)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    let sets = Group {
                        limits("normal", name: "Normal speed", down: state.downloadBps, up: state.uploadBps, inUse: !state.quiet)
                        limits("alternative", name: "Quiet", down: state.alternativeDownloadBps, up: state.alternativeUploadBps,
                               inUse: state.quiet)
                    }
                    if metrics.compact {
                        VStack(spacing: 12) { sets }
                    } else {
                        HStack(alignment: .top, spacing: 12) { sets }
                    }
                    ForEach(BandwidthPresentation.notes(state), id: \.self) { note in
                        Text(note)
                            .font(HubType.body(13, relativeTo: .footnote))
                            .foregroundStyle(.white.opacity(0.66))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            // Held to a readable width at the page's own margin, as the other pages' rows are.
            .frame(maxWidth: 900, alignment: .leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 28)
        }
        .padPage("speed-limits")
        .refreshable { loads += 1 }
        .task(id: loads) { await load() }
        .sheet(item: $editing) { _ in
            LimitsEditor(editing: $editing, inUse: BandwidthPresentation.name(quiet: state?.quiet == true)) { change in
                Task { await save(change) }
            }
        }
    }

    /// One set of limits: its name, In use when it is, the two caps, and Edit limits.
    private func limits(_ set: String, name: String, down: Int64, up: Int64, inUse: Bool) -> some View {
        DashboardCard(title: name, trailing: {
            if inUse {
                Text("In use")
                    .font(HubType.body(12, weight: .bold, relativeTo: .caption))
                    .foregroundStyle(accent.tint)
            }
        }) {
            HStack(alignment: .top, spacing: 12) {
                cap("Download", "↓ " + BandwidthPresentation.rate(down))
                cap("Upload", "↑ " + BandwidthPresentation.rate(up))
            }
            .padding(.horizontal, 8)
            if state?.canControl == true {
                Button("Edit limits") {
                    editing = Editing(limits: set, name: name, download: BandwidthPresentation.field(down),
                                      upload: BandwidthPresentation.field(up))
                }
                .buttonStyle(GlassControlStyle())
                .disabled(saving)
                .padFocusable("edit-\(set)") {
                    guard !saving else { return }
                    editing = Editing(limits: set, name: name, download: BandwidthPresentation.field(down),
                                      upload: BandwidthPresentation.field(up))
                }
                .padding(.horizontal, 8)
                .padding(.top, 6)
                .accessibilityIdentifier("edit-\(set)")
            }
        }
        .accessibilityIdentifier("limits-\(set)")
    }

    private func cap(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            GlassLabel(text: label)
            Text(value)
                .font(HubType.heading(20, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private func load() async {
        guard !saving else { return }
        do {
            state = try await model.hub.fetch(HubEndpoints.bandwidth, as: BandwidthState.self)
            status = StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: state != nil)
        }
    }

    private func save(_ change: BandwidthChange) async {
        guard !saving else { return }
        saving = true
        defer { saving = false }
        status = StatusMessage("Saving and checking with qBittorrent…")
        do {
            state = try await model.hub.fetch(HubEndpoints.setBandwidth(change), as: BandwidthState.self)
            status = StatusMessage("Speed limits saved")
        } catch {
            if error.kind == .cancelled { return }
            status = StatusMessage(error.message, tone: .error)
        }
    }
}

/// A set's two caps, in KiB/s, as a sheet: Apply checks them first.
struct LimitsEditor: View {
    @Binding var editing: SpeedLimitsView.Editing?
    let inUse: String
    let apply: (BandwidthChange) -> Void
    @Environment(\.horizontalSizeClass) private var sizeClass
    /// The field a controller's Ⓐ chose to type in (#46).
    @FocusState private var typing: String?

    var body: some View {
        if let current = editing {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(current.name)
                            .font(HubType.heading(22, weight: .bold, relativeTo: .title2))
                            .foregroundStyle(.white)
                        Text("In KiB/s, for every transfer. 0 means no limit.")
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.7))
                    }
                    Spacer(minLength: 8)
                    GlassRoundButton(systemImage: "xmark", label: "Close", size: 40, pad: "close") { editing = nil }
                        .keyboardShortcut(.cancelAction)
                }
                field("Download", text: Binding(get: { editing?.download ?? "" }, set: { editing?.download = $0 }))
                field("Upload", text: Binding(get: { editing?.upload ?? "" }, set: { editing?.upload = $0 }))
                if !current.error.isEmpty {
                    Text(current.error)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(Color.dangerText)
                }
                Button {
                    applyLimits()
                } label: {
                    Text("Apply")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(PrimaryPillStyle())
                .keyboardShortcut(.defaultAction)
                .accessibilityIdentifier("apply-limits")
                .padFocusable("apply", scrolls: false) { applyLimits() }
                Text("Keeps \(inUse) in use.")
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
                Spacer(minLength: 0)
            }
            .padding(20)
            // A page of its own while it shows: Ⓑ closes it (#46).
            .padPage("limits-editor", modal: true, initial: "field-Download") { editing = nil }
            .presentationBackground { GlassSheetFill() }
            .presentationDetents(sizeClass == .compact ? [.medium, .large] : [.large])
        }
    }

    /// The caps as they are typed now, read when Apply is pressed: the body's
    /// own copy can be a keystroke behind on a busy device, and Apply then
    /// saved the old cap and closed (#68, the iPad Pro under load).
    private func applyLimits() {
        guard let current = editing else { return }
        guard let down = BandwidthPresentation.parse(current.download),
              let up = BandwidthPresentation.parse(current.upload) else {
            editing?.error = "Enter a number from 0 to 1,048,576."
            return
        }
        editing = nil
        apply(BandwidthChange(limitsFor: current.limits, downloadBps: down, uploadBps: up))
    }

    private func field(_ label: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            GlassLabel(text: label)
            TextField("0", text: text)
                .textFieldStyle(.plain)
                .font(HubType.body(17))
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .padding(.horizontal, 14)
                .frame(height: 46)
                .glassPanel(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityLabel("\(label) limit in KiB per second")
                .accessibilityIdentifier("limit-\(label.lowercased())")
                .focused($typing, equals: label)
                // Ⓐ types in it, with the keyboard the system offers.
                .padFocusable("field-\(label)", ring: .rounded(12), scrolls: false) { typing = label }
        }
    }
}

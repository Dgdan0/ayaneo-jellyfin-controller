import HubKit
import SwiftUI

/// The request form as a glass sheet (the prototype's request sheet; Android
/// `RequestFlow` in `FormOverlay`): the quality profile and the folder that
/// Radarr or Sonarr marks default, every season of a series until some are
/// picked, and Request at the foot. Nothing is sent until Request; a request
/// is never sent twice on its own.
struct RequestSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.glassAccent) private var accent
    let key: String
    let fallbackTitle: String
    let done: (CreateRequestReply) -> Void

    @State private var draft: RequestDraft?
    @State private var status = StatusMessage("")
    @State private var sending = false
    @State private var loads = 0
    private var isSeries: Bool { draft?.isSeries ?? key.contains(":series:") }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header
                .padding(.horizontal, 20)
                .padding(.top, 22)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    StatusLine(message: status) { loads += 1 }
                    if let draft { form(draft) }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
            .scrollBounceBehavior(.basedOnSize)
            Button(action: send) {
                Label(sending ? "Requesting…" : "Request", systemImage: "arrow.down.to.line")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(PrimaryPillStyle())
            .disabled(sending || (draft == nil && status.tone != .error))
            .padFocusable("request", scrolls: false, press: send)
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
            .padding(.top, 6)
        }
        // A controller goes down the form and Ⓑ closes it (#46).
        .padGroup("form", .column, members: padColumn, prefix: false)
        .padPage("request", modal: true) { if !sending { dismiss() } }
        .foregroundStyle(.white)
        .presentationBackground { GlassSheetFill() }
        .presentationCornerRadius(sizeClass == .compact ? 32 : 28)
        #if os(iOS)
        .presentationDetents(sizeClass == .compact ? [.medium, .large] : [.large])
        #else
        .frame(minWidth: 460, minHeight: 520)
        #endif
        .task(id: loads) { await loadOptions() }
    }

    /// The form's lines for a controller, top to bottom (#46).
    private var padColumn: [String] {
        var lines = ["close"]
        if let draft {
            if !draft.options.profiles.isEmpty { lines.append("profile") }
            if !draft.options.rootFolders.isEmpty { lines.append("folder") }
            if draft.isSeries, !draft.seasons.isEmpty {
                lines.append("all-seasons")
                if !draft.allSeasons { lines += draft.seasons.map { "season-\($0.number)" } }
            }
        }
        return lines + ["request"]
    }

    // MARK: Parts

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(draft?.heading(fallbackTitle: fallbackTitle) ?? "Request \(fallbackTitle)")
                    .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                    .lineLimit(2)
                if let subtitle = draft?.subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                }
            }
            Spacer(minLength: 8)
            GlassRoundButton(systemImage: "xmark", label: "Close", size: 38, pad: "close") { dismiss() }
                .disabled(sending)
        }
    }

    /// A menu cannot be opened for a controller: Ⓐ on a row shows its choices as rows the ring walks (#46).
    private func askProfile(_ draft: RequestDraft) {
        PadFocusCenter.shared.present(PadMenu(title: "Quality profile", choices: draft.options.profiles.indices.map { index in
            PadChoice(id: "profile-\(index)", title: draft.options.profiles[index].label, checked: index == draft.profileIndex) {
                self.draft?.profileIndex = index
            }
        }))
    }

    private func askFolder(_ draft: RequestDraft) {
        PadFocusCenter.shared.present(PadMenu(title: "Root folder", choices: draft.options.rootFolders.indices.map { index in
            PadChoice(id: "folder-\(index)", title: RequestDraft.folderName(draft.options.rootFolders[index]),
                      checked: index == draft.folderIndex) {
                self.draft?.folderIndex = index
            }
        }))
    }

    @ViewBuilder private func form(_ draft: RequestDraft) -> some View {
        if !draft.options.profiles.isEmpty {
            GlassLabel(text: "Quality").padding(.top, 4)
            group {
                choiceRow(label: "Quality profile", detail: "", value: draft.profile?.label ?? "", pad: "profile",
                          ask: { askProfile(draft) }) {
                    Picker("Quality profile", selection: binding(\.profileIndex)) {
                        ForEach(draft.options.profiles.indices, id: \.self) { index in
                            Text(draft.options.profiles[index].label).tag(index)
                        }
                    }
                }
            }
        }
        if !draft.options.rootFolders.isEmpty {
            GlassLabel(text: "Folder").padding(.top, 4)
            group {
                choiceRow(label: "Root folder", detail: draft.folder.map(RequestDraft.freeSpace) ?? "",
                          value: draft.folder.map(RequestDraft.folderName) ?? "", pad: "folder", ask: { askFolder(draft) }) {
                    Picker("Root folder", selection: binding(\.folderIndex)) {
                        ForEach(draft.options.rootFolders.indices, id: \.self) { index in
                            Text(RequestDraft.folderName(draft.options.rootFolders[index])).tag(index)
                        }
                    }
                }
            }
        }
        if draft.isSeries, !draft.seasons.isEmpty {
            GlassLabel(text: "Seasons").padding(.top, 4)
            group {
                toggleRow(label: "All seasons", detail: "", on: draft.allSeasons, pad: "all-seasons") {
                    self.draft?.allSeasons.toggle()
                }
                if !draft.allSeasons {
                    ForEach(draft.seasons) { season in
                        Divider().overlay(Color.white.opacity(0.08))
                        toggleRow(label: RequestDraft.seasonName(season), detail: RequestDraft.seasonDetail(season),
                                  on: draft.ticked.contains(season.number), pad: "season-\(season.number)") {
                            self.draft?.toggle(season: season.number)
                        }
                    }
                }
            }
        }
    }

    private func binding(_ path: WritableKeyPath<RequestDraft, Int>) -> Binding<Int> {
        Binding(get: { draft?[keyPath: path] ?? 0 }, set: { draft?[keyPath: path] = $0 })
    }

    /// A group of rows on one glass card (`.group`).
    private func group<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        VStack(spacing: 0) { content() }
            .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    /// A row whose value is chosen from a menu: the label (and a line under
    /// it) on the left, the value and the menu's mark on the right.
    private func choiceRow<Choices: View>(label: String, detail: String, value: String, pad: String,
                                          ask: @escaping () -> Void,
                                          @ViewBuilder choices: () -> Choices) -> some View {
        Menu {
            choices()
        } label: {
            HStack(spacing: 12) {
                rowWords(label: label, detail: detail)
                Spacer(minLength: 8)
                Text(value)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.62))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityValue(value)
        // A menu cannot be opened for a controller: Ⓐ shows its choices as the menu panel.
        .padFocusable(pad, ring: .inside(16), press: ask)
    }

    private func toggleRow(label: String, detail: String, on: Bool, pad: String,
                           toggle: @escaping () -> Void) -> some View {
        Button(action: toggle) {
            HStack(spacing: 12) {
                rowWords(label: label, detail: detail)
                Spacer(minLength: 8)
                Image(systemName: "checkmark")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(on ? accent.tint : .white.opacity(0.25))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(on ? "On" : "Off")
        .accessibilityAddTraits(on ? .isSelected : [])
        .padFocusable(pad, ring: .inside(16), press: toggle)
    }

    private func rowWords(label: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(.white)
            if !detail.isEmpty {
                Text(detail)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.6))
            }
        }
    }

    // MARK: Loading and sending

    private func loadOptions() async {
        status = StatusMessage("Loading request options…")
        do {
            let options = try await model.hub.fetch(HubEndpoints.requestOptions(key: key), as: RequestOptions.self)
            draft = RequestDraft(options: options)
            status = StatusMessage("")
        } catch {
            if error.kind == .cancelled { return }
            // Request still works without the choices: the server's defaults.
            status = StatusText.failed(error.message + " — Request uses the server's defaults", kind: error.kind,
                                       hasData: false)
        }
    }

    private func send() {
        guard !sending else { return }
        let body = draft?.body() ?? CreateRequestBody(key: key, seasons: isSeries ? .all : nil)
        sending = true
        status = StatusMessage("Requesting…")
        Task {
            defer { sending = false }
            do throws(HubFailure) {
                let reply = try await model.hub.fetch(HubEndpoints.createRequest(body), as: CreateRequestReply.self)
                done(reply)
                dismiss()
            } catch {
                status = StatusText.failed(error.message, kind: error.kind, hasData: false, canRetry: false)
            }
        }
    }
}

import HubKit
import SwiftUI
import UniformTypeIdentifiers

/// Settings (the prototype's `pgSettings`): its places in a list on the left
/// on an iPad or a Mac, as pills over the page on a phone, and the chosen
/// one's glass cards. Each place is built with the screens it belongs to:
/// Libraries (#15), Playback (#25), Notifications (#36), and Appearance,
/// Subtitles, the controller test and the licences (#38).
struct SettingsView: View {
    @Environment(\.glassMetrics) private var metrics

    enum Pane: String, CaseIterable, Identifiable {
        case appearance, libraries, playback, subtitles, notifications, controller, licences

        var id: String { rawValue }
        var title: String {
            switch self {
            case .appearance: "Appearance"
            case .libraries: "Libraries"
            case .playback: "Playback"
            case .subtitles: "Subtitles"
            case .notifications: "Notifications"
            case .controller: "Controller test"
            case .licences: "Fonts and licences"
            }
        }
        var systemImage: String {
            switch self {
            case .appearance: "paintpalette"
            case .libraries: "books.vertical"
            case .playback: "play.circle"
            case .subtitles: "captions.bubble"
            case .notifications: "bell"
            case .controller: "gamecontroller"
            case .licences: "doc.text"
            }
        }
    }

    @SceneStorage("settings.pane") private var pane: Pane = .appearance
    /// How big the window is: Subtitles draws its words at the size the player would.
    @State private var window = CGSize(width: 390, height: 844)

    var body: some View {
        ScrollView {
            Group {
                if metrics.compact {
                    VStack(alignment: .leading, spacing: 14) {
                        PageHeading(title: "Settings") { EmptyView() }
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(Pane.allCases) { place in
                                    ChoicePill(title: place.title, selected: place == pane, systemImage: place.systemImage) {
                                        pane = place
                                    }
                                    .accessibilityIdentifier("pane-\(place.rawValue)")
                                }
                            }
                        }
                        content
                    }
                } else {
                    HStack(alignment: .top, spacing: 22) {
                        nav.frame(width: 210)
                        content.frame(maxWidth: 760, alignment: .leading)
                    }
                }
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 30)
        }
        .ambientArtwork("")
        .onGeometryChange(for: CGSize.self) { $0.size } action: { window = $0 }
        #if DEBUG
        // scripts/mac.sh opens a place for screenshots: HUB_OPEN=pane:subtitles.
        .onAppear {
            if let open = ProcessInfo.processInfo.environment["HUB_OPEN"], open.hasPrefix("pane:"),
               let chosen = Pane(rawValue: String(open.dropFirst("pane:".count))) { pane = chosen }
        }
        #endif
        .environment(\.settingsWindow, window)
    }

    /// The prototype's `.snav`: the page's name, then its places.
    private var nav: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Settings")
                .font(HubType.heading(metrics.pageTitle, weight: .heavy, relativeTo: .largeTitle))
                .foregroundStyle(.white)
                .padding(.leading, 6)
                .padding(.bottom, 10)
            ForEach(Pane.allCases) { place in
                let on = place == pane
                Button {
                    pane = place
                } label: {
                    Label(place.title, systemImage: place.systemImage)
                        .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                        .foregroundStyle(on ? Color.white : .white.opacity(0.74))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(on ? Color.white.opacity(0.14) : .clear,
                                    in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
                .accessibilityIdentifier("pane-\(place.rawValue)")
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch pane {
        case .appearance: AppearanceSettings()
        case .libraries: LibrariesSettings()
        case .playback: PlaybackSettingsPane()
        case .subtitles: SubtitlesSettings()
        case .notifications: NotificationsSettingsPane()
        case .controller: ControllerSettings()
        case .licences: LicencesSettings()
        }
    }
}

/// Settings › Libraries: both sides' libraries in the order every device
/// shows them, each held by its grip and dragged into place, and Back to A–Z
/// once a side has an order of its own (#15).
struct LibrariesSettings: View {
    @Environment(AppModel.self) private var model
    @State private var media = LibraryOrderEditor(side: .media)
    @State private var books = LibraryOrderEditor(side: .books)

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            LibraryOrderCard(title: "Movies and TV", editor: media)
            LibraryOrderCard(title: "Books", editor: books)
        }
        .task(id: model.userId) {
            await media.load(model)
            await books.load(model)
            #if DEBUG
            // scripts/mac.sh: HUB_SHEET=atoz puts both sides back to A to Z.
            if ProcessInfo.processInfo.environment["HUB_SHEET"] == "atoz" {
                if media.custom { media.resetToName(model: model) }
                if books.custom { books.resetToName(model: model) }
            }
            #endif
        }
    }
}

/// One side's libraries as a glass card of rows (the prototype's `.scard2`
/// and `.group`), each row with the grip at its end.
struct LibraryOrderCard: View {
    @Environment(AppModel.self) private var model
    let title: String
    let editor: LibraryOrderEditor
    @State private var dragging: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(title)
                    .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                    .foregroundStyle(.white)
                Spacer(minLength: 8)
                Text(editor.custom ? "Your order" : "A to Z")
                    .font(HubType.body(13, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
            }
            Text("Hold a library by its grip and drag it. Every device shows this order.")
                .font(HubType.body(13.5, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.64))
                .fixedSize(horizontal: false, vertical: true)
            if !editor.status.text.isEmpty {
                StatusLine(message: editor.status) { Task { await editor.load(model) } }
            }
            if !editor.libraries.isEmpty {
                VStack(spacing: 0) {
                    ForEach(Array(editor.libraries.enumerated()), id: \.element.id) { index, library in
                        row(library, first: index == 0)
                    }
                }
                .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .onDrop(of: [.text], delegate: LibraryDropFallback(dragging: $dragging, editor: editor, model: model))
            }
            if !editor.notice.isEmpty {
                Text(editor.notice)
                    .font(HubType.body(13.5, weight: .semibold, relativeTo: .footnote))
                    .foregroundStyle(Color.pending)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if editor.custom {
                Button {
                    editor.resetToName(model: model)
                } label: {
                    Label("Back to A–Z", systemImage: "arrow.up.arrow.down")
                }
                .buttonStyle(GlassControlStyle())
                .accessibilityLabel("Back to A to Z: \(title)")
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    /// The prototype's `.opt`: a picture, the name with its kind under it, and
    /// the grip at the end.
    private func row(_ library: ArrangedLibrary, first: Bool) -> some View {
        HStack(spacing: 12) {
            Group {
                if library.art.isEmpty {
                    Image(systemName: editor.side == .media ? "film.stack" : "books.vertical")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.white.opacity(0.1))
                } else {
                    ArtworkView(path: library.art, width: 120)
                }
            }
            .frame(width: 34, height: 34)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            VStack(alignment: .leading, spacing: 2) {
                Text(library.title)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white)
                Text(library.kind)
                    .font(HubType.body(12.5, relativeTo: .caption))
                    .foregroundStyle(.white.opacity(0.6))
            }
            .lineLimit(1)
            Spacer(minLength: 8)
            GripMark(color: .white.opacity(0.75))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(dragging == library.id ? Color.white.opacity(0.08) : .clear)
        .overlay(alignment: .top) {
            if !first { Rectangle().fill(.white.opacity(0.08)).frame(height: 1) }
        }
        .contentShape(Rectangle())
        .arrangeable(library.id, dragging: $dragging, editor: editor, model: model)
        .accessibilityElement(children: .combine)
        .accessibilityHint("Drag to move this library")
        .accessibilityAction(named: "Move earlier") { editor.step(library.id, by: -1, model: model) }
        .accessibilityAction(named: "Move later") { editor.step(library.id, by: 1, model: model) }
    }
}

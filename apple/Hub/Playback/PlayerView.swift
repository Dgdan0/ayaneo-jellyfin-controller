import HubKit
import SwiftUI

/// The player, over everything (GLASS_PLAN.md › Player; the prototype's
/// `.pl`; Android `playback/PlayerChrome`): the picture, and over it the Glass
/// chrome. At the top a round Back, the title over its episode, glass pills
/// for Audio & subtitles, Chapters and This video, then AirPlay, Lock and
/// picture in picture; in the middle Previous, −10, a white Play disc, +10
/// and Next; at the foot the timeline in a frosted bar, "5:34 · Part A" under
/// its start and "−22:53" under its end, a notch where each chapter starts.
///
/// It follows the device: turned, the picture fills the screen; held upright
/// it is a band across the middle and the controls are laid out for that,
/// the title on its own line under the buttons. Where the pills do not fit
/// they are round icons, and where those do not either, one round menu: a
/// narrow window keeps every function (`PlayerLayout`). Turning it keeps
/// playing, keeps the chrome as it was and keeps an open panel open.
///
/// A tap shows or hides the chrome; it hides itself 3.5 s into playing, as on
/// Android, and stays while paused or while a panel is open. Space, ← and →
/// play, pause and step ten seconds from a keyboard; Escape closes a panel,
/// then leaves.
struct PlayerView: View {
    let player: PlayerModel
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.glassAccent) private var accent

    @State private var chromeShown = true
    @State private var locked = false
    @State private var unlockShown = false
    /// The panels open, the last on top (`PlayerSheet`).
    @State private var panels: [PlayerPanel] = []
    /// Where a drag along the timeline is, 0…1, until it lets go.
    @State private var scrub: Double?
    @State private var hiding: Task<Void, Never>?
    @State private var unlockHiding: Task<Void, Never>?
    @FocusState private var keys: Bool

    /// Debug builds: HUB_PLAY_CHROME=pinned keeps the chrome up for screenshots.
    private var pinned: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["HUB_PLAY_CHROME"] == "pinned"
        #else
        false
        #endif
    }

    private var showsChrome: Bool { (chromeShown || pinned || !panels.isEmpty) && !locked }

    var body: some View {
        GeometryReader { proxy in
            // The picture and the chrome run under the safe area to the
            // screen's edges; the reader's own size stops short of them.
            let safe = proxy.safeAreaInsets
            let screen = CGSize(width: proxy.size.width + safe.leading + safe.trailing,
                                height: proxy.size.height + safe.top + safe.bottom)
            let layout = PlayerLayout(size: screen, safe: safe)
            ZStack {
                Color.black
                standIn
                VideoSurface(player: player.player, ready: { ready in player.setReadyForDisplay(ready) },
                             layer: { layer in player.attach(layer) })
                elsewhere
                if showsChrome {
                    vignette.transition(.opacity)
                }
                // Over the vignette, so it never greys them; under the controls.
                if !player.subtitleLines.isEmpty, !player.pipActive {
                    SubtitleOverlay(lines: player.subtitleLines, look: player.subtitleLook, size: screen,
                                    picture: letterboxed(player.presentationSize, in: screen),
                                    covered: showsChrome && player.plan != nil ? layout.bottom + layout.bottomBarHeight : 0)
                }
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: tapped)
                if showsChrome {
                    chrome(layout).transition(.opacity)
                }
                if panels.isEmpty {
                    corner(layout)
                }
                status(layout)
                messages(layout)
                if locked && unlockShown {
                    GlassRoundButton(systemImage: "lock.open.fill", label: "Unlock controls", size: layout.round) { unlock() }
                        .padding(.top, layout.top)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .transition(.opacity)
                }
                if !panels.isEmpty {
                    // It animates itself (`PlayerSheet`), never by a transition.
                    PlayerSheet(player: player, panels: $panels, layout: layout)
                        .zIndex(2)
                }
                #if os(macOS)
                // The hidden title bar's band still moves the window.
                Color.clear
                    .frame(height: proxy.safeAreaInsets.top)
                    .contentShape(Rectangle())
                    .gesture(WindowDragGesture())
                    .allowsWindowActivationEvents(true)
                    .frame(maxHeight: .infinity, alignment: .top)
                #endif
            }
            .ignoresSafeArea()
            .animation(.easeOut(duration: 0.2), value: showsChrome)
            .animation(.easeOut(duration: 0.25), value: player.upNext == nil)
            .animation(.easeOut(duration: 0.25), value: player.skipSegment == nil)
            .animation(.easeOut(duration: 0.3), value: player.readyForDisplay)
        }
        // Over video every button, pill and panel is dark glass tinted by the
        // playing title's colours, whatever the page was; only Play is white.
        .environment(\.glassPalette, model.colors.palette(for: player.backdrop))
        .environment(\.glassOverVideo, true)
        .onChange(of: player.backdrop, initial: true) { _, path in
            if !path.isEmpty { model.colors.want([path]) }
        }
        .foregroundStyle(.white)
        #if os(iOS)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        #endif
        .focusable()
        .focused($keys)
        .focusEffectDisabled()
        .onKeyPress(.space) { panels.isEmpty ? act { player.togglePlay() } : .ignored }
        .onKeyPress(.leftArrow) { panels.isEmpty ? act { player.seek(by: -10_000) } : .ignored }
        .onKeyPress(.rightArrow) { panels.isEmpty ? act { player.seek(by: 10_000) } : .ignored }
        .onKeyPress(.escape) {
            if panels.isEmpty {
                player.close()
            } else {
                panels.removeLast()
            }
            return .handled
        }
        .onAppear {
            keys = true
            scheduleHide()
        }
        .onChange(of: player.isPlaying) { _, playing in
            // As on Android: the chrome comes up when playing starts or stops,
            // and goes again only while it plays.
            chromeShown = true
            if playing { scheduleHide() } else { hiding?.cancel() }
        }
        .onChange(of: panels.isEmpty) { _, closed in
            if closed { poke() } else { hiding?.cancel() }
        }
        #if os(iOS)
        .onChange(of: scenePhase) { _, phase in
            // Leaving the app ends playback unless picture in picture carries
            // it on. The Mac plays on with the window minimised or the app
            // hidden, as QuickTime does; closing the window or quitting ends it.
            player.sceneChanged(background: phase == .background)
        }
        #endif
        #if DEBUG
        .onChange(of: player.debugPanel) { _, name in
            let tour: [String: [PlayerPanel]] = ["tracks": [.tracks], "timing": [.tracks, .timing],
                                                 "video": [.video], "chapters": [.chapters]]
            panels = name.flatMap { tour[$0] } ?? []
        }
        #endif
        .accessibilityAddTraits(.isModal)
    }

    // MARK: Pieces

    /// The title's picture, until the first frame of the video is on screen
    /// (the prototype's `.pl > .art`, at 80%).
    @ViewBuilder private var standIn: some View {
        if !player.readyForDisplay || player.externalActive, !player.backdrop.isEmpty {
            ArtworkView(path: player.backdrop, width: 1280, placeholder: .clear, keepsPrevious: true)
                .opacity(player.externalActive ? 0.35 : 0.8)
                .transition(.opacity)
                .allowsHitTesting(false)
        }
    }

    /// Where the picture is when it is not here: on an AirPlay receiver, or in
    /// the small picture-in-picture window.
    @ViewBuilder private var elsewhere: some View {
        if player.pipActive || player.externalActive {
            VStack(spacing: 10) {
                Image(systemName: player.pipActive ? "pip" : "airplayvideo")
                    .font(.system(size: 40, weight: .regular))
                Text(player.pipActive ? "Playing in picture in picture" : "Playing on AirPlay")
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
            }
            .foregroundStyle(.white.opacity(0.75))
            .accessibilityElement(children: .combine)
        }
    }

    /// The prototype's `.vig`: dark at the top and the foot, clear between.
    private var vignette: some View {
        LinearGradient(stops: [.init(color: .black.opacity(0.66), location: 0), .init(color: .clear, location: 0.24),
                               .init(color: .clear, location: 0.6), .init(color: .black.opacity(0.8), location: 1)],
                       startPoint: .top, endPoint: .bottom)
            .allowsHitTesting(false)
    }

    private func chrome(_ layout: PlayerLayout) -> some View {
        ZStack {
            VStack(spacing: 0) {
                topBar(layout)
                    .padding(.top, layout.top)
                    .padding(.horizontal, layout.side)
                Spacer(minLength: 0)
                if player.plan != nil {
                    bottomBar(layout)
                        .padding(.horizontal, layout.side)
                        .padding(.bottom, layout.bottom)
                }
            }
            if player.phase == .playing {
                // On a phone turned sideways the up-next card at the right
                // would cover +10 and Next; the row moves into the room left of it.
                middleRow(layout)
                    .offset(x: player.upNext != nil && layout.short && !locked
                            ? -(layout.upNextWidth + 16) / 2 : 0)
                    .animation(.easeOut(duration: 0.25), value: player.upNext == nil)
            }
        }
    }

    /// One row on a wide screen; held upright, the buttons first and the
    /// title on its own line under them, so neither is squeezed.
    @ViewBuilder private func topBar(_ layout: PlayerLayout) -> some View {
        if layout.stacked {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 8) {
                    backButton(layout)
                    Spacer(minLength: 8)
                    panelButtons(layout)
                    tools(layout)
                }
                titleBlock(layout, lines: 2)
            }
        } else {
            HStack(spacing: 12) {
                backButton(layout)
                titleBlock(layout, lines: 1)
                Spacer(minLength: 8)
                panelButtons(layout)
                tools(layout)
            }
        }
    }

    private func backButton(_ layout: PlayerLayout) -> some View {
        GlassRoundButton(systemImage: "chevron.left", label: "Back", size: layout.round) { player.close() }
    }

    private func titleBlock(_ layout: PlayerLayout, lines: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(HubType.body(layout.phone ? 17 : 19, weight: .bold, relativeTo: .headline))
                .lineLimit(1)
            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(HubType.body(layout.phone ? 12.5 : 13.5, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.72))
                    .lineLimit(lines)
            }
        }
        .accessibilityElement(children: .combine)
    }

    /// Audio & subtitles, Chapters and This video: pills where they fit, round
    /// icons where they do not, and one round menu in the narrowest window.
    @ViewBuilder private func panelButtons(_ layout: PlayerLayout) -> some View {
        let disabled = player.plan == nil
        switch layout.panelButtons {
        case .pills:
            HStack(spacing: 8) {
                PlayerChip(systemImage: "captions.bubble", title: "Audio & subtitles", compact: layout.phone) { open(.tracks) }
                PlayerChip(systemImage: "list.bullet", title: "Chapters", compact: layout.phone) { open(.chapters) }
                PlayerChip(systemImage: nil, title: "This video", compact: layout.phone) { open(.video) }
            }
            .disabled(disabled)
        case .icons:
            HStack(spacing: 8) {
                GlassRoundButton(systemImage: "captions.bubble", label: "Audio & subtitles", size: layout.round) { open(.tracks) }
                GlassRoundButton(systemImage: "list.bullet", label: "Chapters", size: layout.round) { open(.chapters) }
                GlassRoundButton(systemImage: "slider.horizontal.3", label: "This video", size: layout.round) { open(.video) }
            }
            .disabled(disabled)
        case .menu:
            Menu {
                Button("Audio & subtitles", systemImage: "captions.bubble") { open(.tracks) }
                Button("Chapters", systemImage: "list.bullet") { open(.chapters) }
                Button("This video", systemImage: "slider.horizontal.3") { open(.video) }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: layout.round * 0.4, weight: .semibold))
                    .frame(width: layout.round, height: layout.round)
                    .glassPanel(Circle())
                    .contentShape(Circle())
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .disabled(disabled)
            .accessibilityLabel("Audio, chapters and this video")
        }
    }

    /// AirPlay, Lock (iPhone and iPad) and picture in picture.
    private func tools(_ layout: PlayerLayout) -> some View {
        HStack(spacing: 8) {
            ZStack {
                Circle().fill(.clear).glassPanel(Circle())
                Image(systemName: "airplayvideo")
                    .font(.system(size: layout.round * 0.4, weight: .semibold))
                    .foregroundStyle(player.externalActive ? accent.tint : .white)
                RoutePicker(player: player.player)
            }
            .frame(width: layout.round, height: layout.round)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("AirPlay")
            .accessibilityAddTraits(.isButton)
            #if os(iOS)
            GlassRoundButton(systemImage: "lock", label: "Lock controls", size: layout.round) { lock() }
            #endif
            if player.pipSupported {
                GlassRoundButton(systemImage: player.pipActive ? "pip.exit" : "pip.enter",
                                 label: player.pipActive ? "Leave picture in picture" : "Picture in picture",
                                 size: layout.round) { player.togglePictureInPicture() }
                    .disabled(!player.pipPossible && !player.pipActive)
                    .opacity(player.pipPossible || player.pipActive ? 1 : 0.45)
            }
        }
    }

    private func middleRow(_ layout: PlayerLayout) -> some View {
        let previous = player.plan?.previousItem != nil
        let next = player.plan?.nextItem != nil
        return HStack(spacing: layout.gap) {
            // A missing neighbour keeps its place, so Play stays in the middle.
            PlayerSkipButton(systemImage: "backward.end.fill", text: nil, label: "Previous episode", size: layout.skip) {
                act { player.playPrevious() }
            }
            .opacity(previous ? 1 : 0)
            .disabled(!previous)
            PlayerSkipButton(systemImage: nil, text: "\u{2212}10", label: "Back 10 seconds", size: layout.skip) {
                act { player.seek(by: -10_000) }
            }
            PlayerPlayDisc(playing: player.isPlaying, buffering: player.isBuffering, size: layout.big) {
                act { player.togglePlay() }
            }
            PlayerSkipButton(systemImage: nil, text: "+10", label: "Forward 10 seconds", size: layout.skip) {
                act { player.seek(by: 10_000) }
            }
            PlayerSkipButton(systemImage: "forward.end.fill", text: nil, label: "Next episode", size: layout.skip) {
                act { player.playNext() }
            }
            .opacity(next ? 1 : 0)
            .disabled(!next)
        }
    }

    private func bottomBar(_ layout: PlayerLayout) -> some View {
        let duration = player.durationMillis
        let position = scrub.map { Int64($0 * Double(duration)) } ?? player.positionMillis
        let chapters = player.chapters
        let chapter = PlayerLabels.chapterName(at: position, in: chapters)
        return VStack(spacing: layout.phone ? 7 : 10) {
            PlayerTimeline(positionMillis: position, durationMillis: duration, bufferedMillis: player.bufferedMillis,
                           marks: duration > 0 ? chapters.map { Double($0.positionMillis) / Double(duration) }.filter { $0 > 0 } : [],
                           scrub: $scrub, seek: { target in
                               player.seek(to: target)
                               scheduleHide()
                           }, adjust: { delta in act { player.seek(by: delta) } })
            HStack {
                Text(PlayerLabels.positionLine(positionMillis: position, chapterName: chapter))
                Spacer(minLength: 12)
                Text(PlayerLabels.remainingLine(positionMillis: position, durationMillis: duration))
            }
            .font(HubType.body(layout.phone ? 12.5 : 14, relativeTo: .footnote))
            .monospacedDigit()
            .foregroundStyle(.white.opacity(0.82))
            .lineLimit(1)
            .accessibilityHidden(true)
        }
        .padding(.horizontal, layout.phone ? 14 : 18)
        .padding(.vertical, layout.phone ? 10 : 14)
        .glassPanel(RoundedRectangle(cornerRadius: layout.phone ? 18 : 24, style: .continuous))
    }

    /// Above the timeline's bar at the right: Skip intro while an intro (or a
    /// recap, preview or ad) plays, and the up-next card near the end.
    private func corner(_ layout: PlayerLayout) -> some View {
        VStack(alignment: .trailing, spacing: 12) {
            if let segment = player.skipSegment, let label = UpNext.skipLabel(segment.type) {
                Button { act { player.skip() } } label: {
                    Label(label, systemImage: "forward.fill")
                }
                .buttonStyle(GlassPillStyle())
                .transition(.move(edge: .trailing).combined(with: .opacity))
            }
            if let card = player.upNext {
                UpNextCardView(card: card, compact: layout.phone,
                               playNow: { player.playNext() }, watchCredits: { player.watchCredits() })
                    .frame(width: layout.upNextWidth)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
            }
        }
        .padding(.trailing, layout.side)
        .padding(.bottom, layout.bottom + (showsChrome && player.plan != nil ? layout.bottomBarHeight + 14 : 0))
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
        .opacity(locked ? 0 : 1)
        .allowsHitTesting(!locked)
    }

    /// Opening, a failure, or waiting on the network before anything plays.
    @ViewBuilder private func status(_ layout: PlayerLayout) -> some View {
        switch player.phase {
        case .opening:
            VStack(spacing: 14) {
                ProgressView().controlSize(.large).tint(.white)
                Text(title.isEmpty ? "Opening…" : "Opening \(title)…")
                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.85))
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, layout.side)
            .allowsHitTesting(false)
        case .failed(let message):
            VStack(spacing: 12) {
                Text("This video could not be played")
                    .font(HubType.heading(22, weight: .heavy, relativeTo: .title3))
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.75))
                    .multilineTextAlignment(.center)
                HStack(spacing: 10) {
                    Button("Back") { player.close() }
                        .buttonStyle(GlassPillStyle())
                    Button("Try again") { player.retry() }
                        .buttonStyle(GlassPillStyle())
                }
                .padding(.top, 4)
            }
            .padding(24)
            .frame(maxWidth: 420)
            .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
            .padding(.horizontal, layout.side)
        case .playing:
            EmptyView()
        }
    }

    /// A change on its way to the hub, or one that did not work, in a small
    /// glass capsule under the top bar.
    @ViewBuilder private func messages(_ layout: PlayerLayout) -> some View {
        let text = player.notice ?? (player.applying && panels.isEmpty ? "Changing playback…" : nil)
        if let text {
            HStack(spacing: 10) {
                if player.notice == nil { ProgressView().controlSize(.small).tint(.white) }
                Text(text)
                    .font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                    .lineLimit(2)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .glassPanel(Capsule())
            .padding(.top, layout.top + layout.round + (layout.stacked ? 70 : 16))
            .padding(.horizontal, layout.side)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .transition(.opacity)
            .allowsHitTesting(false)
            .accessibilityAddTraits(.updatesFrequently)
        }
    }

    private var title: String {
        if let plan = player.plan { return PlayerLabels.title(plan.item) }
        return player.request?.title ?? ""
    }

    private var subtitle: String {
        player.plan.map { PlayerLabels.subtitle($0.item) } ?? ""
    }

    // MARK: Showing and hiding

    private func open(_ panel: PlayerPanel) {
        hiding?.cancel()
        chromeShown = true
        panels = [panel]
    }

    /// A tap on the picture: the chrome comes or goes; locked, the way to
    /// unlock comes for a moment.
    private func tapped() {
        if locked {
            showUnlock()
        } else if chromeShown {
            hiding?.cancel()
            chromeShown = false
        } else {
            poke()
        }
    }

    /// Something was done: the chrome stays a while longer.
    @discardableResult
    private func act(_ action: () -> Void) -> KeyPress.Result {
        action()
        poke()
        return .handled
    }

    private func poke() {
        guard !locked else { return }
        chromeShown = true
        scheduleHide()
    }

    private func scheduleHide() {
        hiding?.cancel()
        guard player.isPlaying, !pinned, panels.isEmpty else { return }
        hiding = Task {
            try? await Task.sleep(for: .seconds(3.5))
            guard !Task.isCancelled, player.isPlaying, scrub == nil, panels.isEmpty else { return }
            chromeShown = false
        }
    }

    private func lock() {
        hiding?.cancel()
        panels = []
        locked = true
        chromeShown = false
        showUnlock()
    }

    private func unlock() {
        unlockHiding?.cancel()
        unlockShown = false
        locked = false
        poke()
    }

    private func showUnlock() {
        unlockShown = true
        unlockHiding?.cancel()
        unlockHiding = Task {
            try? await Task.sleep(for: .seconds(2.5))
            guard !Task.isCancelled else { return }
            unlockShown = false
        }
    }
}

/// The prototype's player sizes on an iPad, its phone sizes wherever the
/// short side is a phone's, and how the top bar fits the width it has.
struct PlayerLayout {
    enum PanelButtons { case pills, icons, menu }

    let size: CGSize
    let safe: EdgeInsets

    var phone: Bool { min(size.width, size.height) < 500 }
    var side: CGFloat { max(phone ? 16 : 26, max(safe.leading, safe.trailing) + 4) }
    /// The width between the side margins.
    var usable: CGFloat { size.width - side * 2 }
    var top: CGFloat {
        #if os(macOS)
        // Below the window buttons, which sit over the hidden title bar.
        safe.top + 10
        #else
        max(24, safe.top)
        #endif
    }
    var bottom: CGFloat { max(24, safe.bottom) }
    var round: CGFloat { phone ? 42 : 46 }

    /// AirPlay, Lock where there is one, and picture in picture (counted
    /// even where the device has none, so the layout is the same everywhere).
    private var tools: CGFloat {
        #if os(iOS)
        3 * round + 2 * 8
        #else
        2 * round + 8
        #endif
    }

    private var icons: CGFloat { 3 * round + 2 * 8 }

    /// The pills beside the title: an iPad, or a Mac window, wide enough.
    private var pillsFit: Bool { size.width >= 900 }

    /// The title goes on its own line under the buttons when the buttons and
    /// at least 200 points of title (232 with the gaps) do not fit in one
    /// row: a phone held upright, a narrow window.
    var stacked: Bool {
        let row: CGFloat = round + icons + tools + 232
        return !pillsFit && usable < row
    }

    var panelButtons: PanelButtons {
        if pillsFit { return .pills }
        if !stacked { return .icons }
        let buttons: CGFloat = round + icons + tools + 16
        return usable >= buttons ? .icons : .menu
    }

    /// The middle row shrinks only where it would not fit (a narrow window):
    /// four steps of 50, Play at 76 and gaps of 14.
    private var narrowMiddle: Bool { usable < 332 }
    var skip: CGFloat { narrowMiddle ? 44 : (phone ? 50 : 58) }
    var big: CGFloat { narrowMiddle ? 66 : (phone ? 76 : 88) }
    var gap: CGFloat {
        let natural: CGFloat = phone ? 18 : 34
        let room: CGFloat = (usable - skip * 4 - big) / 4
        return max(8, min(natural, room))
    }

    /// The frosted bar's height, for what sits above it.
    var bottomBarHeight: CGFloat { phone ? 64 : 80 }

    /// A phone turned sideways: about 400 points tall.
    var short: Bool { size.height < 500 }
    var upNextWidth: CGFloat { min(phone ? 300 : 340, usable) }

    /// The panels come up from the bottom where a sheet at the side would
    /// cover most of the picture (a phone held upright, a narrow window).
    var sheetFromBottom: Bool { size.width < 600 }
    /// The prototype's 410 on an iPad, 360 on a phone turned sideways, and
    /// the notch's inset on top, so the rows keep their width beside it.
    var sheetWidth: CGFloat {
        if sheetFromBottom { return size.width }
        let width: CGFloat = (phone ? 360 : 410) + safe.trailing
        return min(width, size.width - 40)
    }
}

import HubKit
import SwiftUI

/// The player, over everything (GLASS_PLAN.md › Player; the prototype's
/// `.pl`; Android `playback/PlayerChrome`): the picture, and over it the Glass
/// chrome. At the top a round Back, the title over its episode, glass pills
/// for Audio & subtitles, Chapters and This video, then AirPlay, Lock and
/// picture in picture; in the middle Previous, −10, a white Play disc, +10
/// and Next; at the foot the timeline in a frosted bar, "5:34 · Part A" under
/// its start and "−22:53" under its end.
///
/// A tap shows or hides the chrome; it hides itself 3.5 s into playing, as on
/// Android, and stays while paused. Space, ← and → play, pause and step ten
/// seconds from a keyboard; Escape leaves.
struct PlayerView: View {
    let player: PlayerModel
    @Environment(\.scenePhase) private var scenePhase

    @State private var chromeShown = true
    @State private var locked = false
    @State private var unlockShown = false
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

    private var showsChrome: Bool { (chromeShown || pinned) && !locked }

    var body: some View {
        GeometryReader { proxy in
            let layout = PlayerLayout(size: proxy.size, safe: proxy.safeAreaInsets)
            ZStack {
                Color.black
                standIn
                VideoSurface(player: player.player) { ready in player.setReadyForDisplay(ready) }
                Color.clear
                    .contentShape(Rectangle())
                    .onTapGesture(perform: tapped)
                if showsChrome {
                    chrome(layout).transition(.opacity)
                }
                if let card = player.upNext, !locked {
                    UpNextCardView(card: card, compact: layout.phone,
                                   playNow: { player.playNext() }, watchCredits: { player.watchCredits() })
                        .frame(width: min(340, proxy.size.width - layout.side * 2))
                        .padding(.trailing, layout.side)
                        .padding(.bottom, layout.bottom + (showsChrome ? layout.bottomBarHeight + 14 : 0))
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
                status(layout)
                if locked && unlockShown {
                    GlassRoundButton(systemImage: "lock.open.fill", label: "Unlock controls", size: layout.round) { unlock() }
                        .padding(.top, layout.top)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .transition(.opacity)
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
            .animation(.easeOut(duration: 0.3), value: player.readyForDisplay)
        }
        // Over video the panels are the dark glass whatever the page was.
        .environment(\.glassPalette, .neutral)
        .foregroundStyle(.white)
        #if os(iOS)
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        #endif
        .focusable()
        .focused($keys)
        .focusEffectDisabled()
        .onKeyPress(.space) { act { player.togglePlay() } }
        .onKeyPress(.leftArrow) { act { player.seek(by: -10_000) } }
        .onKeyPress(.rightArrow) { act { player.seek(by: 10_000) } }
        .onKeyPress(.escape) {
            player.close()
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
        .onChange(of: scenePhase) { _, phase in
            // Leaving the app leaves the player: the session is never left open.
            if phase == .background { player.close() }
        }
        .accessibilityAddTraits(.isModal)
    }

    // MARK: Pieces

    /// The title's picture, until the first frame of the video is on screen
    /// (the prototype's `.pl > .art`, at 80%).
    @ViewBuilder private var standIn: some View {
        if !player.readyForDisplay, !player.backdrop.isEmpty {
            ArtworkView(path: player.backdrop, width: 1280, placeholder: .clear, keepsPrevious: true)
                .opacity(0.8)
                .transition(.opacity)
                .allowsHitTesting(false)
        }
    }

    private func chrome(_ layout: PlayerLayout) -> some View {
        ZStack {
            // The prototype's `.vig`: dark at the top and the foot, clear between.
            LinearGradient(stops: [.init(color: .black.opacity(0.66), location: 0), .init(color: .clear, location: 0.24),
                                   .init(color: .clear, location: 0.6), .init(color: .black.opacity(0.8), location: 1)],
                           startPoint: .top, endPoint: .bottom)
                .allowsHitTesting(false)
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
                middleRow(layout)
            }
        }
    }

    private func topBar(_ layout: PlayerLayout) -> some View {
        HStack(spacing: 12) {
            GlassRoundButton(systemImage: "chevron.left", label: "Back", size: layout.round) { player.close() }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(HubType.body(layout.phone ? 17 : 19, weight: .bold, relativeTo: .headline))
                    .lineLimit(1)
                if !subtitle.isEmpty {
                    Text(subtitle)
                        .font(HubType.body(layout.phone ? 12.5 : 13.5, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.72))
                        .lineLimit(1)
                }
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            // The prototype keeps the pills off a phone held upright.
            if !layout.narrow {
                HStack(spacing: 8) {
                    PlayerChip(systemImage: "captions.bubble", title: "Audio & subtitles", compact: layout.phone)
                    PlayerChip(systemImage: "list.bullet", title: "Chapters", compact: layout.phone)
                    PlayerChip(systemImage: nil, title: "This video", compact: layout.phone)
                }
                // Their panels come with the next part of the player (#2, 4b).
                .disabled(true)
                .opacity(0.45)
            }
            GlassRoundButton(systemImage: "airplayvideo", label: "AirPlay", size: layout.round) {}
                .disabled(true)
                .opacity(0.45)
            #if os(iOS)
            GlassRoundButton(systemImage: "lock", label: "Lock controls", size: layout.round) { lock() }
            #endif
            GlassRoundButton(systemImage: "pip.enter", label: "Picture in picture", size: layout.round) {}
                .disabled(true)
                .opacity(0.45)
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
        let chapter = PlayerLabels.chapterName(at: position, in: player.plan?.chapters ?? [])
        return VStack(spacing: layout.phone ? 7 : 10) {
            PlayerTimeline(positionMillis: position, durationMillis: duration, bufferedMillis: player.bufferedMillis,
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
                Text(message)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.75))
                    .multilineTextAlignment(.center)
                HStack(spacing: 10) {
                    Button("Back") { player.close() }
                        .buttonStyle(PrimaryPillStyle())
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

    private var title: String {
        if let plan = player.plan { return PlayerLabels.title(plan.item) }
        return player.request?.title ?? ""
    }

    private var subtitle: String {
        player.plan.map { PlayerLabels.subtitle($0.item) } ?? ""
    }

    // MARK: Showing and hiding

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
        guard player.isPlaying, !pinned else { return }
        hiding = Task {
            try? await Task.sleep(for: .seconds(3.5))
            guard !Task.isCancelled, player.isPlaying, scrub == nil else { return }
            chromeShown = false
        }
    }

    private func lock() {
        hiding?.cancel()
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

/// The prototype's player sizes on an iPad, and its phone sizes wherever the
/// short side is a phone's.
struct PlayerLayout {
    let size: CGSize
    let safe: EdgeInsets

    var phone: Bool { min(size.width, size.height) < 500 }
    /// Too narrow for the three pills beside the title and its buttons: a
    /// phone either way up, or an iPad mini upright (where they were squeezed
    /// to "…").
    var narrow: Bool { size.width < 900 }
    var side: CGFloat { max(phone ? 16 : 26, max(safe.leading, safe.trailing) + 4) }
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
    var skip: CGFloat { phone ? 50 : 58 }
    var big: CGFloat { phone ? 76 : 88 }
    var gap: CGFloat { phone ? 18 : 34 }
    /// The frosted bar's height, for what sits above it.
    var bottomBarHeight: CGFloat { phone ? 64 : 80 }
}

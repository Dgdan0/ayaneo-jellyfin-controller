import HubKit
import SwiftUI

// The parts of a series' downloads on its page (#48): a card's corner, the
// storage bar and the bar that carries it, the season button, and the top and
// bottom of select mode. Each takes plain values; `SeriesDownloadsModel`
// holds what they show.

// MARK: A card's corner

/// What a card's corner draws for an episode's download: an arrow, a clock
/// while it waits for the PC, a ring while bytes arrive, an accent tick when
/// it is on the device.
struct DownloadBadgeMark: View {
    @Environment(\.glassAccent) private var accent
    let badge: SeriesDownloads.Badge
    var size: CGFloat = 30

    var body: some View {
        ZStack {
            switch badge {
            case .downloaded:
                // On the device: the download mark, never a tick (a tick is watched).
                DownloadedMark(size: size)
            case .failed:
                Circle().fill(.clear).glassPanel(Circle())
                Image(systemName: "exclamationmark")
                    .font(.system(size: size * 0.44, weight: .heavy))
                    .foregroundStyle(Color.glassAlert)
            case .none:
                Circle().fill(.clear).glassPanel(Circle())
                Image(systemName: "arrow.down")
                    .font(.system(size: size * 0.44, weight: .bold))
                    .foregroundStyle(.white)
            case .waiting:
                Circle().fill(.clear).glassPanel(Circle())
                Image(systemName: "clock")
                    .font(.system(size: size * 0.44, weight: .bold))
                    .foregroundStyle(.white)
            case .moving(let fraction):
                Circle().fill(.clear).glassPanel(Circle())
                Circle().stroke(Color.white.opacity(0.25), lineWidth: 2.5).padding(size * 0.14)
                Circle().trim(from: 0, to: max(min(fraction, 1), 0.03))
                    .stroke(accent.tint, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .padding(size * 0.14)
                // A tap stops it.
                RoundedRectangle(cornerRadius: 1.5).fill(.white).frame(width: size * 0.26, height: size * 0.26)
            }
        }
        .frame(width: size, height: size)
        .shadow(color: .black.opacity(0.3), radius: 3, y: 1)
    }
}

/// An episode card's corner as its own button, outside the card's press: a
/// tap downloads at once, stops one on its way, or tries a failed one again.
struct DownloadBadgeButton: View {
    let id: String
    let badge: SeriesDownloads.Badge
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            DownloadBadgeMark(badge: badge)
                // A finger's room, not just the mark's.
                .frame(width: 44, height: 44)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        // A tick is a state, not a control: it says so, and a tap plays the card as it did.
        .allowsHitTesting(badge != .downloaded)
        .accessibilityLabel(badge.label)
        .accessibilityIdentifier("download-" + id)
    }
}

/// A card's tick circle in select mode: filled when ticked, an empty ring
/// when it can be, a dimmed tick when the episode is here or on its way.
struct SelectTick: View {
    @Environment(\.glassAccent) private var accent
    let ticked: Bool
    let possible: Bool
    var size: CGFloat = 28

    var body: some View {
        ZStack {
            if ticked {
                Circle().fill(accent.tint)
                Image(systemName: "checkmark").font(.system(size: size * 0.42, weight: .heavy)).foregroundStyle(.white)
            } else if possible {
                Circle().fill(Color.black.opacity(0.35))
                Circle().strokeBorder(.white.opacity(0.9), lineWidth: 2)
            } else {
                // Here or on its way already: the download mark, dimmed, nothing to tick.
                DownloadedMark(size: size).opacity(0.6)
            }
        }
        .frame(width: size, height: size)
        .padding(8)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

// MARK: The storage bar

/// The device's storage as one line (#48): other apps, JellyHub, what is
/// coming or being added in the theme accent, and free. The same bar is in the
/// transient bar, the choices panel and select mode.
struct StorageBarView: View {
    @Environment(\.glassAccent) private var accent
    let bar: StorageBar
    var height: CGFloat = 8

    var body: some View {
        GeometryReader { proxy in
            let widths = bar.widths(over: Double(proxy.size.width))
            HStack(spacing: 0) {
                segment(.other, widths, Color.white.opacity(0.30))
                segment(.app, widths, Color.white.opacity(0.74))
                segment(.coming, widths, accent.tint)
                segment(.free, widths, Color.white.opacity(0.10))
            }
            .clipShape(Capsule())
        }
        .frame(height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Storage")
        .accessibilityValue(bar.words)
    }

    private func segment(_ part: StorageBar.Part, _ widths: [StorageBar.Part: Double], _ color: Color) -> some View {
        Rectangle().fill(color).frame(width: CGFloat(max(widths[part] ?? 0, 0)))
    }
}

/// What the colours mean, for the panel.
struct StorageBarLegend: View {
    @Environment(\.glassAccent) private var accent

    var body: some View {
        HStack(spacing: 14) {
            key("Other apps", Color.white.opacity(0.30))
            key("JellyHub", Color.white.opacity(0.74))
            key("Adding", accent.tint)
            key("Free", Color.white.opacity(0.10))
        }
        .font(HubType.body(11.5, relativeTo: .caption2))
        .foregroundStyle(.white.opacity(0.62))
        .accessibilityHidden(true)
    }

    private func key(_ words: String, _ color: Color) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(words)
        }
    }
}

/// The bar that rises when a download starts, says "2 coming · 1 on this
/// iPad" over the storage bar, links to Downloads, and fades about three
/// seconds after the last one finishes.
struct DownloadBar: View {
    @Environment(\.openRoute) private var openRoute
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var offline = OfflineLibrary.shared
    let scope: String

    /// A phone turned sideways has little height: the words, the bar and the link on one line.
    private var oneLine: Bool { verticalSizeClass == .compact }

    var body: some View {
        let counts = offline.counts(scope: scope)
        let words = Text(StorageBarWords.line(coming: counts.coming, onDevice: counts.onDevice, device: deviceWord))
            .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
            .foregroundStyle(.white)
            .monospacedDigit()
            .lineLimit(1)
        Group {
            if oneLine {
                HStack(spacing: 14) {
                    words.accessibilityIdentifier("storage-bar-line")
                    StorageBarView(bar: offline.storageBar()).frame(minWidth: 90)
                    link
                }
            } else {
                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 10) {
                        words.accessibilityIdentifier("storage-bar-line")
                        Spacer(minLength: 8)
                        link
                    }
                    StorageBarView(bar: offline.storageBar())
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, oneLine ? 9 : 14)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .frame(maxWidth: 560)
        .padding(.horizontal, metrics.margin)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
    }

    private var link: some View {
        Button {
            openRoute(.offlineQueue)
        } label: {
            HStack(spacing: 3) {
                Text("Downloads")
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold))
            }
            .font(HubType.body(13.5, weight: .bold, relativeTo: .subheadline))
            .foregroundStyle(.white.opacity(0.85))
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("storage-bar-downloads")
    }

    private var deviceWord: String {
        #if os(iOS)
        SeriesDownloads.deviceWord(idiom: UIDevice.current.userInterfaceIdiom == .pad ? "pad" : "phone")
        #else
        SeriesDownloads.deviceWord(idiom: "mac")
        #endif
    }
}

/// Puts the bar at the bottom of a title's page while its downloads are on
/// their way and a few seconds after (`StorageBarVisibility`). Select mode has
/// its own bottom bar, which carries the storage bar too.
struct DownloadBarHost: ViewModifier {
    @State private var offline = OfflineLibrary.shared
    @State private var visibility = StorageBarVisibility()
    @State private var visible = false
    let scope: String
    var suppressed = false

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if visible && !suppressed {
                    DownloadBar(scope: scope)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("storage-bar")
                }
            }
            .task(id: offline.counts(scope: scope).coming) { await follow() }
    }

    private func follow() async {
        #if DEBUG
        // HUB_BAR_PINNED=1 keeps it up, for measuring where it sits against the page.
        if ProcessInfo.processInfo.environment["HUB_BAR_PINNED"] == "1" {
            visible = true
            return
        }
        #endif
        let coming = offline.counts(scope: scope).coming
        visibility.update(coming: coming, now: Date())
        withAnimation(.snappy(duration: 0.3)) { visible = visibility.isVisible(at: Date()) }
        guard let next = visibility.nextChange else { return }
        let wait = next.timeIntervalSinceNow
        if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
        guard !Task.isCancelled else { return }
        withAnimation(.easeOut(duration: 0.5)) { visible = visibility.isVisible(at: Date()) }
    }
}

extension View {
    /// The storage bar for the downloads of `scope`, a film's or a series' id.
    func downloadBar(scope: String, suppressed: Bool = false) -> some View {
        modifier(DownloadBarHost(scope: scope, suppressed: suppressed))
    }
}

// MARK: After the season pills

/// "Season 2 · 4.9 GB" gets every episode of the season not here yet; when
/// there is nothing left it reads "Season 2 on this iPad".
struct SeasonDownloadButton: View {
    let words: String
    let done: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                Image(systemName: done ? DownloadedMark.symbol : "arrow.down").font(.system(size: 12.5, weight: .bold))
                    .accessibilityHidden(true)
                Text(words).font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
            }
            .lineLimit(1)
            .padding(.horizontal, 15)
            .padding(.vertical, 9)
            .foregroundStyle(.white.opacity(done ? 0.7 : 1))
            .background(Capsule().fill(.clear).glassPanel(Capsule()))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .disabled(done)
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
        .accessibilityLabel(words)
        .accessibilityIdentifier("download-season")
    }
}

// MARK: Select mode

/// The top of select mode: Cancel, how many are ticked, and Select season
/// (Unselect season when the season is all ticked).
struct SelectTopBar: View {
    let count: Int
    let seasonAllTicked: Bool
    let cancel: () -> Void
    let toggleSeason: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            ChoicePill(title: "Cancel", selected: false, action: cancel)
                .accessibilityIdentifier("select-cancel")
            Spacer(minLength: 6)
            Text(SeriesDownloads.selectedWords(count))
                .font(HubType.body(16, weight: .bold, relativeTo: .headline))
                .foregroundStyle(.white)
                .monospacedDigit()
                .accessibilityIdentifier("select-count")
            Spacer(minLength: 6)
            ChoicePill(title: seasonAllTicked ? "Unselect season" : "Select season", selected: false, action: toggleSeason)
                .accessibilityIdentifier("select-season")
        }
    }
}

/// The bottom of select mode: the total, the storage bar previewing it, and Download.
struct SelectBottomBar: View {
    @Environment(\.glassMetrics) private var metrics
    @State private var offline = OfflineLibrary.shared
    let total: String
    let bytes: Int64
    let canDownload: Bool
    let download: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(total)
                        .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                        .foregroundStyle(.white)
                        .monospacedDigit()
                        .accessibilityIdentifier("select-total")
                    Text(StorageBarWords.preview(adding: bytes, free: offline.barFreeBytes))
                        .font(HubType.body(12, relativeTo: .caption))
                        .foregroundStyle(.white.opacity(0.62))
                }
                Spacer(minLength: 8)
                Button(action: download) {
                    Label("Download", systemImage: "arrow.down")
                }
                .buttonStyle(PrimaryPillStyle())
                .disabled(!canDownload)
                .accessibilityIdentifier("select-download")
            }
            StorageBarView(bar: offline.storageBar(adding: bytes))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .glassPanel(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .frame(maxWidth: 640)
        .padding(.horizontal, metrics.margin)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
    }
}

import HubKit
import SwiftUI

/// The smart choices behind a series' round download button (#48): Keep the
/// next N ready, the rest of the season, everything unwatched, the whole
/// series, or Choose episodes. Each says how many episodes and how big; the
/// storage bar under them previews what the chosen one adds before Download
/// is pressed. The page shows it as a side panel on an iPad or a Mac and a
/// bottom sheet on an iPhone; this is what is in either.
struct SeriesDownloadPanel: View {
    let downloads: SeriesDownloadsModel
    let close: () -> Void
    let chooseEpisodes: () -> Void

    @State private var offline = OfflineLibrary.shared
    @State private var picked = "keep-ready"
    @State private var count = KeepReady.defaultCount
    @State private var started = false

    var body: some View {
        let choices = downloads.choices(keepReadyCount: count)
        let chosen = choices.first { $0.id == picked } ?? choices[0]
        let keepOn = downloads.keepReadyCount
        VStack(alignment: .leading, spacing: 0) {
            header
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if !downloads.isLoaded {
                        Text("Asking the hub for the episodes…")
                            .font(HubType.body(14, relativeTo: .subheadline))
                            .foregroundStyle(.white.opacity(0.66))
                            .padding(.vertical, 8)
                    }
                    ForEach(choices) { choice in
                        row(choice, keepOn: keepOn)
                    }
                    chooseRow
                    if keepOn != nil {
                        Button(role: .destructive) {
                            downloads.turnOffKeepReady()
                            close()
                        } label: {
                            Label(KeepReady.turnOffWords, systemImage: "pause.circle")
                                .font(HubType.body(14.5, weight: .semibold, relativeTo: .subheadline))
                                .foregroundStyle(Color.glassAlert)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.horizontal, 4)
                                .padding(.vertical, 8)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("keep-ready-off")
                        Text("The episodes already downloaded stay on this device.")
                            .font(HubType.body(12, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.55))
                            .padding(.horizontal, 4)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 4)
                .padding(.bottom, 12)
            }
            .scrollBounceBehavior(.basedOnSize)
            footer(chosen, keepOn: keepOn)
        }
        .foregroundStyle(.white)
        .onAppear { count = downloads.keepReadyCount ?? KeepReady.defaultCount }
    }

    // MARK: Parts

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Download")
                    .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                    .accessibilityIdentifier("download-panel")
                if !downloads.seriesTitle.isEmpty {
                    Text(downloads.seriesTitle)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                        .lineLimit(1)
                }
            }
            Spacer(minLength: 8)
            GlassRoundButton(systemImage: "xmark", label: "Close", size: 38, action: close)
                .accessibilityIdentifier("download-panel-close")
        }
        .padding(.horizontal, 20)
        .padding(.top, 20)
        .padding(.bottom, 12)
    }

    @ViewBuilder private func row(_ choice: SeriesDownloads.Choice, keepOn: Int?) -> some View {
        let isKeepReady: Bool = { if case .keepReady = choice.kind { return true } else { return false } }()
        let selected = picked == choice.id
        let usable = isKeepReady || !choice.isEmpty
        HStack(spacing: 10) {
            Button {
                picked = choice.id
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: selected ? "largecircle.fill.circle" : "circle")
                        .font(.system(size: 20))
                        .foregroundStyle(selected ? Color.white : .white.opacity(0.5))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(choice.title)
                            .font(HubType.body(15.5, weight: .semibold, relativeTo: .body))
                            .lineLimit(2)
                        Text(subtitle(choice, isKeepReady: isKeepReady, keepOn: keepOn))
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.62))
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(!usable)
            .accessibilityAddTraits(selected ? .isSelected : [])
            .accessibilityIdentifier("choice-" + choice.id)
            if isKeepReady { stepper }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay {
            if selected {
                RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(.white.opacity(0.7), lineWidth: 1.5)
            }
        }
        .opacity(usable ? 1 : 0.5)
    }

    /// 1 to 10, one at a time: minus, the number, plus.
    private var stepper: some View {
        HStack(spacing: 0) {
            stepButton("minus", label: "One fewer", enabled: count > KeepReady.range.lowerBound, id: "keep-ready-minus") { count -= 1 }
            Text("\(count)")
                .font(HubType.body(16, weight: .bold, relativeTo: .body))
                .monospacedDigit()
                .frame(minWidth: 26)
                .accessibilityIdentifier("keep-ready-count")
            stepButton("plus", label: "One more", enabled: count < KeepReady.range.upperBound, id: "keep-ready-plus") { count += 1 }
        }
        .background(Capsule().fill(.white.opacity(0.1)))
        .fixedSize()
        .onChange(of: count) { _, _ in picked = "keep-ready" }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Episodes to keep ready")
        .accessibilityValue("\(count)")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: count = KeepReady.clamp(count + 1)
            case .decrement: count = KeepReady.clamp(count - 1)
            @unknown default: break
            }
        }
    }

    private func stepButton(_ symbol: String, label: String, enabled: Bool, id: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .bold))
                .frame(width: 36, height: 34)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.35)
        .accessibilityLabel(label)
        .accessibilityIdentifier(id)
    }

    private func subtitle(_ choice: SeriesDownloads.Choice, isKeepReady: Bool, keepOn: Int?) -> String {
        guard isKeepReady else { return choice.line }
        let state = keepOn.map { "On · keeping \($0) ready" } ?? ""
        let line = choice.isEmpty ? "All \(count) are on this device already" : choice.line
        return state.isEmpty ? line : state + " · " + line
    }

    private var chooseRow: some View {
        Button {
            chooseEpisodes()
        } label: {
            HStack {
                Text("Choose episodes")
                    .font(HubType.body(15.5, weight: .semibold, relativeTo: .body))
                Spacer(minLength: 0)
                Image(systemName: "chevron.right").font(.system(size: 14, weight: .bold)).foregroundStyle(.white.opacity(0.6))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 14)
            .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!downloads.isLoaded)
        .accessibilityIdentifier("choose-episodes")
    }

    private func footer(_ chosen: SeriesDownloads.Choice, keepOn: Int?) -> some View {
        let isKeepReady: Bool = { if case .keepReady = chosen.kind { return true } else { return false } }()
        let adding = chosen.bytes
        return VStack(alignment: .leading, spacing: 8) {
            StorageBarView(bar: offline.storageBar(adding: adding))
            StorageBarLegend()
            Text(StorageBarWords.preview(adding: adding, free: offline.barFreeBytes))
                .font(HubType.body(12.5, relativeTo: .caption))
                .foregroundStyle(.white.opacity(0.7))
                .accessibilityIdentifier("download-preview")
            Button {
                go(chosen, isKeepReady: isKeepReady)
            } label: {
                Text(action(chosen, isKeepReady: isKeepReady, keepOn: keepOn))
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(PrimaryPillStyle())
            .disabled(!downloads.isLoaded || started || disabledAction(chosen, isKeepReady: isKeepReady, keepOn: keepOn))
            .accessibilityIdentifier("download-panel-go")
            .padding(.top, 4)
        }
        .padding(.horizontal, 20)
        .padding(.top, 8)
        .padding(.bottom, 22)
    }

    private func action(_ chosen: SeriesDownloads.Choice, isKeepReady: Bool, keepOn: Int?) -> String {
        if isKeepReady {
            guard let keepOn else { return "Keep the next \(count) ready" }
            return keepOn == count ? "Keep ready is on" : "Keep \(count) ready instead"
        }
        return "Download \(chosen.episodes.count) episode\(chosen.episodes.count == 1 ? "" : "s")"
    }

    private func disabledAction(_ chosen: SeriesDownloads.Choice, isKeepReady: Bool, keepOn: Int?) -> Bool {
        isKeepReady ? keepOn == count : chosen.isEmpty
    }

    private func go(_ chosen: SeriesDownloads.Choice, isKeepReady: Bool) {
        started = true
        if isKeepReady {
            downloads.setKeepReady(count)
        } else {
            downloads.start(chosen.ids)
        }
        close()
    }
}

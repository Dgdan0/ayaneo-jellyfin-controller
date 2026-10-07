import HubKit
import SwiftUI

/// Settings › Fonts and licences (#38; Android's row of the same name): the
/// fonts and the software the app is built with, each opening its full licence
/// text from the app's own files (`Resources/Licenses`). `Licences` holds the
/// list, and a test keeps it to the folder.
struct LicencesSettings: View {
    @State private var shown: LicenceEntry?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Licences.note)
                .font(HubType.body(13.5, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.64))
                .padding(.horizontal, 4)
            group("Fonts", Licences.fonts)
            group("Software", Licences.software)
        }
        .sheet(item: $shown) { entry in
            LicenceSheet(entry: entry)
        }
        .onChange(of: shown?.id) { _, id in NSLog("licence shown now %@", id ?? "nil") }
        .onAppear { NSLog("licence page appeared") }
        .onDisappear { NSLog("licence page disappeared") }
        #if DEBUG
        // scripts/mac.sh opens one for a screenshot: HUB_SHEET=licence:figtree.
        .task {
            // After the page has settled: a sheet asked for while it is being put up is dropped.
            guard let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"], sheet.hasPrefix("licence:") else { return }
            try? await Task.sleep(for: .milliseconds(700))
            shown = Licences.all.first { $0.id == String(sheet.dropFirst("licence:".count)) }
        }
        #endif
    }

    private func group(_ title: String, _ entries: [LicenceEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            GlassLabel(text: title).padding(.leading, 4)
            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    Button {
                        NSLog("licence tapped %@", entry.id)
                        shown = entry
                    } label: {
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(entry.name)
                                    .font(HubType.body(15, weight: .semibold, relativeTo: .subheadline))
                                    .foregroundStyle(.white)
                                Text(entry.line)
                                    .font(HubType.body(12.5, relativeTo: .caption))
                                    .foregroundStyle(.white.opacity(0.62))
                                    .multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 8)
                            Image(systemName: "chevron.forward")
                                .font(.footnote.weight(.semibold))
                                .foregroundStyle(.white.opacity(0.5))
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(DashboardRowStyle())
                    .accessibilityLabel("\(entry.name), \(entry.line)")
                    .accessibilityHint("Shows the licence")
                    .accessibilityIdentifier("licence-\(entry.id)")
                    if index < entries.count - 1 {
                        Rectangle().fill(.white.opacity(0.08)).frame(height: 1).padding(.leading, 16)
                    }
                }
            }
            .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
    }
}

/// One licence's full text, from the bundle.
struct LicenceSheet: View {
    let entry: LicenceEntry
    @Environment(\.dismiss) private var dismiss

    /// Where the text is: `Resources/Licenses` is copied flat or as a folder,
    /// depending on how the project was generated, so both are tried.
    static func text(_ file: String) -> String? {
        let url = Bundle.main.url(forResource: file, withExtension: "txt", subdirectory: "Licenses")
            ?? Bundle.main.url(forResource: file, withExtension: "txt")
        return url.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.name)
                        .font(HubType.heading(24, weight: .heavy, relativeTo: .title))
                        .foregroundStyle(.white)
                    Text(entry.terms)
                        .font(HubType.body(13.5, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.66))
                }
                Spacer(minLength: 8)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 40) { dismiss() }
            }
            .padding(.horizontal, 22)
            .padding(.top, 22)
            .padding(.bottom, 14)
            ScrollView {
                Text(Self.text(entry.file) ?? "The licence text is not in this build.")
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.84))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 22)
                    .padding(.bottom, 28)
                    .accessibilityIdentifier("licence-text")
            }
        }
        .presentationBackground { GlassSheetFill() }
        .presentationCornerRadius(28)
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 520)
        #endif
        .preferredColorScheme(.dark)
    }
}

import HubKit
import SwiftUI

/// A licence's own page, by the id `Licences` gives it.
struct LicenceRoute: Hashable {
    let id: String
}

/// Settings › Fonts and licences (#38; Android's row of the same name): the
/// fonts and the software the app is built with, each opening its full licence
/// text from the app's own files (`Resources/Licenses`) on a page of its own,
/// with Back like any page. `Licences` holds the list, and a test keeps it to
/// the folder.
struct LicencesSettings: View {
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(Licences.note)
                .font(HubType.body(13.5, relativeTo: .footnote))
                .foregroundStyle(.white.opacity(0.64))
                .padding(.horizontal, 4)
            group("Fonts", Licences.fonts)
            group("Software", Licences.software)
        }
        #if DEBUG
        // scripts/mac.sh opens one for a screenshot: HUB_SHEET=licence:figtree.
        .task {
            // After the page has settled, so the push is not lost to the pane's own appearing.
            guard let sheet = ProcessInfo.processInfo.environment["HUB_SHEET"], sheet.hasPrefix("licence:") else { return }
            try? await Task.sleep(for: .milliseconds(700))
            openRoute(.licence(LicenceRoute(id: String(sheet.dropFirst("licence:".count)))))
        }
        #endif
    }

    private func group(_ title: String, _ entries: [LicenceEntry]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            GlassLabel(text: title).padding(.leading, 4)
            VStack(spacing: 0) {
                ForEach(Array(entries.enumerated()), id: \.element.id) { index, entry in
                    NavigationLink(value: AppRoute.licence(LicenceRoute(id: entry.id))) {
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
                    .padFocusable("licence-\(entry.id)", ring: .inside(12)) { openRoute(.licence(LicenceRoute(id: entry.id))) }
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
struct LicenceView: View {
    let route: LicenceRoute
    @Environment(\.glassMetrics) private var metrics

    private var entry: LicenceEntry? { Licences.all.first { $0.id == route.id } }

    /// Where the text is: `Resources/Licenses` is copied flat or as a folder,
    /// depending on how the project was generated, so both are tried.
    static func text(_ file: String) -> String? {
        let url = Bundle.main.url(forResource: file, withExtension: "txt", subdirectory: "Licenses")
            ?? Bundle.main.url(forResource: file, withExtension: "txt")
        return url.flatMap { try? String(contentsOf: $0, encoding: .utf8) }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                PageHeading(title: entry?.name ?? "Licence") {
                    Text(entry?.terms ?? "")
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                Text(entry.flatMap { Self.text($0.file) } ?? "The licence text is not in this build.")
                    .font(.system(size: 12.5, design: .monospaced))
                    .foregroundStyle(.white.opacity(0.84))
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 16)
                    .glassPanel(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .accessibilityIdentifier("licence-text")
            }
            .padding(.horizontal, metrics.margin)
            .padding(.top, 4)
            .padding(.bottom, 30)
        }
        .ambientArtwork("")
    }
}

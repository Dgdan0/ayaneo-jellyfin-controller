import HubKit
import SwiftUI

/// A place the Android app has and this one is still to build: a calm glass
/// card in the shell saying what will be here. Each gets its own issue and is
/// built in Glass from the start (#12).
struct ComingNextView: View {
    let title: String
    let systemImage: String
    let detail: String
    @Environment(\.glassAccent) private var accent

    init(title: String, systemImage: String, detail: String) {
        self.title = title
        self.systemImage = systemImage
        self.detail = detail
    }

    /// A section's first page, on the Media side, the Books side, or either
    /// (`side` nil: Notifications and Settings).
    init(side: AppSide?, section: AppSection) {
        let (title, detail) = Self.words(side: side, section: section)
        self.init(title: title, systemImage: side == .books && section == .home ? "book" : section.systemImage,
                  detail: detail)
    }

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.system(size: 26, weight: .semibold))
                .frame(width: 64, height: 64)
                .glassPanel(Circle())
            Text("COMING NEXT")
                .font(HubType.body(12.5, weight: .bold, relativeTo: .caption))
                .tracking(1.8)
                .foregroundStyle(accent.tint)
            Text(title)
                .font(HubType.heading(30, weight: .heavy, relativeTo: .title))
                .multilineTextAlignment(.center)
            Text(detail)
                .font(HubType.body(16))
                .foregroundStyle(.white.opacity(0.72))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 28)
        .padding(.vertical, 30)
        .frame(maxWidth: 520)
        .glassPanel(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }

    private static func words(side: AppSide?, section: AppSection) -> (String, String) {
        if side == .books {
            switch section {
            case .home: return ("Books", "The book you are reading, your series, and comics and manga, from Kavita and Storyteller.")
            case .discover: return ("Discover books", "New and wanted books, requested through BookKeeprr.")
            case .library: return ("Book libraries", "Series, authors and every book on Kavita and Storyteller.")
            case .downloads: return ("Book downloads", "Books and audiobooks kept on this device to read offline.")
            case .activity: return ("Book activity", "What BookKeeprr, Kavita and Storyteller are working on.")
            default: break
            }
        }
        switch section {
        case .discover: return ("Discover", "Trending and upcoming titles, search, and requests to Jellyseerr.")
        case .downloads: return ("Downloads", "Films and episodes kept on this device to watch offline.")
        case .activity: return ("Activity", "Transfers, what needs attention, upcoming releases and disks.")
        case .notifications: return ("Notifications", "Service history and current health warnings, from Sonarr, Radarr, Bazarr and the reading services.")
        case .settings: return ("Settings", "Appearance, playback and subtitle preferences.")
        default: return (section.title, "")
        }
    }
}

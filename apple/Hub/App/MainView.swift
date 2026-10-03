import SwiftUI

/// The app's places, in the Android app's order (`HubActivity.sectionTitles`).
enum AppSection: String, Hashable, CaseIterable {
    case home, discover, library, downloads, activity, notifications, services, settings
}

/// A sidebar on iPad and Mac, tabs on iPhone. `sidebarAdaptable` decides by the
/// width the window actually has, not by the device: an iPad mini in portrait,
/// or an iPad app in Split View, gets the tab bar like a phone.
struct MainView: View {
    @SceneStorage("section") private var section: AppSection = .home

    var body: some View {
        TabView(selection: $section) {
            Tab("Home", systemImage: "house", value: .home) {
                NavigationStack { HomeView() }
            }
            Tab("Discover", systemImage: "sparkles", value: .discover) {
                ComingNextView(title: "Discover", systemImage: "sparkles",
                               detail: "Trending and upcoming titles, search, and requests to Jellyseerr.")
            }
            Tab("Library", systemImage: "square.grid.2x2", value: .library) {
                NavigationStack { LibraryView() }
            }
            Tab("Downloads", systemImage: "arrow.down.circle", value: .downloads) {
                ComingNextView(title: "Downloads", systemImage: "arrow.down.circle",
                               detail: "Films and episodes kept on this device to watch offline.")
            }
            Tab("Activity", systemImage: "gauge.with.dots.needle.33percent", value: .activity) {
                ComingNextView(title: "Activity", systemImage: "gauge.with.dots.needle.33percent",
                               detail: "Transfers, what needs attention, upcoming releases and disks.")
            }
            TabSection("Hub") {
                Tab("Notifications", systemImage: "bell", value: AppSection.notifications) {
                    ComingNextView(title: "Notifications", systemImage: "bell",
                                   detail: "Sonarr, Radarr and Bazarr history with current health warnings.")
                }
                Tab("Services", systemImage: "server.rack", value: AppSection.services) {
                    NavigationStack { ServicesView() }
                }
                Tab("Settings", systemImage: "gearshape", value: AppSection.settings) {
                    ComingNextView(title: "Settings", systemImage: "gearshape",
                                   detail: "Appearance, playback and subtitle preferences.")
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        #if DEBUG
        // scripts/mac.sh opens a chosen section for screenshots (HUB_SECTION=library).
        .onAppear {
            if let name = ProcessInfo.processInfo.environment["HUB_SECTION"], let chosen = AppSection(rawValue: name) {
                section = chosen
            }
        }
        #endif
    }
}

/// A place that exists in the Android app and is still to be built here.
struct ComingNextView: View {
    let title: String
    let systemImage: String
    let detail: String

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label(title, systemImage: systemImage)
                    .font(HubType.heading(26))
            } description: {
                Text(detail)
                    .font(HubType.body(16))
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Color.surface)
            .navigationTitle(title)
        }
    }
}

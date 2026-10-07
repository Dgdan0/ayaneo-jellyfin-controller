import Foundation
import HubKit
import Observation

/// Which rows Home shows and in what order (#35): chosen in Settings › Home,
/// kept on this device, and read by Home as it draws. One for the app, so a
/// change in Settings is on Home the next time it is looked at without either
/// page knowing the other.
@MainActor
@Observable
final class HomeLayoutModel {
    static let shared = HomeLayoutModel()

    private(set) var layout = HomeLayout()
    /// The libraries Settings can add a row of, as the hub last listed them.
    private(set) var libraries: [HomeLibrary] = []

    /// The demo hub keeps its choices for the run only, as `AccentModel` does.
    @ObservationIgnored private var memory = false
    @ObservationIgnored private var loadedDemo: Bool?

    /// Reads the layout kept on this device, once per kind of hub.
    func use(demo: Bool) {
        guard loadedDemo != demo else { return }
        loadedDemo = demo
        memory = demo
        layout = demo ? HomeLayout() : HomeRowSettings.layout()
    }

    func setLibraries(_ list: [HomeLibrary]) {
        if list != libraries { libraries = list }
    }

    /// The lines Settings lists.
    var rows: [HomeSettingsRow] { HomeLayoutEditor.rows(layout, libraries: libraries) }

    func setShown(_ id: String, _ shown: Bool) {
        change(HomeLayoutEditor.setShown(layout, libraries: libraries, id: id, shown: shown))
    }

    func move(_ id: String, by delta: Int) {
        change(HomeLayoutEditor.move(layout, libraries: libraries, id: id, by: delta))
    }

    private func change(_ next: HomeLayout) {
        guard next != layout else { return }
        layout = next
        if !memory { HomeRowSettings.save(next) }
    }
}

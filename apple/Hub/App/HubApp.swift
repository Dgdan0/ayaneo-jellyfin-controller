import HubKit
import SwiftUI

@main
struct HubApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .font(HubType.body())
                .tint(.accentColor)
        }
        #if os(macOS)
        .defaultSize(width: 1280, height: 820)
        #endif
    }
}

/// The first run asks for the hub; after that, the app.
struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.isConfigured {
            MainView()
        } else {
            WelcomeView()
        }
    }
}

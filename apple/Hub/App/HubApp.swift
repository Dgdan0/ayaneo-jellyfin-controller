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
                // Glass is always dark: the page is the artwork's own dark
                // colour under its blurred picture (GLASS_PLAN.md).
                .preferredColorScheme(.dark)
        }
        #if os(macOS)
        .defaultSize(width: 1280, height: 820)
        // The Glass page runs to the top of the window, the window buttons over
        // it, as the bars float over it on an iPad (the shell keeps clear of them).
        .windowStyle(.hiddenTitleBar)
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

import HubKit
import SwiftUI

/// A performer's films and series (Android `PersonScreen`): their portrait and
/// name, how many credits and what they are known for, newest first or by
/// popularity, then the posters. The hub leaves out chat-show appearances and
/// repeats; it knows only which credits are in the library.
struct PersonView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    let route: PersonRoute

    @State private var person: PersonResponse?
    @State private var status = StatusMessage("")
    @State private var byPopularity = false
    @State private var lit: String?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .center, spacing: 16) {
                    if let profile = person?.profile, !profile.isEmpty {
                        ArtworkView(path: profile, width: 240)
                            .frame(width: 72, height: 72)
                            .clipShape(Circle())
                            .shadow(color: .black.opacity(0.35), radius: 11, y: 8)
                    }
                    PageHeading(title: person?.name ?? route.name) {
                        StatusLine(message: status) { Task { await load() } }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                Button {
                    byPopularity.toggle()
                } label: {
                    Label(byPopularity ? "By popularity" : "Newest first",
                          systemImage: byPopularity ? "flame" : "calendar")
                }
                .buttonStyle(GlassControlStyle())
                .accessibilityHint("Changes the order")
                .padding(.horizontal, metrics.margin)
                .padding(.top, 14)
                LazyVGrid(columns: [GridItem(.adaptive(minimum: metrics.small ? 100 : 112, maximum: 180),
                                             spacing: metrics.small ? 12 : 18, alignment: .top)],
                          alignment: .leading, spacing: 20) {
                    ForEach(person?.credits ?? [], id: \.media.key) { hit in
                        NavigationLink(value: hit.route) {
                            DiscoverPoster(hit: model.requested.apply(hit), caption: true)
                        }
                        .buttonStyle(GlassCardStyle())
                        .previewsWhenFocused { lit = hit.pageArtwork }
                    }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 16)
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork(lit ?? person?.credits.first?.pageArtwork ?? "")
        .refreshable { await load() }
        .task(id: byPopularity) { await load() }
    }

    private func load() async {
        status = StatusText.loading("credits", refreshing: person != nil)
        do {
            let response = try await model.hub.fetch(HubEndpoints.person(id: route.id, byPopularity: byPopularity),
                                                     as: PersonResponse.self)
            person = response
            status = StatusText.loaded(PersonLines.status(response), cache: response.cache)
        } catch {
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: person != nil, canRetry: false)
        }
    }
}

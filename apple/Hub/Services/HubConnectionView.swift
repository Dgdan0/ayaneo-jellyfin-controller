import HubKit
import SwiftUI

/// Where the hub is and the token this device uses. Android's
/// `HubConnectionScreen`: save first, then test, and never put the stored
/// token back into the field.
struct HubConnectionView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var onConnected: () -> Void = {}
    /// The first run shows this inside the welcome screen, with no Cancel.
    var embedded = false

    @State private var address = ""
    @State private var tvAddress = ""
    @State private var token = ""
    @State private var result = StatusMessage("")
    @State private var testing = false
    @FocusState private var focus: Field?

    private enum Field { case address, token, tv }

    var body: some View {
        Form {
            Section {
                TextField("Address", text: $address, prompt: Text("https://your-pc.your-tailnet.ts.net"))
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($focus, equals: .address)
                    .submitLabel(.next)
                    .onSubmit { focus = .token }
            } header: {
                Text("Ayaneo Hub address")
            } footer: {
                Text("The hub's Tailscale address. This device needs Tailscale, signed in to the same tailnet as the media PC.")
            }

            Section {
                SecureField("Access token", text: $token,
                            prompt: Text(model.hasToken ? "Stored token · leave blank to keep it" : "Paste the HUB_TOKEN value"))
                    .textContentType(.password)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .focused($focus, equals: .token)
                    .submitLabel(.go)
                    .onSubmit { Task { await saveAndTest() } }
            } header: {
                Text("Hub access token")
            } footer: {
                Text("One token per device, issued on the media PC with hubctl token new --label ipad-pro. It is kept in this device's Keychain.")
            }

            #if os(iOS)
            if !embedded {
            Section {
                TextField("Address for TV playback", text: $tvAddress, prompt: Text("https://your-hub.duckdns.org:55886"))
                    .textContentType(.URL)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .focused($focus, equals: .tv)
                    .accessibilityIdentifier("hub-tv-address")
            } header: {
                Text("TV playback")
            } footer: {
                Text(tvFooter)
            }
            }
            #endif

            Section {
                Button {
                    Task { await saveAndTest() }
                } label: {
                    HStack {
                        Text("Save and test")
                        if testing {
                            Spacer()
                            ProgressView().controlSize(.small)
                        }
                    }
                }
                .disabled(testing)
                .keyboardShortcut(.defaultAction)
                if !result.text.isEmpty {
                    Text(result.text)
                        .foregroundStyle(Color.status(result.tone))
                }
            }
        }
        .formStyle(.grouped)
        .navigationTitle("Hub connection")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            if !embedded {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .onAppear {
            // A new device starts with the media PC's address; it only pastes
            // its own token.
            address = model.address.isEmpty ? HubEndpoints.suggestedAddress : model.address
            tvAddress = model.tvAddress
            focus = model.hasToken ? .address : .token
        }
    }

    /// What the TV address is for, and what happens without one.
    private var tvFooter: String {
        let fallback = CastAddress.problem(base: HubEndpoints.normaliseBase(address)) == nil
            ? "Empty, the TV uses the hub address above."
            : "Needed to cast: the TV is not on the tailnet, so it cannot use the hub address above."
        return "A public HTTPS address of the same hub, which a Chromecast or Google TV fetches the video from. " + fallback
    }

    private func saveAndTest() async {
        let normalised = HubEndpoints.normaliseBase(address)
        let effective = HubConnectionValidation.effectiveToken(stored: model.storedToken(), entered: token)
        if let error = HubConnectionValidation.error(normalizedAddress: normalised, token: effective) {
            result = StatusMessage(error, tone: .error)
            return
        }
        let tv = tvAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        if !tv.isEmpty, let problem = CastAddress.problem(base: HubEndpoints.normaliseBase(tv)) {
            result = StatusMessage(problem, tone: .error)
            return
        }
        testing = true
        defer { testing = false }
        model.saveTVAddress(tv)
        tvAddress = model.tvAddress
        await model.saveConnection(address: normalised, token: effective)
        address = normalised
        token = ""
        result = StatusMessage("Testing connection…")
        do {
            _ = try await model.hub.fetch(HubEndpoints.health, as: HealthResponse.self)
            result = StatusMessage("Connected. This address is now saved.")
            onConnected()
            if !embedded { dismiss() }
        } catch {
            result = error.kind == .cancelled
                ? StatusMessage("Saved. The connection test was interrupted; select Save and test to try again.")
                : StatusMessage("Saved, but the test failed: \(error.message)", tone: .error)
        }
    }
}

/// The first run: what the app is, and where to connect.
struct WelcomeView: View {
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    VStack(spacing: 14) {
                        HubMark(size: 72)
                        Text("Connect to your hub")
                            .font(HubType.heading(32))
                            .foregroundStyle(Color.ink)
                        Text("Jellyfin, Jellyseerr, the *arrs and qBittorrent, through the Ayaneo Hub on your media PC.")
                            .font(HubType.body(17))
                            .foregroundStyle(Color.muted)
                            .multilineTextAlignment(.center)
                    }
                    .padding(.top, 32)
                    HubConnectionView(embedded: true)
                        .frame(minHeight: 520)
                        .scrollDisabled(true)
                }
                .frame(maxWidth: 560)
                .padding(.horizontal, 20)
                .frame(maxWidth: .infinity)
            }
            .background(Color.surface)
        }
    }
}

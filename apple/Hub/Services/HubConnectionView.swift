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
    @State private var token = ""
    @State private var result = StatusMessage("")
    @State private var testing = false
    @FocusState private var focus: Field?

    private enum Field { case address, token }

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
            address = model.address
            focus = model.hasToken ? .address : .token
        }
    }

    private func saveAndTest() async {
        let normalised = HubEndpoints.normaliseBase(address)
        let effective = HubConnectionValidation.effectiveToken(stored: model.storedToken(), entered: token)
        if let error = HubConnectionValidation.error(normalizedAddress: normalised, token: effective) {
            result = StatusMessage(error, tone: .error)
            return
        }
        testing = true
        defer { testing = false }
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

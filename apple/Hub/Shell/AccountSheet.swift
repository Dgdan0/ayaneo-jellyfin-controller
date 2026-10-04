import HubKit
import SwiftUI

/// The avatar's sheet. Where the sections are a tab bar (`places`: an iPhone,
/// an iPad mini in portrait) it is the account sheet: who is watching, the
/// profiles to switch to, and rows for Notifications, Services and Settings,
/// which have no icons of their own there. With the top capsule (iPad, Mac)
/// it is "Who is watching?" with the profiles large.
struct AccountSheet: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.glassAccent) private var accent
    let shell: ShellModel
    let places: Bool
    let open: (AppSection) -> Void

    @State private var status = StatusMessage("")

    private var current: HubUser? { Profiles.current(shell.users, chosen: model.userId) }
    private var avatarSize: CGFloat { places ? 56 : 104 }

    var body: some View {
        sheet
            .presentationBackground { GlassSheetFill() }
            .presentationCornerRadius(places ? 32 : 28)
            #if os(iOS)
            .presentationDetents(places ? [.medium, .large] : [.large])
            #else
            .frame(minWidth: 560)
            #endif
            .task { await reload() }
    }

    /// The account sheet scrolls: a phone's half-height sheet with large text
    /// can be shorter than it. "Who is watching?" is sized to what it holds
    /// instead, which a scroll view would hide from the sizing (on an iPad
    /// mini that left a sliver with the rows cut off).
    @ViewBuilder private var sheet: some View {
        if places {
            ScrollView { content }
                .scrollBounceBehavior(.basedOnSize)
        } else {
            content
                .presentationSizing(.form.fitted(horizontal: false, vertical: true))
        }
    }

    private var content: some View {
        VStack(alignment: places ? .leading : .center, spacing: places ? 16 : 22) {
            header
            LazyVGrid(columns: [GridItem(.adaptive(minimum: avatarSize), spacing: places ? 12 : 22,
                                         alignment: places ? .leading : .center)],
                      alignment: places ? .leading : .center, spacing: 14) {
                ForEach(Profiles.ordered(shell.users)) { user in tile(user) }
            }
            if !places {
                Text("Each person has their own Continue watching, Next up and progress.")
                    .font(HubType.body(13, relativeTo: .footnote))
                    .foregroundStyle(.white.opacity(0.6))
                    .multilineTextAlignment(.center)
            }
            StatusLine(message: status) { Task { await reload() } }
            if places { placeRows }
        }
        .padding(.horizontal, 22)
        .padding(.top, 22)
        .padding(.bottom, 30)
    }

    @ViewBuilder private var header: some View {
        if places {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 5) {
                    Text(current?.name ?? (model.userName.isEmpty ? "Profiles" : model.userName))
                        .font(HubType.heading(23, weight: .heavy, relativeTo: .title2))
                    Text("Watching as")
                        .font(HubType.body(13, weight: .medium, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.65))
                }
                Spacer(minLength: 0)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 40) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
        } else {
            ZStack(alignment: .topTrailing) {
                Text("Who is watching?")
                    .font(HubType.heading(30, weight: .heavy, relativeTo: .title))
                    .frame(maxWidth: .infinity)
                    .padding(.top, 6)
                GlassRoundButton(systemImage: "xmark", label: "Close", size: 40) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
        }
    }

    private func tile(_ user: HubUser) -> some View {
        let isCurrent = user.id == current?.id
        let color = Profiles.color(of: user.id, in: shell.users).map(Color.init(argb:)) ?? .white.opacity(0.4)
        let radius = avatarSize * 0.28
        return Button {
            Task { await choose(user) }
        } label: {
            VStack(spacing: 8) {
                Text(Profiles.initial(user.name))
                    .font(HubType.heading(avatarSize * 0.44, weight: .heavy, relativeTo: .title))
                    .foregroundStyle(Color.glassInk)
                    .frame(width: avatarSize, height: avatarSize)
                    .background(color, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
                    .overlay {
                        if isCurrent {
                            RoundedRectangle(cornerRadius: radius + 4, style: .continuous)
                                .strokeBorder(accent.tint, lineWidth: 3)
                                .padding(-4)
                        }
                    }
                    .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
                Text(user.name)
                    .font(HubType.body(places ? 12.5 : 15, weight: .bold, relativeTo: .footnote))
                    .lineLimit(1)
                if isCurrent && !places {
                    Text("Watching now")
                        .font(HubType.body(12, weight: .bold, relativeTo: .caption))
                        .foregroundStyle(accent.tint)
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isCurrent ? "\(user.name), watching now" : user.name)
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
        #if os(iOS)
        .hoverEffect(.lift)
        #endif
    }

    private var placeRows: some View {
        let attention = shell.attention
        return VStack(spacing: 0) {
            PlaceRow(section: .notifications,
                     value: attention == 0 ? "" : "\(attention) need\(attention == 1 ? "s" : "") attention") {
                open(.notifications)
            }
            Divider().overlay(Color.white.opacity(0.08))
            PlaceRow(section: .services, value: shell.servicesSummary) { open(.services) }
            Divider().overlay(Color.white.opacity(0.08))
            PlaceRow(section: .settings, value: "") { open(.settings) }
        }
        .glassPanel(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private func reload() async {
        if shell.users.isEmpty { status = StatusText.loading("profiles", refreshing: false) }
        if let failure = await shell.loadUsers(model.hub) {
            if failure.kind != .cancelled {
                status = StatusText.failed(failure.message, kind: failure.kind, hasData: !shell.users.isEmpty)
            }
        } else {
            status = shell.users.isEmpty ? StatusMessage("No enabled Jellyfin users were found") : StatusMessage("")
        }
        // The Services row's "All 13 running", for the sheet's own reading.
        if places { await shell.refreshServices(model.hub, address: model.address) }
    }

    private func choose(_ user: HubUser) async {
        await model.selectUser(id: user.id, name: user.name)
        dismiss()
    }
}

/// A place reached from the iPhone's sheet: its icon on a small glass tile,
/// the name, what it has to say ("17 need attention", "All 13 running").
private struct PlaceRow: View {
    let section: AppSection
    let value: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: section.systemImage)
                    .font(.system(size: 15, weight: .semibold))
                    .frame(width: 34, height: 34)
                    .glassPanel(RoundedRectangle(cornerRadius: 10, style: .continuous))
                Text(section.title)
                    .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                Spacer(minLength: 8)
                if !value.isEmpty {
                    Text(value)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                }
                Image(systemName: "chevron.right")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.5))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

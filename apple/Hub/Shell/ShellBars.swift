import HubKit
import SwiftUI

// The Glass shell's bars (GLASS_PLAN.md, "Navigation per device"). Sizes are
// the prototype's: `.tabs`, `.seg2`, `.ibtn`, `.avatar`, `.backpill` and
// `.tabbar`.

/// The iPad's and the Mac's bar: Media/Books or the back pill on the left,
/// the sections in a capsule centred on the window, then Notifications,
/// Services and Settings as round icons and the profile's avatar.
struct WideBar: View {
    @Binding var side: AppSide
    let section: AppSection
    /// The page under this one, when a page is pushed.
    let backTitle: String?
    let attention: Int
    let avatar: AvatarLook
    let select: (AppSection) -> Void
    let back: () -> Void
    let openProfiles: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            HStack(spacing: 10) {
                if let backTitle {
                    BackPill(title: backTitle, action: back)
                } else {
                    SidePicker(side: $side)
                }
                Spacer(minLength: 0)
                GlassRoundButton(systemImage: "bell", label: "Notifications", on: section == .notifications,
                                 size: 44, count: attention) { select(.notifications) }
                GlassRoundButton(systemImage: "server.rack", label: "Services", on: section == .services,
                                 size: 44) { select(.services) }
                GlassRoundButton(systemImage: "gearshape", label: "Settings", on: section == .settings,
                                 size: 44) { select(.settings) }
                AvatarButton(look: avatar, size: 44, action: openProfiles)
            }
            SectionCapsule(section: section, select: select)
        }
    }
}

/// The iPhone's top bar: Media/Books on a section's first page, a round back
/// button above it; the bell (first pages only) and the avatar on the right.
struct PhoneBar: View {
    @Binding var side: AppSide
    let section: AppSection
    let backTitle: String?
    let attention: Int
    let avatar: AvatarLook
    let select: (AppSection) -> Void
    let back: () -> Void
    let openProfiles: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if let backTitle {
                GlassRoundButton(systemImage: "chevron.left", label: "Back to \(backTitle)", size: 40, action: back)
                    .keyboardShortcut("[", modifiers: .command)
            } else {
                SidePicker(side: $side, compact: true)
            }
            Spacer(minLength: 0)
            if backTitle == nil {
                GlassRoundButton(systemImage: "bell", label: "Notifications", on: section == .notifications,
                                 size: 40, count: attention) { select(.notifications) }
            }
            AvatarButton(look: avatar, size: 40, action: openProfiles)
        }
    }
}

/// The five sections as a glass capsule; the one shown is a white pill that
/// slides to the next. None is lit while Notifications, Services or Settings
/// is open: their icon is.
struct SectionCapsule: View {
    let section: AppSection
    let select: (AppSection) -> Void
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 2) {
            ForEach(AppSection.sections, id: \.self) { item in
                CapsuleTab(title: item.title, on: item == section, pill: pill) { select(item) }
                    .keyboardShortcut(item.shortcut, modifiers: .command)
            }
        }
        .padding(5)
        .glassPanel(Capsule())
        .animation(.spring(duration: 0.3), value: section)
    }
}

private struct CapsuleTab: View {
    let title: String
    let on: Bool
    let pill: Namespace.ID
    let action: () -> Void
    @State private var hovering = false

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(HubType.chrome(15, weight: .semibold))
                .foregroundStyle(on ? Color.glassInk : .white.opacity(0.82))
                .padding(.vertical, 10)
                .padding(.horizontal, 16)
                .background {
                    if on {
                        Capsule().fill(.white).matchedGeometryEffect(id: "pill", in: pill)
                    } else if hovering {
                        Capsule().fill(.white.opacity(0.12))
                    }
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .onHover { hovering = $0 }
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Media or Books: a segmented glass pill whose chosen side wears that
/// side's accent, teal or gold.
struct SidePicker: View {
    @Binding var side: AppSide
    var compact = false

    var body: some View {
        HStack(spacing: 2) {
            ForEach(AppSide.allCases, id: \.self) { mode in
                let on = mode == side
                let accent = AccentPreset.defaultFor(mode)
                Button {
                    side = mode
                } label: {
                    HStack(spacing: compact ? 6 : 7) {
                        Image(systemName: mode == .media ? "play.rectangle" : "book")
                            .font(.system(size: compact ? 13 : 14, weight: .semibold))
                        Text(mode.label)
                            .font(HubType.chrome(compact ? 13 : 14, weight: .semibold))
                    }
                    .foregroundStyle(on ? accent.inkColor : .white.opacity(0.8))
                    .padding(.horizontal, compact ? 11 : 14)
                    .padding(.vertical, compact ? 8 : 9)
                    .background(on ? accent.tint : .clear, in: Capsule())
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(mode == .media ? "Movies and TV" : "Books and comics")
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .glassPanel(Capsule())
        .animation(.easeInOut(duration: 0.2), value: side)
    }
}

/// Back, named after the page it goes back to ("‹ Library").
struct BackPill: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            // Hugs a short name; a long one stops at the prototype's 260 and
            // ends in an ellipsis.
            HugWidth(maxWidth: 260) {
                HStack(spacing: 4) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 17, weight: .semibold))
                    Text(title)
                        .font(HubType.chrome(15, weight: .bold))
                        .lineLimit(1)
                }
                .padding(.leading, 10)
                .padding(.trailing, 16)
                .frame(height: 44)
            }
            .foregroundStyle(.white)
            .glassPanel(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .keyboardShortcut("[", modifiers: .command)
        .accessibilityLabel("Back to \(title)")
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// Its content's natural width, up to `maxWidth`. A frame with a maximum
/// width would take all the room a row offers it, up to that maximum.
struct HugWidth: Layout {
    let maxWidth: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        let natural = content.sizeThatFits(.unspecified).width
        let width = min(natural, maxWidth, proposal.width ?? .infinity)
        return content.sizeThatFits(ProposedViewSize(width: width, height: proposal.height))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }
}

/// What the avatar shows: the profile's letter on its colour, or a glass
/// circle until the profiles are known.
struct AvatarLook: Equatable {
    var name: String
    var initial: String
    var color: Color?
}

struct AvatarButton: View {
    let look: AvatarLook
    var size: CGFloat = 44
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if let color = look.color {
                    Circle().fill(color)
                } else {
                    Circle().fill(.clear).glassPanel(Circle())
                }
                if look.initial.isEmpty {
                    Image(systemName: "person.fill")
                        .font(.system(size: size * 0.4, weight: .semibold))
                        .foregroundStyle(.white)
                } else {
                    Text(look.initial)
                        .font(HubType.chrome(size * 0.39, weight: .bold))
                        .foregroundStyle(look.color == nil ? .white : Color.glassInk)
                }
            }
            .frame(width: size, height: size)
            .overlay { Circle().strokeBorder(.white.opacity(0.75), lineWidth: 2) }
            .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(look.name.isEmpty ? "Profiles" : "Profiles, watching as \(look.name)")
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// The iPhone's sections: a glass bar floating above the bottom edge, an
/// icon and a word for each, the one shown lit.
struct ShellTabBar: View {
    let section: AppSection
    let select: (AppSection) -> Void

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppSection.sections, id: \.self) { item in
                let on = item == section
                Button {
                    select(item)
                } label: {
                    VStack(spacing: 3) {
                        Image(systemName: item.systemImage)
                            .font(.system(size: 19, weight: .semibold))
                            .frame(height: 24)
                        Text(item.title)
                            .font(HubType.chrome(10.5, weight: .semibold))
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    }
                    .foregroundStyle(on ? Color.white : .white.opacity(0.7))
                    .padding(.vertical, 6)
                    .padding(.horizontal, 4)
                    .frame(minWidth: 62)
                    .background(on ? Color.white.opacity(0.14) : .clear,
                                in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .keyboardShortcut(item.shortcut, modifiers: .command)
                .frame(maxWidth: .infinity)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 66)
        .glassPanel(Capsule())
    }
}

extension AppSection {
    /// ⌘1 to ⌘5 for the five sections, on an iPad's keyboard and the Mac:
    /// the keyboard's way to what L1 and R1 do on the Pocket.
    var shortcut: KeyEquivalent {
        KeyEquivalent(Character(String((Self.sections.firstIndex(of: self) ?? 0) + 1)))
    }
}

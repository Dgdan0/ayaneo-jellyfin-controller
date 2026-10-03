import HubKit
import SwiftUI

// The pieces of the Android app's 2026-10 redesign that Home and a title's page
// share: the artwork that fades into the page behind the words, the accent
// pill for the main action, round icon buttons, and underlined tabs.

/// The top of Home and of a title's page (Android `HomeHeroView` and
/// `DetailHeaderView`'s hero). The artwork sits on the trailing side and fades
/// into the page colour towards the words and the bottom; on a narrow screen
/// it spans the width and fades only downwards. It reaches up under the tabs,
/// so the page has no title bar of its own.
struct BackdropHeader<Content: View>: View {
    let path: String
    /// Room above the words for the status bar, the tabs and the back button.
    let topInset: CGFloat
    @ViewBuilder let content: Content
    @Environment(\.horizontalSizeClass) private var sizeClass

    private var compact: Bool { sizeClass == .compact }

    var body: some View {
        content
            .padding(.horizontal, 24)
            .padding(.top, topInset + (compact ? 150 : 40))
            .frame(maxWidth: .infinity, minHeight: compact ? 0 : 380, alignment: .bottomLeading)
            .background(alignment: .topTrailing) {
                GeometryReader { geometry in
                    let width = compact ? geometry.size.width : geometry.size.width * 0.68
                    ArtworkView(path: path, width: 1920)
                        .frame(width: width, height: geometry.size.height)
                        .overlay {
                            if !compact {
                                LinearGradient(stops: [.init(color: .surface, location: 0),
                                                       .init(color: .surface.opacity(0), location: 0.5)],
                                               startPoint: .leading, endPoint: .trailing)
                            }
                        }
                        .overlay {
                            LinearGradient(stops: [.init(color: .surface.opacity(0), location: compact ? 0.25 : 0.55),
                                                   .init(color: .surface, location: 1)],
                                           startPoint: .top, endPoint: .bottom)
                        }
                        .frame(width: geometry.size.width, alignment: .trailing)
                }
            }
    }
}

/// The page's main action: a filled accent pill ("▶ Resume · 15:00").
struct AccentPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HubType.body(16, weight: .semibold))
            .padding(.horizontal, 22)
            .padding(.vertical, 12)
            .foregroundStyle(Color.accentInk)
            .background(Color.accentColor.opacity(configuration.isPressed ? 0.8 : 1), in: Capsule())
            #if os(iOS)
            .hoverEffect(.lift)
            #endif
    }
}

/// A secondary pill ("ⓘ Details"): the same shape, quiet.
struct SoftPillStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HubType.body(16, weight: .semibold))
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .foregroundStyle(Color.ink)
            .background(Color.muted.opacity(configuration.isPressed ? 0.28 : 0.16), in: Capsule())
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

/// A round action next to the main pill: watched, favourite. Filled accent
/// when on, an outline when off (Android's `MediaActionIconDrawable`).
struct RoundIconButton: View {
    let systemImage: String
    let label: String
    var on = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(on ? Color.accentColor : Color.ink)
                .frame(width: 48, height: 48)
                .background(Color.muted.opacity(0.16), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// Tabs drawn as words with an accent line under the chosen one ("Episodes",
/// "More like this", "Cast", "Details").
struct UnderlineTabs<Tab: Hashable>: View {
    let tabs: [(id: Tab, title: String)]
    @Binding var selection: Tab

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 22) {
                ForEach(tabs, id: \.id) { tab in
                    Button {
                        withAnimation(.easeInOut(duration: 0.15)) { selection = tab.id }
                    } label: {
                        VStack(spacing: 7) {
                            Text(tab.title)
                                .font(HubType.body(16, weight: selection == tab.id ? .semibold : .medium,
                                                   relativeTo: .headline))
                                .foregroundStyle(selection == tab.id ? Color.ink : Color.muted)
                            Rectangle()
                                .fill(selection == tab.id ? Color.accentColor : .clear)
                                .frame(height: 3)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(selection == tab.id ? .isSelected : [])
                }
            }
            Divider()
        }
    }
}

/// A screen whose artwork reaches under the tabs: the bar keeps its back
/// button and actions but draws no background and no title.
struct UnderTheBar: ViewModifier {
    func body(content: Content) -> some View {
        content
            .navigationTitle("")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            #endif
    }
}

extension View {
    func underTheBar() -> some View { modifier(UnderTheBar()) }
}

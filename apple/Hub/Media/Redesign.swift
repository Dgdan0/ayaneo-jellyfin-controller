import HubKit
import SwiftUI

// The pieces Home and a title's page share: the artwork behind the words and
// the underlined tabs. Their buttons are the Glass ones (`Glass/GlassStyle`).

/// The top of Home and of a title's page (Android `HomeHeroView` and
/// `DetailHeaderView`'s hero). The artwork sits on the trailing side and fades
/// out towards the words and the bottom; on a narrow screen it spans the width
/// and fades only downwards. It reaches up under the bar, so the page has no
/// title bar of its own.
///
/// It fades through transparency, not into a colour, so the Glass page shows
/// through where it ends (GLASS_PLAN.md, "Title pages").
struct BackdropHeader<Content: View>: View {
    let path: String
    /// Room above the words for the status bar and the shell's bar.
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
                        .mask {
                            LinearGradient(stops: [.init(color: .black, location: compact ? 0.3 : 0.4),
                                                   .init(color: .clear, location: 0.97)],
                                           startPoint: .top, endPoint: .bottom)
                        }
                        .mask {
                            if compact {
                                Color.black
                            } else {
                                LinearGradient(stops: [.init(color: .clear, location: 0),
                                                       .init(color: .black, location: 0.5)],
                                               startPoint: .leading, endPoint: .trailing)
                            }
                        }
                        .frame(width: geometry.size.width, alignment: .trailing)
                }
            }
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

import HubKit
import SwiftUI

// Glass's pills, capsules, control buttons, tabs and page headings, from the
// prototype's `.pill`, `.capsule`, `.cbtn`, `.utabs` and `.phead`.

/// A pill that picks one of a few, a season or a filter (`.pill`): glass,
/// and white with ink while chosen.
struct ChoicePill: View {
    let title: String
    let selected: Bool
    var systemImage: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 7) {
                if let systemImage {
                    Image(systemName: systemImage).font(.system(size: 13, weight: .semibold))
                }
                Text(title).font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
            }
            .lineLimit(1)
            .padding(.horizontal, 15)
            .padding(.vertical, 9)
            .foregroundStyle(selected ? Color.glassInk : .white)
            .background {
                if selected {
                    Capsule().fill(.white)
                } else {
                    Capsule().fill(.clear).glassPanel(Capsule())
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        #if os(iOS)
        .hoverEffect(.highlight)
        #endif
    }
}

/// One of several places in a glass capsule (`.capsule`), the chosen one a
/// white pill that slides to the next: a library's name among the others.
struct GlassCapsulePicker<ID: Hashable>: View {
    struct Item {
        let id: ID
        let title: String
        var systemImage: String?
    }

    let items: [Item]
    let selection: ID
    let select: (ID) -> Void
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 2) {
            ForEach(items, id: \.id) { item in
                let on = item.id == selection
                Button {
                    select(item.id)
                } label: {
                    HStack(spacing: 6) {
                        if let image = item.systemImage {
                            Image(systemName: image).font(.system(size: 12, weight: .semibold))
                        }
                        Text(item.title).font(HubType.body(14, weight: .semibold, relativeTo: .subheadline))
                    }
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .foregroundStyle(on ? Color.glassInk : .white.opacity(0.8))
                    .background {
                        if on { Capsule().fill(.white).matchedGeometryEffect(id: "pill", in: pill) }
                    }
                    .contentShape(Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .glassPanel(Capsule())
        .animation(.spring(duration: 0.3), value: selection)
    }
}

/// A control beside a page's content (`.cbtn`): Sort, its direction,
/// Favourites, a glass capsule 42 tall.
struct GlassControlStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(HubType.body(14, weight: .bold, relativeTo: .subheadline))
            .lineLimit(1)
            .padding(.horizontal, 15)
            .frame(minHeight: 42)
            .foregroundStyle(.white)
            .glassPanel(Capsule())
            .contentShape(Capsule())
            .brightness(configuration.isPressed ? 0.1 : 0)
            .opacity(isEnabled ? 1 : 0.55)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

/// Tabs drawn as words over a hairline, the chosen one white with the accent
/// under it ("Episodes", "More like this", "Cast", "Details"; `.utabs`).
struct UnderlineTabs<Tab: Hashable>: View {
    let tabs: [(id: Tab, title: String)]
    @Binding var selection: Tab
    @Environment(\.glassAccent) private var accent
    @Namespace private var line

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 26) {
                ForEach(tabs, id: \.id) { tab in
                    let on = selection == tab.id
                    Button {
                        withAnimation(.easeInOut(duration: 0.2)) { selection = tab.id }
                    } label: {
                        Text(tab.title)
                            .font(HubType.body(15, weight: .bold, relativeTo: .headline))
                            .foregroundStyle(on ? Color.white : .white.opacity(0.6))
                            .padding(.vertical, 12)
                            .overlay(alignment: .bottom) {
                                if on {
                                    Capsule().fill(accent.tint)
                                        .frame(height: 3)
                                        .matchedGeometryEffect(id: "line", in: line)
                                }
                            }
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
        }
        .background(alignment: .bottom) {
            Rectangle().fill(.white.opacity(0.14)).frame(height: 1)
        }
    }
}

/// A page's own heading (`.phead`): its name in the display face, and a
/// line under it.
struct PageHeading<Detail: View>: View {
    let title: String
    @ViewBuilder let detail: Detail
    @Environment(\.glassMetrics) private var metrics

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(HubType.heading(metrics.pageTitle, weight: .heavy, relativeTo: .largeTitle))
                .foregroundStyle(.white)
            detail
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The small capitals over a value or a group (`.dl dt`, `.slab`).
struct GlassLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(HubType.body(11, weight: .bold, relativeTo: .caption2))
            .tracking(1.3)
            .foregroundStyle(.white.opacity(0.55))
    }
}

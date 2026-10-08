import HubKit
import SwiftUI

// The modifiers a page uses to be driven by a controller or a keyboard (#46,
// C; APPLE_PLAN.md, "A controller drives the whole app"):
//
//     ScrollView { … }
//         .padPage("books-home")                  // the page, once, round its scroll view
//     ScrollView(.horizontal) { LazyHStack { ForEach(items) { item in
//         card(item).padFocusable(item.id, ring: .card) { open(item) }
//     } } }
//         .padGroup("recent", .row, members: items.map(\.id), strip: true)
//     VStack { … rows … }
//         .padGroup("rows", .column, members: ["hero", "recent"], prefix: false)
//
// An item's id inside a group is `group/id` (its group's `prefix`), so the
// same book in two rows is two places; `id` alone is what its scroll view
// knows it by (a `ForEach` id), so a card not drawn yet is still scrolled to.

extension EnvironmentValues {
    /// The page the views under it register with.
    @Entry var padPage: PadPage?
    /// The group the items under it are members of.
    @Entry var padGroup: PadGroupContext?
    /// The pad's focus is on this item and the ring shows: a card lights as a
    /// pointer resting on it lights it (`GlassCardStyle`, `previewsWhenFocused`).
    @Entry var padLit = false
}

struct PadGroupContext: Equatable {
    let id: String
    let prefix: Bool

    func itemId(_ id: String) -> String { prefix ? "\(self.id)/\(id)" : id }
}

/// How a focused item shows the ring.
enum PadRing: Equatable {
    /// Lit as a card is under a pointer: its artwork's ring and lift (`GlassCardStyle`).
    case card
    case capsule
    case circle
    case rounded(CGFloat)
    /// Inside its bounds, for an item a scroll view would clip (a tab in a strip).
    case inside(CGFloat)
    /// The item draws its own (it reads `padLit`).
    case none
}

extension View {
    /// The page a controller moves on: once, round the page's own scroll view
    /// (which it scrolls), with the key its focus is remembered by. `modal`
    /// for a sheet, which has the keys while it shows.
    func padPage(_ key: String, modal: Bool = false, initial: String? = nil, back: (() -> Void)? = nil) -> some View {
        modifier(PadPageModifier(key: key, modal: modal, initial: initial, back: back))
    }

    /// The shell's bars: one page for the top bar and the iPhone's tab bar.
    func padBar(_ page: PadPage) -> some View {
        modifier(PadBarModifier(page: page))
    }

    /// Items moved through in order (`PadLayout`). `members` are the items'
    /// own ids (prefixed with the group's here, as the items' are) or, with
    /// `prefix: false`, other groups' ids and ids given whole. `strip` for a
    /// horizontal scroll view, which this then scrolls; `scrollIds`, when its
    /// `ForEach` knows its members by something else (their positions). Nil
    /// `id` leaves the view as it is.
    func padGroup(_ id: String?, _ layout: PadLayout, members: [String], prefix: Bool = true,
                  strip: Bool = false, scrollIds: [AnyHashable]? = nil) -> some View {
        modifier(PadGroupModifier(id: id, layout: layout, members: members, prefix: prefix, strip: strip,
                                  scrollIds: scrollIds))
    }

    /// Runs `action` as a controller's or keyboard's focus lands here: inside
    /// (before) the item's `padFocusable`. Notifications reads a row so.
    func onPadFocus(_ action: @escaping () -> Void) -> some View {
        modifier(OnPadFocus(action: action))
    }

    /// An item a controller or a keyboard can focus: Ⓐ or Return runs `press`,
    /// Ⓨ `hold` (its menu). `scroll` is what its scroll view knows it by when
    /// that is not `id`; `scrolls: false` for an item no scroll view holds (a
    /// sheet's picker, a toolbar's button). The item itself is never given an
    /// id or a frame reader (a clear view behind it has them), so a Menu, a
    /// Toggle or a text field behaves as it does without this. Nil `id`
    /// leaves the view as it is.
    func padFocusable(_ id: String?, ring: PadRing = .capsule, scroll: AnyHashable? = nil, scrolls: Bool = true,
                      hold: (() -> Void)? = nil, press: (() -> Void)?) -> some View {
        modifier(PadFocusableModifier(id: id, ring: ring, scroll: scroll, scrolls: scrolls,
                                      actions: PadItemActions(press: press, hold: hold)))
    }

    /// An item with a hold menu (#46, D): `menu` is its context menu under a
    /// long press or a secondary click and, the same choices, Ⓨ's panel.
    func padFocusable(_ id: String?, ring: PadRing = .capsule, scroll: AnyHashable? = nil, scrolls: Bool = true,
                      menu: @escaping () -> PadMenu, press: (() -> Void)?) -> some View {
        contextMenu { PadChoicesMenu(choices: menu().choices) }
            .modifier(PadFocusableModifier(id: id, ring: ring, scroll: scroll, scrolls: scrolls,
                                           actions: PadItemActions(press: press,
                                                                   hold: { PadFocusCenter.shared.present(menu()) })))
    }
}

private struct PadPageModifier: ViewModifier {
    let key: String
    let modal: Bool
    let initial: String?
    let back: (() -> Void)?
    @Environment(\.shellStack) private var stack
    @State private var page: PadPage
    /// A menu shown over the page (`PadFocusCenter.present`).
    @State private var menu: PadMenu?

    init(key: String, modal: Bool, initial: String?, back: (() -> Void)?) {
        self.key = key
        self.modal = modal
        self.initial = initial
        self.back = back
        _page = State(initialValue: PadPage(modal: modal))
    }

    func body(content: Content) -> some View {
        ScrollViewReader { proxy in
            content
                .environment(\.padPage, page)
                .onGeometryChange(for: CGRect.self) { geometry in
                    let frame = geometry.frame(in: .global)
                    let safe = geometry.safeAreaInsets
                    return CGRect(x: frame.minX + safe.leading, y: frame.minY + safe.top,
                                  width: max(0, frame.width - safe.leading - safe.trailing),
                                  height: max(0, frame.height - safe.top - safe.bottom))
                } action: { visible in
                    page.visible = visible
                }
                .onAppear {
                    page.key = key
                    page.stack = modal ? "" : stack
                    page.back = back
                    page.initial = initial
                    page.present = { menu = $0 }
                    page.proxy = proxy
                    PadFocusCenter.shared.appeared(page)
                }
                .onDisappear { PadFocusCenter.shared.disappeared(page) }
                .onChange(of: key) { _, latest in page.key = latest }
                // On a view of its own: a page's own `.sheet` on the same chain
                // (a book's Finished) kept this one from showing.
                .background { Color.clear.sheet(item: $menu) { shown in PadMenuPanel(menu: shown) } }
        }
    }
}

private struct PadBarModifier: ViewModifier {
    let page: PadPage

    func body(content: Content) -> some View {
        content
            .environment(\.padPage, page)
            .onAppear { PadFocusCenter.shared.appeared(page) }
    }
}

private struct PadGroupModifier: ViewModifier {
    let id: String?
    let layout: PadLayout
    let members: [String]
    let prefix: Bool
    let strip: Bool
    let scrollIds: [AnyHashable]?
    @Environment(\.padPage) private var page

    func body(content: Content) -> some View {
        if let id, strip {
            ScrollViewReader { proxy in
                grouped(content, id)
                    .onAppear { page?.stripProxies[id] = proxy }
            }
            // The row as a whole, for the page's scroll view to show.
            .id(id)
        } else if let id {
            grouped(content, id)
        } else {
            content
        }
    }

    private func grouped(_ content: Content, _ id: String) -> some View {
        content
            .environment(\.padGroup, PadGroupContext(id: id, prefix: prefix))
            .onAppear { register(id) }
            .onChange(of: members) { _, _ in register(id) }
            .onDisappear { page?.removeGroup(id) }
    }

    private func register(_ id: String) {
        guard let page else { return }
        let context = PadGroupContext(id: id, prefix: prefix)
        let ids = members.map(context.itemId)
        page.setGroup(PadGroup(id, layout, members: ids), prefix: prefix)
        if let scrollIds {
            for (member, scroll) in zip(ids, scrollIds) { page.scrollIds[member] = scroll }
        }
    }
}

private struct PadFocusableModifier: ViewModifier {
    let id: String?
    let ring: PadRing
    let scroll: AnyHashable?
    let scrolls: Bool
    let actions: PadItemActions
    @Environment(\.padPage) private var page
    @Environment(\.padGroup) private var group

    func body(content: Content) -> some View {
        if let id, let page {
            let padId = group?.itemId(id) ?? id
            let lit = PadFocusCenter.shared.lit(padId, on: page)
            let _ = page.actions[padId] = actions
            let _ = scroll.map { page.scrollIds[padId] = $0 }
            // The item keeps its own identity: its scroll id and its frame are a
            // clear view's behind it. Given to a Menu, a Toggle or a text field
            // itself, they lost a menu's choice and a switch's turn under a
            // finger (the series' ⋯, Settings › Home's switches).
            content
                .environment(\.padLit, lit)
                .overlay { PadRingView(ring: ring, lit: lit) }
                .background {
                    PadAnchor(page: page, padId: padId, scrollId: scrolls ? (scroll ?? AnyHashable(id)) : nil)
                }
        } else {
            content
        }
    }
}

/// Where an item is, for the engine and its scroll view: a clear view the
/// item's size behind it, with the item's scroll id, that measures its frame.
private struct PadAnchor: View {
    let page: PadPage
    let padId: String
    let scrollId: AnyHashable?

    var body: some View {
        anchored
            .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                page.frames[padId] = frame
            }
            .onDisappear { page.frames[padId] = nil }
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }

    @ViewBuilder private var anchored: some View {
        if let scrollId {
            Color.clear.id(scrollId)
        } else {
            Color.clear
        }
    }
}

private struct OnPadFocus: ViewModifier {
    let action: () -> Void
    @Environment(\.padLit) private var lit

    func body(content: Content) -> some View {
        content.onChange(of: lit) { _, now in if now { action() } }
    }
}

/// The ring round a focused item: 3 points of white standing outside it, as
/// round a lit card's artwork (`LitArtwork`).
struct PadRingView: View {
    let ring: PadRing
    let lit: Bool

    var body: some View {
        Group {
            switch ring {
            case .capsule: Capsule().strokeBorder(.white, lineWidth: 3).padding(-5)
            case .circle: Circle().strokeBorder(.white, lineWidth: 3).padding(-5)
            case .rounded(let corner):
                RoundedRectangle(cornerRadius: corner + 5, style: .continuous).strokeBorder(.white, lineWidth: 3).padding(-5)
            case .inside(let corner):
                RoundedRectangle(cornerRadius: corner, style: .continuous).strokeBorder(.white, lineWidth: 3)
            case .card, .none: Color.clear
            }
        }
        .opacity(lit ? 1 : 0)
        .animation(.easeOut(duration: 0.18), value: lit)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

/// Scrolls a focused item into view: across inside its strip, then the page
/// so the item (a strip's row, with room for its heading) shows between the
/// bars, moving as little as it takes (HubKit `PadReveal`).
@MainActor
enum PadScroller {
    /// Room over a row of cards for its heading.
    static let headingRoom: CGFloat = 44
    static let margin: CGFloat = 12

    static func reveal(_ id: String, on page: PadPage) {
        let scrollId = page.scrollId(id)
        let group = page.group(of: id)
        let strip = group.flatMap { page.stripProxies[$0.id] }
        if let strip {
            withAnimation(.easeOut(duration: 0.25)) { strip.scrollTo(scrollId, anchor: nil) }
        }
        guard let proxy = page.proxy else { return }
        // A strip's card: its row, which the page's scroll view knows; else the item.
        let target: AnyHashable = strip != nil ? AnyHashable(group!.id) : scrollId
        guard let frame = page.frames[id], page.visible.height > 0 else {
            withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(target, anchor: nil) }
            return
        }
        let room = strip != nil ? headingRoom : margin
        let visible = page.visible
        // Measured from far above the window's top, so nothing is near the
        // content's start that `PadReveal` keeps scrolls after.
        let base = 100_000 - Double(visible.minY)
        let span = Double(frame.minY - room) + base...Double(frame.maxY + margin) + base
        guard let moved = PadReveal.offset(showing: span, at: Double(visible.minY) + base,
                                           length: Double(visible.height)) else { return }
        // As far as `PadReveal` says: the item's top `room` under the top, or
        // its foot `margin` over the foot. An anchor is a point of the item
        // put on the same point of the scroll view.
        let height = Double(visible.height)
        let item = Double(frame.height)
        let anchor: UnitPoint
        if item >= height {
            anchor = .top
        } else if moved < Double(visible.minY) + base {
            anchor = UnitPoint(x: 0.5, y: min(max(Double(room) / (height - item), 0), 1))
        } else {
            anchor = UnitPoint(x: 0.5, y: min(max((height - Double(margin) - item) / (height - item), 0), 1))
        }
        withAnimation(.easeOut(duration: 0.25)) { proxy.scrollTo(target, anchor: anchor) }
    }
}

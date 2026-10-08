import HubKit
import Observation
import SwiftUI

// A controller or a keyboard driving every screen (#46, C), as the Pocket's
// pad does. HubKit's `PadFocus` decides where a press goes; this is the app's
// side of it: the pages and the bar register what can take focus
// (`padPage`, `padGroup`, `padFocusable`), and `PadFocusCenter` keeps the
// focus, draws the ring only while a controller or keyboard is in use, scrolls
// the focused item into view and presses it.
//
// Every input goes through `PadFocusCenter.route`: the keyboard (`PadKeys`)
// and the app-wide controller input (#46, A). The center takes what is its
// own (a step, Ⓐ, Ⓨ, Ⓑ out of the bar) and hands everything else on to
// `unhandled`, which the shell sets: Back, the sections.

/// What a focusable item does when pressed (Ⓐ, Return) and held (Ⓨ).
struct PadItemActions {
    var press: (() -> Void)?
    var hold: (() -> Void)?
}

/// One page's focusable items, as they are laid out now: a page of a section's
/// stack, a sheet over the pages (`modal`), or the bar. Not observed: frames
/// change as a page scrolls, and nothing redraws for that.
@MainActor
final class PadPage {
    /// Where this page's focus is remembered (`PadMemory`): the same page shown
    /// again returns to it.
    var key = ""
    /// The section's stack it is in (`StackKey.id`), or "" for a sheet or the bar.
    var stack = ""
    /// A sheet over the pages: it has the keys while it shows.
    let modal: Bool
    /// The shell's bars: reached up (or down) from a page, left with Down, Up or Ⓑ.
    let isBar: Bool
    /// A sheet's Ⓑ and Escape: what closes it.
    var back: (() -> Void)?

    /// Each item's frame, in the window (`.global`), so the bar and a page can
    /// be measured against each other.
    var frames: [String: CGRect] = [:]
    var actions: [String: PadItemActions] = [:]
    private(set) var groups: [String: PadGroup] = [:]
    private var order: [String] = []
    /// Whether a group's own items' ids carry its id (`group/item`).
    var prefixes: [String: Bool] = [:]
    /// What an item is called inside its scroll view, where that is not its
    /// own id without its group's prefix (a row's `ForEach` by position).
    var scrollIds: [String: AnyHashable] = [:]
    /// The scroll views to scroll: each strip's, and the page's.
    var stripProxies: [String: ScrollViewProxy] = [:]
    var proxy: ScrollViewProxy?
    /// The part of the window the page shows, between the bars.
    var visible: CGRect = .zero

    init(modal: Bool = false, bar: Bool = false) {
        self.modal = modal
        isBar = bar
    }

    /// What the engine sees. A column's members are rows that are there now
    /// (a registered group, or an item laid out): a row a page lists but did
    /// not draw (an empty shelf) is not somewhere to land. A strip's or a
    /// grid's members not drawn yet are, since their scroll view scrolls to them.
    var map: PadMap {
        let groups = order.compactMap { self.groups[$0] }.map { group -> PadGroup in
            guard case .column = group.layout else { return group }
            var kept = group
            kept.members = group.members.filter { self.groups[$0] != nil || frames[$0] != nil }
            return kept
        }
        return PadMap(frames: frames, groups: groups)
    }

    func setGroup(_ group: PadGroup, prefix: Bool) {
        if groups[group.id] == nil { order.append(group.id) }
        groups[group.id] = group
        prefixes[group.id] = prefix
    }

    func removeGroup(_ id: String) {
        groups[id] = nil
        order.removeAll { $0 == id }
        stripProxies[id] = nil
    }

    /// The group an item sits in directly.
    func group(of id: String) -> PadGroup? {
        order.lazy.compactMap { self.groups[$0] }.first { $0.members.contains(id) }
    }

    /// What `id` is called inside its scroll view: what it said, else its id
    /// without its group's prefix.
    func scrollId(_ id: String) -> AnyHashable {
        if let named = scrollIds[id] { return named }
        guard let group = group(of: id), prefixes[group.id] == true, id.hasPrefix(group.id + "/") else { return id }
        return String(id.dropFirst(group.id.count + 1))
    }
}

/// Where the focus is: an item of a page.
struct PadFocusMark: Equatable {
    let page: ObjectIdentifier
    let id: String
}

@MainActor
@Observable
final class PadFocusCenter {
    static let shared = PadFocusCenter()

    /// The ring shows only while a controller or a keyboard is in use.
    private(set) var input = PadInput()
    private(set) var focus: PadFocusMark?

    /// Where each page's focus returns, and each row's.
    @ObservationIgnored private(set) var memory = PadMemory()
    /// The pages on screen, in the order they appeared: a section's stack
    /// shows its last.
    @ObservationIgnored private var pages: [PadPage] = []
    @ObservationIgnored private(set) var bar: PadPage?
    /// The section's stack in front (`StackKey.id`).
    @ObservationIgnored var shownStack = "" {
        didSet { if shownStack != oldValue { settleSoon() } }
    }
    /// The player or a reader is over everything: they read their own keys.
    var covered = false
    /// Every press this does not take: the shell's Back and sections (#46, A).
    @ObservationIgnored var unhandled: (PadAction) -> Void = { _ in }
    @ObservationIgnored private var settling: Task<Void, Never>?

    /// The page the keys move on now: a sheet over the pages, else the last
    /// page of the stack in front.
    var activePage: PadPage? {
        if let sheet = pages.last(where: { $0.modal }) { return sheet }
        return pages.last { $0.stack == shownStack }
    }

    /// Whether `id` on `page` shows the ring.
    func lit(_ id: String, on page: PadPage) -> Bool {
        input.showsRing && focus == PadFocusMark(page: ObjectIdentifier(page), id: id)
    }

    // MARK: Pages appearing

    func appeared(_ page: PadPage) {
        if page.isBar {
            bar = page
            return
        }
        pages.removeAll { $0 === page }
        pages.append(page)
        settleSoon()
    }

    func disappeared(_ page: PadPage) {
        if page.isBar {
            if bar === page { bar = nil }
            return
        }
        pages.removeAll { $0 === page }
        if focus?.page == ObjectIdentifier(page) { focus = nil }
        settleSoon()
    }

    /// The pointer was used: the ring goes until the next press. Written only
    /// on a change: a touch rewriting the same mode redrew every item under the
    /// finger, and a context menu's choice was lost (the simulator).
    func pointerUsed() {
        guard input.mode != .pointer else { return }
        input.pointer()
    }

    /// A controller or keyboard press: the ring shows. Whether it was hidden.
    @discardableResult
    private func pressed() -> Bool {
        guard input.mode != .directional else { return false }
        input.directional()
        return true
    }

    /// After a page appears, Back, or another section: the ring lands on the
    /// page's place at once, while a controller or keyboard is in use. A
    /// moment later, so what the page draws has been measured.
    private func settleSoon() {
        settling?.cancel()
        settling = Task { [weak self] in
            for _ in 0..<12 {
                try? await Task.sleep(for: .milliseconds(60))
                guard let self, !Task.isCancelled else { return }
                guard self.input.showsRing, !self.covered, let page = self.activePage else { return }
                if let focus = self.focus, focus.page == ObjectIdentifier(page) || self.bar.map(ObjectIdentifier.init) == focus.page {
                    return
                }
                if let place = self.memory.returning(to: page.key, in: page.map) {
                    self.set(place, on: page)
                    return
                }
            }
        }
    }

    // MARK: Presses

    /// Every input's way in: what the focus takes, else `unhandled`.
    func route(_ action: PadAction) {
        if !handle(action) { unhandled(action) }
    }

    /// Whether the focus took `action`. Under the player only a sheet of its
    /// own (its panels, `modal`) is the focus's; the player hands it the presses.
    func handle(_ action: PadAction) -> Bool {
        guard !covered || activePage?.modal == true else { return false }
        switch action {
        case .step(let direction):
            move(direction)
            return true
        case .activate:
            let showing = input.showsRing
            pressed()
            guard let (page, id) = current() else {
                // Nothing focused yet: the first press says where focus is.
                return settleNow()
            }
            guard showing else { return true }
            guard let press = page.actions[id]?.press else { return false }
            press()
            return true
        case .secondary:
            guard input.showsRing, let (page, id) = current(), let hold = page.actions[id]?.hold else { return false }
            hold()
            return true
        case .back:
            // Ⓑ in the bar goes back to the page, as on the Pocket.
            if input.showsRing, let (page, _) = current(), page.isBar, let target = activePage {
                returnFocus(to: target)
                return true
            }
            // A sheet closes.
            if let sheet = activePage, sheet.modal, let back = sheet.back {
                back()
                return true
            }
            return false
        default:
            return false
        }
    }

    /// The focused item, while its page is the one the keys move on or the bar.
    private func current() -> (PadPage, String)? {
        guard let focus else { return nil }
        if let bar, ObjectIdentifier(bar) == focus.page { return (bar, focus.id) }
        guard let page = activePage, ObjectIdentifier(page) == focus.page else { return nil }
        return (page, focus.id)
    }

    @discardableResult
    private func settleNow() -> Bool {
        guard let page = activePage, let place = memory.returning(to: page.key, in: page.map) else {
            guard let bar, let first = PadFocus.first(in: bar.map) else { return false }
            set(first, on: bar)
            return true
        }
        set(place, on: page)
        return true
    }

    private func move(_ direction: PadDirection) {
        let showing = input.showsRing
        pressed()
        guard let (page, id) = current() else {
            settleNow()
            return
        }
        // The first press after the pointer shows where focus is, and moves nothing.
        guard showing else { return }
        // In the bars, toward the page goes back to it (down from the top bar,
        // up from an iPhone's tab bar), never across to the other bar.
        if page.isBar, direction == .up || direction == .down {
            guard let target = activePage, let frame = page.frames[id] else { return }
            let topBar = frame.midY < target.visible.midY
            if (direction == .down) == topBar { returnFocus(to: target) }
            return
        }
        switch PadFocus.step(from: id, direction, in: page.map, memory: memory) {
        case .to(let next):
            set(next, on: page)
        case .stay:
            break
        case .leave:
            if page.isBar {
                // Down from the top bar, up from the tab bar: back to the page.
                if let target = activePage, direction == .down || direction == .up { returnFocus(to: target) }
            } else if !page.modal, let bar, let source = page.frames[id] {
                // Never to the bars under a sheet.
                enterBar(bar, from: source, direction)
            }
        }
    }

    /// Up past a page's top (or down past its foot, to an iPhone's tab bar):
    /// the bar item that way nearest across.
    private func enterBar(_ bar: PadPage, from source: CGRect, _ direction: PadDirection) {
        var map = bar.map
        let probe = "\u{0}page"
        map.frames[probe] = source
        map.groups = []
        if case .to(let target) = PadFocus.step(from: probe, direction, in: map) {
            set(target, on: bar)
        }
    }

    private func returnFocus(to page: PadPage) {
        if let place = memory.returning(to: page.key, in: page.map) { set(place, on: page) }
    }

    /// Focus on `id` of `page`: remembered, and scrolled into view.
    func set(_ id: String, on page: PadPage) {
        focus = PadFocusMark(page: ObjectIdentifier(page), id: id)
        if !page.isBar { memory.focused(id, page: page.key, in: page.map) }
        PadScroller.reveal(id, on: page)
    }

    #if DEBUG
    /// "ring books-home hero-resume", "hidden books-home hero-resume", "none": for the UI tests.
    var probeLine: String {
        guard let focus else { return "none" }
        let page = (pages + [bar].compactMap { $0 }).first { ObjectIdentifier($0) == focus.page }
        let name = page.map { $0.isBar ? "bar" : $0.key } ?? "?"
        return "\(input.showsRing ? "ring" : "hidden") \(name) \(focus.id)"
    }
    #endif

}

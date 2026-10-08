import HubKit
import SwiftUI

// What a long press, a secondary click and Ⓨ open (#46, D), and what Ⓑ closes.
//
// A context menu, a SwiftUI `Menu` and an alert are UIKit's to drive, and a
// game controller drives none of them: a menu cannot even be opened from code.
// So an item's choices are written once, as `PadChoice`s, and shown two ways:
// as its context menu under a finger or a pointer, and as `PadMenuPanel`, a
// sheet of rows the ring walks, under Ⓨ (or Ⓐ on a ⋯). A confirmation asked
// while the ring is in use comes as the same panel (`PadFocusCenter.confirm`).
// Whatever a page presents that is not a page of its own says how it closes
// (`padCloses`), and Ⓑ closes the last of them first.

/// One choice of an item's menu: what its context menu and Ⓨ both offer.
struct PadChoice: Identifiable {
    let id: String
    var title: String
    var detail = ""
    var systemImage: String?
    var role: ButtonRole?
    /// A tick by it: a list the book is on, the format chosen.
    var checked = false
    /// A submenu: a context menu's own menu, the panel's next list.
    var children: [PadChoice] = []
    /// A line before it in a context menu (the one that deletes, last).
    var dividerBefore = false
    var action: () -> Void = {}
}

/// A menu's or a confirmation's heading, the line under it, and its choices.
struct PadMenu: Identifiable {
    let id = UUID()
    var title: String
    var message = ""
    var choices: [PadChoice]
}

/// `choices` as a context menu's (or a `Menu`'s) buttons, a submenu each
/// choice with children.
struct PadChoicesMenu: View {
    let choices: [PadChoice]

    var body: some View {
        ForEach(choices) { choice in
            if choice.dividerBefore { Divider() }
            if choice.children.isEmpty {
                Button(role: choice.role, action: choice.action) { label(choice) }
            } else {
                Menu {
                    PadChoicesMenu(choices: choice.children)
                } label: {
                    label(choice)
                }
            }
        }
    }

    @ViewBuilder private func label(_ choice: PadChoice) -> some View {
        if choice.checked {
            Label(choice.title, systemImage: "checkmark")
        } else if let image = choice.systemImage {
            Label(choice.title, systemImage: image)
        } else {
            Text(choice.title)
        }
    }
}

/// A menu as a sheet of rows the ring walks: Ⓐ chooses, a choice with
/// children opens them in place, Ⓑ goes back out of them and then closes.
/// The choice runs once the sheet has gone, so it may present another.
struct PadMenuPanel: View {
    let menu: PadMenu
    @Environment(\.dismiss) private var dismiss
    @State private var path: [PadChoice] = []
    @State private var chosen: PadChoice?

    var body: some View {
        let level = path.last
        let choices = level?.children ?? menu.choices
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(level?.title ?? menu.title)
                    .font(HubType.heading(20, weight: .heavy, relativeTo: .title3))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityIdentifier("pad-menu-title")
                Spacer(minLength: 8)
                GlassRoundButton(systemImage: level == nil ? "xmark" : "chevron.left",
                                 label: level == nil ? "Close" : "Back", size: 36, pad: "close") { back() }
            }
            if level == nil, !menu.message.isEmpty {
                Text(menu.message)
                    .font(HubType.body(14, relativeTo: .subheadline))
                    .foregroundStyle(.white.opacity(0.7))
                    .fixedSize(horizontal: false, vertical: true)
            }
            ScrollView {
                SheetGroup {
                    ForEach(choices) { choice in
                        PadChoiceRow(choice: choice) { choose(choice) }
                    }
                }
                .padGroup("choices", .column, members: choices.map(\.id))
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .padding(20)
        .padPage("menu:\(menu.id)", modal: true, initial: menu.choices.first.map { "choices/\($0.id)" }) { back() }
        .presentationBackground { GlassSheetFill() }
        .presentationDetents([.medium, .large])
        #if os(macOS)
        .frame(minWidth: 380, minHeight: 300)
        #endif
        .onDisappear {
            guard let action = chosen?.action else { return }
            chosen = nil
            // Once this sheet's own page has gone too, so what the choice
            // presents (a confirmation) comes over the page under it.
            Task { @MainActor in
                try? await Task.sleep(for: .milliseconds(80))
                action()
            }
        }
    }

    private func choose(_ choice: PadChoice) {
        if choice.children.isEmpty {
            chosen = choice
            dismiss()
        } else {
            path.append(choice)
            if let first = choice.children.first { PadFocusCenter.shared.focus("choices/\(first.id)") }
        }
    }

    private func back() {
        guard let left = path.popLast() else {
            dismiss()
            return
        }
        // Back on the submenu's own row.
        PadFocusCenter.shared.focus("choices/\(left.id)")
    }
}

/// One choice as a row: its name (red for one that deletes), the line under
/// it, a tick, or a chevron for a submenu.
private struct PadChoiceRow: View {
    let choice: PadChoice
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                if let image = choice.systemImage {
                    Image(systemName: image)
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 22)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(choice.title)
                        .font(HubType.body(15, weight: .semibold, relativeTo: .body))
                    if !choice.detail.isEmpty {
                        Text(choice.detail)
                            .font(HubType.body(12.5, relativeTo: .caption))
                            .foregroundStyle(.white.opacity(0.6))
                    }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                if choice.checked {
                    Image(systemName: "checkmark").font(.system(size: 15, weight: .bold))
                }
                if !choice.children.isEmpty {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.5))
                }
            }
            .foregroundStyle(choice.role == .destructive ? Color.dangerText : .white)
            .padding(.horizontal, 14)
            .padding(.vertical, 13)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(SheetRowStyle())
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("pad-choice-\(choice.id)")
        // Outside the combined row, never inside it: inside, the focus's anchor and ring made
        // the row a button holding its own button, met twice by VoiceOver and the tests (#46).
        .padFocusable(choice.id, ring: .none, press: action)
        .sheetDivider()
    }
}

extension View {
    /// What this presents (an alert, a dialog, a sheet that is not a page of
    /// its own) while `shown`: Ⓑ runs `close`, the last shown first, and the
    /// ring under it stays still meanwhile.
    func padCloses(_ shown: Bool, close: @escaping () -> Void) -> some View {
        modifier(PadCloses(shown: shown, close: close))
    }
}

private struct PadCloses: ViewModifier {
    let shown: Bool
    let close: () -> Void
    @State private var token = UUID()

    func body(content: Content) -> some View {
        content
            .onChange(of: shown, initial: true) { _, now in
                if now {
                    PadFocusCenter.shared.opened(token, close: close)
                } else {
                    PadFocusCenter.shared.closed(token)
                }
            }
            .onDisappear { PadFocusCenter.shared.closed(token) }
    }
}

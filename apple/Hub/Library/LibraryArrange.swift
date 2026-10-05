import HubKit
import SwiftUI
import UniformTypeIdentifiers

// Arranging libraries (#15): the grip, the jiggle and the drop that moves a
// library, for the Library page's tiles and Settings' rows alike.

/// Two columns of three dots: the place to hold a library to move it. The
/// user asked for this mark wherever libraries move, and for no arrow buttons.
struct GripMark: View {
    var dot: CGFloat = 3.6
    var color: Color = .white

    var body: some View {
        Canvas { context, size in
            let gap = dot * 1.25
            let width = dot * 2 + gap
            let height = dot * 3 + gap * 2
            let origin = CGPoint(x: (size.width - width) / 2, y: (size.height - height) / 2)
            for column in 0..<2 {
                for row in 0..<3 {
                    let rect = CGRect(x: origin.x + CGFloat(column) * (dot + gap),
                                      y: origin.y + CGFloat(row) * (dot + gap), width: dot, height: dot)
                    context.fill(Path(ellipseIn: rect), with: .color(color))
                }
            }
        }
        .frame(width: dot * 2 + dot * 1.25 + 8, height: dot * 3 + dot * 2.5 + 8)
        .accessibilityHidden(true)
    }
}

/// The tiles' wiggle while they are arranged: about a degree each way, each
/// tile a little out of step with the next, as on the iPhone's Home Screen.
/// None under Reduce Motion; the grips say the same thing still.
struct Jiggle: ViewModifier {
    let active: Bool
    let index: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        if active && !reduceMotion {
            TimelineView(.animation) { timeline in
                let seconds = timeline.date.timeIntervalSinceReferenceDate
                let phase = Double(index % 4) * 0.9
                content.rotationEffect(.degrees(sin(seconds * 2 * .pi * 3.2 + phase) * 1.1))
            }
        } else {
            content
        }
    }
}

extension View {
    func jiggle(_ active: Bool, index: Int) -> some View { modifier(Jiggle(active: active, index: index)) }

    /// A library that can be picked up and dropped on another: the dragged one
    /// takes the other's place as it passes over, and the order saves when it
    /// is let go.
    func arrangeable(_ id: String, dragging: Binding<String?>, editor: LibraryOrderEditor, model: AppModel) -> some View {
        self
            .onDrag {
                dragging.wrappedValue = id
                return NSItemProvider(object: id as NSString)
            }
            .onDrop(of: [.text], delegate: LibraryDropDelegate(target: id, dragging: dragging, editor: editor, model: model))
    }
}

/// Moves the library being dragged into the place of the one under it.
struct LibraryDropDelegate: DropDelegate {
    let target: String
    @Binding var dragging: String?
    let editor: LibraryOrderEditor
    let model: AppModel

    func dropEntered(info: DropInfo) {
        guard let dragging, dragging != target else { return }
        editor.preview(dragging, over: target)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        dragging = nil
        editor.commit(model: model)
        return true
    }
}

/// A drop in the gaps between libraries: the order as the drag left it.
struct LibraryDropFallback: DropDelegate {
    @Binding var dragging: String?
    let editor: LibraryOrderEditor
    let model: AppModel

    func dropUpdated(info: DropInfo) -> DropProposal? { DropProposal(operation: .move) }

    func performDrop(info: DropInfo) -> Bool {
        dragging = nil
        editor.commit(model: model)
        return true
    }
}

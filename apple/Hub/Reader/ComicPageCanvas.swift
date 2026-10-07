import HubKit
import SwiftUI

/// The page (#25, phase 3): the unit on screen, one page or two side by side,
/// drawn where the reader's camera looks, over black. It never changes size:
/// the bars float over it (the owner's choice, X7).
///
/// A finger does what the keys do (#18, C7): a tap on an outer third reads on
/// or back and in the middle shows or hides the controls; a double tap looks
/// closer round the point or goes back to the fit; a pinch zooms round the
/// fingers, and the zoom is kept for the next page; a drag moves the page,
/// and a swipe across turns it while it is not zoomed. On the Mac a click, a
/// double click, a trackpad's pinch and a drag do the same.
struct ComicPageCanvas: View {
    let reader: ComicReaderModel
    @Environment(\.displayScale) private var displayScale

    var body: some View {
        GeometryReader { proxy in
            let size = proxy.size
            ZStack(alignment: .topLeading) {
                Color.black
                if let shown = reader.shown {
                    pages(shown, size: size, camera: reader.camera)
                }
            }
            .frame(width: size.width, height: size.height)
            .clipped()
            // While a curl may start at an outer edge, the edge is the curl's (#32).
            .contentShape(ComicCanvasTouch(left: curlEdge(.left, width: size.width),
                                           right: curlEdge(.right, width: size.width)))
            // Under a curl, the page shown is the curl's.
            .opacity(reader.curling ? 0 : 1)
            .onTapGesture(count: 2, coordinateSpace: .local) { point in reader.doubleTapped(at: point) }
            .onTapGesture(count: 1, coordinateSpace: .local) { point in reader.tapped(x: point.x) }
            .gesture(SimultaneousGesture(pinch, drag))
            .onGeometryChange(for: CGSize.self) { proxy in proxy.size } action: { size in
                reader.viewChanged(size: size)
            }
        }
        .onAppear { reader.screenScale = displayScale }
        .onChange(of: displayScale) { _, scale in reader.screenScale = scale }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(reader.heading)
        .accessibilityValue(reader.position.isEmpty ? reader.subtitle : [reader.issueName, reader.position]
            .filter { !$0.isEmpty }.joined(separator: ", "))
        .accessibilityIdentifier("comic-page")
        .accessibilityAddTraits(.isImage)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: reader.forward()
            case .decrement: reader.backward()
            @unknown default: break
            }
        }
        .accessibilityAction(named: "Next page") { reader.turnPage(1) }
        .accessibilityAction(named: "Previous page") { reader.turnPage(-1) }
        .accessibilityAction(named: reader.controlsVisible ? "Hide controls" : "Show controls") {
            reader.setControls(!reader.controlsVisible)
        }
    }

    private enum Side { case left, right }

    /// How much of the page's `side` edge belongs to the curl now: its zone
    /// while a curl may start there, else none.
    private func curlEdge(_ side: Side, width: CGFloat) -> CGFloat {
        #if os(iOS)
        guard reader.curlAvailable else { return 0 }
        // Right to left, the next page is on the left.
        let edge: ComicCurl.Edge = (side == .right) != reader.rtl ? .forward : .backward
        return reader.curlAllowed(edge) ? ComicCurl.zoneWidth(width) : 0
        #else
        return 0
        #endif
    }

    /// Each page at its place in the unit, scaled and moved by the camera.
    private func pages(_ shown: ComicReaderModel.Shown, size: CGSize, camera: ComicCamera) -> some View {
        ForEach(shown.layout.placed, id: \.page) { placed in
            if let image = reader.images[PageKey(shown.publication, placed.page)] {
                Image(decorative: image.image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: placed.width * camera.scale, height: placed.height * camera.scale)
                    .position(x: (placed.x + placed.width / 2 - camera.x) * camera.scale + size.width / 2,
                              y: (placed.y + placed.height / 2 - camera.y) * camera.scale + size.height / 2)
            }
        }
    }

    private var pinch: some Gesture {
        MagnifyGesture(minimumScaleDelta: 0.01)
            .onChanged { value in reader.pinchChanged(magnification: value.magnification, anchor: value.startLocation) }
            .onEnded { _ in reader.pinchEnded() }
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .local)
            .onChanged { value in reader.dragChanged(value.translation) }
            .onEnded { value in reader.dragEnded(value.translation, predicted: value.predictedEndTranslation) }
    }
}

/// A page's thumbnail from the hub (`…/pages/{n}/thumb?w=`): the scrubber's
/// preview and the Pages grid's cells, one width for both so one fetch
/// serves the other (`PageGrid.thumbWidth`). A tile until it comes.
struct ComicThumb: View {
    @Environment(AppModel.self) private var model
    let path: String
    @State private var image: DecodedArtwork?

    var body: some View {
        Color.white.opacity(0.08)
            .overlay {
                if let image {
                    Image(decorative: image.image, scale: 1)
                        .resizable()
                        .scaledToFit()
                        .transition(.opacity)
                }
            }
            .task(id: path) {
                guard !path.isEmpty else { return }
                guard let loaded = await loadArtwork(model.hub, request: path, maxPixels: PageGrid.thumbWidth * 2) else { return }
                withAnimation(.easeOut(duration: 0.15)) { image = loaded }
            }
            .accessibilityHidden(true)
    }
}

/// The page's touches, less the outer edges a curl starts from.
struct ComicCanvasTouch: Shape {
    var left: CGFloat
    var right: CGFloat

    func path(in rect: CGRect) -> Path {
        Path(CGRect(x: rect.minX + left, y: rect.minY, width: max(0, rect.width - left - right), height: rect.height))
    }
}

import HubKit
import SwiftUI

/// Deleting from the server (#34; Android `MediaRemovalScreen`): a read-only
/// preview of what would go, then a separate confirmation with the one-use
/// ticket the preview gave. Cancel comes first, and a native alert asks once
/// more. The copies saved on this device are not touched.
///
/// `kind` is "video" for a film, series, season or episode (a Jellyfin id),
/// "reading" for a book (a work id).
struct RemovalRoute: Hashable {
    let kind: String
    let id: String
    let title: String
}

struct RemovalView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.glassMetrics) private var metrics
    @Environment(\.openRoute) private var openRoute
    let route: RemovalRoute

    @State private var preview: RemovalPreview?
    @State private var status = StatusMessage("")
    @State private var loads = 0
    @State private var confirming = false
    @State private var deleting = false
    /// A deletion that did not end well: some files may be gone, so the page
    /// offers to look again, or to leave for a library that reads again.
    @State private var failed = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PageHeading(title: RemovalLines.heading) {
                    Text(route.title)
                        .font(HubType.body(14, relativeTo: .subheadline))
                        .foregroundStyle(.white.opacity(0.66))
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 4)
                HStack(spacing: 10) {
                    if deleting { ProgressView().controlSize(.small).tint(.white) }
                    StatusLine(message: status) { loads += 1 }
                }
                .padding(.horizontal, metrics.margin)
                .padding(.top, 10)
                if let preview, !failed {
                    summary(preview)
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 14)
                    HStack(spacing: 10) {
                        // The harmless answer first.
                        Button {
                            openRoute.pop(1)
                        } label: {
                            Label(RemovalLines.keep, systemImage: "xmark")
                        }
                        .buttonStyle(GlassPillStyle())
                        .disabled(deleting)
                        .accessibilityIdentifier("removal-cancel")
                        Button {
                            confirming = true
                        } label: {
                            Label(RemovalLines.delete, systemImage: "trash")
                        }
                        .buttonStyle(DestructivePillStyle())
                        .disabled(deleting)
                        .accessibilityIdentifier("removal-delete")
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 16)
                    files(preview)
                        .padding(.horizontal, metrics.margin)
                        .padding(.top, 22)
                } else if failed {
                    HStack(spacing: 10) {
                        Button {
                            failed = false
                            loads += 1
                        } label: {
                            Label(RemovalLines.review, systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(GlassPillStyle())
                        .accessibilityIdentifier("removal-review")
                        Button {
                            openRoute.pop(2)
                        } label: {
                            Label(RemovalLines.backToLibrary, systemImage: "chevron.left")
                        }
                        .buttonStyle(PrimaryPillStyle())
                        .accessibilityIdentifier("removal-leave")
                    }
                    .padding(.horizontal, metrics.margin)
                    .padding(.top, 16)
                }
            }
            .padding(.bottom, 28)
        }
        .ambientArtwork("")
        .task(id: "\(route.id)·\(loads)") { await loadPreview() }
        // A native alert, Cancel in the cancel role (which iOS 26 places last).
        .alert(preview.map(RemovalLines.confirmTitle) ?? "", isPresented: $confirming, presenting: preview) { preview in
            Button(RemovalLines.keep, role: .cancel) {}
            Button(RemovalLines.delete, role: .destructive) { Task { await remove(preview) } }
        } message: { preview in
            Text(RemovalLines.confirmMessage(preview))
        }
    }

    private func summary(_ preview: RemovalPreview) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(preview.title)
                .font(HubType.heading(21, weight: .heavy, relativeTo: .title3))
                .foregroundStyle(.white)
            Text(RemovalLines.files(preview.fileCount))
                .font(HubType.body(15, weight: .bold, relativeTo: .subheadline))
                .foregroundStyle(Color.glassAlert)
            Text(preview.description)
                .font(HubType.body(14, relativeTo: .subheadline))
                .foregroundStyle(.white.opacity(0.78))
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(16)
        .frame(maxWidth: 620, alignment: .leading)
        .glassPanel(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("removal-summary")
    }

    private func files(_ preview: RemovalPreview) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(RemovalLines.filesIncluded)
                .font(HubType.body(metrics.rowTitle, weight: .bold, relativeTo: .title3))
                .foregroundStyle(.white)
            LazyVStack(alignment: .leading, spacing: 6) {
                ForEach(Array(preview.files.enumerated()), id: \.offset) { _, name in
                    Text(name)
                        .font(HubType.body(13, relativeTo: .footnote))
                        .foregroundStyle(.white.opacity(0.8))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .glassPanel(RoundedRectangle(cornerRadius: 11, style: .continuous))
                }
            }
        }
    }

    /// Reading only: nothing is deleted by looking.
    private func loadPreview() async {
        status = StatusMessage(RemovalLines.loading)
        do {
            preview = try await model.hub.fetch(HubEndpoints.removalPreview(kind: route.kind, id: route.id), as: RemovalPreview.self)
            status = StatusMessage("")
        } catch {
            preview = nil
            if error.kind == .cancelled { return }
            status = StatusText.failed(error.message, kind: error.kind, hasData: false)
        }
    }

    /// Sent once, never again by itself: an uncertain answer must not delete
    /// twice. Either way the library is read again, because it may have changed.
    private func remove(_ preview: RemovalPreview) async {
        guard !deleting else { return }
        deleting = true
        defer { deleting = false }
        status = StatusMessage("\(RemovalLines.deleting) \(preview.title)…")
        do {
            _ = try await model.hub.fetch(HubEndpoints.removeMedia(ticket: preview.ticket), as: ActionAck.self)
            model.libraryChanged()
            // The page being deleted, and the one that led to this.
            openRoute.pop(2)
        } catch {
            model.libraryChanged()
            failed = true
            status = StatusText.failed(error.message, kind: error.kind, hasData: true, canRetry: false)
        }
    }
}

/// The destructive twin of `GlassPillStyle`: the same shape, in the alert red.
struct DestructivePillStyle: ButtonStyle {
    @Environment(\.horizontalSizeClass) private var sizeClass
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let compact = sizeClass == .compact
        configuration.label
            .font(HubType.body(compact ? 15 : 16, weight: .bold))
            .padding(.horizontal, 18)
            .padding(.vertical, compact ? 12 : 13)
            .foregroundStyle(.white)
            .background(Color.glassAlert, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .brightness(configuration.isPressed ? 0.1 : 0)
            .opacity(isEnabled ? 1 : 0.55)
            #if os(iOS)
            .hoverEffect(.highlight)
            #endif
    }
}

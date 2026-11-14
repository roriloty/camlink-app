import SwiftUI

/// Upload gallery: everything queued or uploaded, newest first, with previews. A photo can be deleted from the
/// queue or after upload (device, FTP and Drive trash), and failed uploads retried. Port of UploadGallery.kt.
struct GalleryScreen: View {
    @ObservedObject private var queue = UploadQueue.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selected: UploadItem?
    @State private var confirmDelete: UploadItem?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 6)]

    var body: some View {
        NavigationStack {
            ScrollView {
                if queue.items.isEmpty {
                    Text("Nothing uploaded yet. Photos appear here when uploading is switched on in Settings.")
                        .foregroundStyle(Theme.dim).multilineTextAlignment(.center).padding(40)
                }
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(queue.items) { item in
                        Button { selected = item } label: { Tile(item: item) }.buttonStyle(.plain)
                    }
                }
                .padding(6)
            }
            .navigationTitle("Uploads")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button("Clear uploaded") { queue.clearUploaded() }.disabled(!queue.items.contains { $0.status == .done })
                }
            }
            .sheet(item: $selected) { item in
                ItemDetail(id: item.id, onDelete: { confirmDelete = queue.find(item.id); selected = nil })
            }
            .confirmationDialog("Delete \(confirmDelete?.name ?? "")?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), titleVisibility: .visible) {
                Button("Delete everywhere", role: .destructive) { if let i = confirmDelete { queue.delete(i.id) }; confirmDelete = nil }
                Button("Cancel", role: .cancel) { confirmDelete = nil }
            } message: {
                Text("Removes it from this device and the FTP server, and moves the Drive copy to Drive's trash.")
            }
        }
    }
}

private struct Tile: View {
    let item: UploadItem
    @ObservedObject private var queue = UploadQueue.shared

    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Thumb(url: queue.thumbURL(item.id), version: item.thumbVersion)
                .frame(minWidth: 0, maxWidth: .infinity).aspectRatio(1, contentMode: .fit).clipped()
            LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 3) {
                StatusBadge(item: item)
                if item.status == .uploading { ProgressView(value: item.progress).tint(Theme.accent) }
                Text(item.name).font(.system(size: 10)).foregroundStyle(.white).lineLimit(1)
            }
            .padding(5)
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .opacity(item.status == .deleting ? 0.4 : 1)
    }
}

struct StatusBadge: View {
    let item: UploadItem
    var body: some View {
        let (text, color): (String, Color) = {
            switch item.status {
            case .queued: return (item.error == nil ? "Waiting" : "Retrying", Theme.dim)
            case .editing: return ("Editing", Theme.accent)
            case .uploading: return ("→ \(item.dest ?? "")", Theme.accent)
            case .done: return (item.error == nil ? "Uploaded" : "Partly deleted", item.error == nil ? Theme.good : Theme.bad)
            case .failed: return ("Failed", Theme.bad)
            case .deleting: return ("Deleting", Theme.bad)
            }
        }()
        Text(text).font(.system(size: 10, weight: .bold)).padding(.horizontal, 5).padding(.vertical, 2)
            .background(color.opacity(0.85), in: Capsule()).foregroundStyle(.black)
    }
}

private struct Thumb: View {
    let url: URL
    let version: Int
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Theme.card
            if let image { Image(uiImage: image).resizable().scaledToFill() } else { Image(systemName: "photo").foregroundStyle(Theme.dim) }
        }
        .task(id: "\(url.path)#\(version)") { image = UIImage(contentsOfFile: url.path) }
    }
}

private struct ItemDetail: View {
    let id: String
    let onDelete: () -> Void
    @ObservedObject private var queue = UploadQueue.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let item = queue.find(id) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        let file = PhotoStore.resolve(item.file)
                        if FileManager.default.fileExists(atPath: file.path) {
                            FileImage(url: file, maxPixel: 1600).frame(maxWidth: .infinity, minHeight: 240)
                        } else {
                            Thumb(url: queue.thumbURL(item.id), version: item.thumbVersion).frame(height: 260).clipped()
                        }
                        HStack { StatusBadge(item: item); Spacer(); Text(item.time.formatted(date: .abbreviated, time: .shortened)).foregroundStyle(Theme.dim) }
                        Text(item.name).font(.headline)
                        Text(String(format: "%.1f MB", Double(item.size) / 1_048_576)).foregroundStyle(Theme.dim)
                        if let e = item.error { Text(e).foregroundStyle(Theme.bad) }
                        if !item.remotes.isEmpty {
                            Text("Uploaded to: " + item.remotes.map { $0.kind == "ftp" ? "FTP \($0.ref.replacingOccurrences(of: "|", with: "/"))" : "Google Drive" }.joined(separator: ", "))
                                .font(.footnote).foregroundStyle(Theme.dim)
                        }
                        HStack {
                            if item.status == .failed { Button("Retry") { queue.retry(id) }.buttonStyle(.borderedProminent) }
                            if FileManager.default.fileExists(atPath: file.path) {
                                ShareLink(item: file) { Label("Share", systemImage: "square.and.arrow.up") }.buttonStyle(.bordered)
                            }
                            Spacer()
                            Button(role: .destructive) { onDelete() } label: { Label("Delete", systemImage: "trash") }
                                .buttonStyle(.bordered).disabled(item.status == .deleting)
                        }
                    }
                    .padding()
                }
                .navigationTitle(item.name)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            } else {
                Text("Deleted").foregroundStyle(Theme.dim).onAppear { dismiss() }
            }
        }
    }
}

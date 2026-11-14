import SwiftUI

/// Upload gallery: everything queued or uploaded, newest first, with previews. A photo can be deleted from the
/// queue or after upload (device, FTP and Drive trash), and failed uploads retried. Port of UploadGallery.kt.
struct GalleryScreen: View {
    @ObservedObject private var queue = UploadQueue.shared
    @Environment(\.dismiss) private var dismiss
    @State private var selected: UploadItem?
    @State private var confirmDelete: UploadItem?
    @State private var tab: Int?

    private let columns = [GridItem(.adaptive(minimum: 110), spacing: 6)]

    var body: some View {
        NavigationStack {
            let waiting = queue.items.filter { $0.inQueue }
            let uploaded = queue.items.filter { !$0.inQueue }
            let current = tab ?? (waiting.isEmpty ? 1 : 0)
            let shown = current == 0 ? waiting : uploaded
            ScrollView {
                Picker("", selection: Binding(get: { current }, set: { tab = $0 })) {
                    Text("Queue · \(waiting.count)").tag(0)
                    Text("Uploaded · \(uploaded.count)").tag(1)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 8).padding(.top, 6)
                if shown.isEmpty {
                    Text(current == 0 ? "Nothing waiting to upload" : "No uploads yet")
                        .foregroundStyle(Theme.dim).multilineTextAlignment(.center).padding(40)
                }
                LazyVGrid(columns: columns, spacing: 6) {
                    ForEach(shown) { item in
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
                    if (tab ?? (queue.items.contains { $0.inQueue } ? 0 : 1)) == 1 && queue.items.contains(where: { $0.status == .done }) {
                        Button("Clear list") { queue.clearUploaded() }
                    }
                }
            }
            .sheet(item: $selected) { item in
                ItemDetail(id: item.id, onDelete: { confirmDelete = queue.find(item.id); selected = nil })
            }
            .confirmationDialog("Delete \(confirmDelete?.name ?? "")?", isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }), titleVisibility: .visible) {
                Button("Delete everywhere", role: .destructive) { if let i = confirmDelete { queue.delete(i.id) }; confirmDelete = nil }
                Button("Cancel", role: .cancel) { confirmDelete = nil }
            } message: {
                Text(deleteMessage(confirmDelete))
            }
        }
    }
}

/// Same wording as Android's UploadGallery.kt.
private func deleteMessage(_ item: UploadItem?) -> String {
    guard let item else { return "" }
    let w = places(item)
    return "Delete this photo from the phone" + (w.isEmpty ? "" : ", " + w.joined(separator: " and ")) +
        (item.inQueue ? " and stop its upload" : "") + "?" +
        (w.contains("Google Drive") ? " Drive copies go to Drive's trash (kept 30 days)." : "") +
        " The camera's card is not touched."
}

private func places(_ item: UploadItem) -> [String] {
    var w: [String] = []
    if item.remotes.contains(where: { $0.kind == "ftp" }) { w.append("the FTP server") }
    if item.remotes.contains(where: { $0.kind == "drive" }) { w.append("Google Drive") }
    return w
}

/// Status line in the detail view (UploadGallery.kt statusText).
private func statusText(_ i: UploadItem) -> String {
    switch i.status {
    case .queued: return i.error != nil ? "Waiting to retry" : "Waiting to upload"
    case .editing: return "Auto-editing…"
    case .uploading: return "Uploading to \(i.dest ?? "") · \(Int(i.progress * 100))%"
    case .done: return i.error != nil ? "Uploaded (delete failed)" : "Uploaded"
    case .failed: return "Upload failed"
    case .deleting: return "Deleting…"
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
                        Text(statusText(item)).foregroundStyle(item.status == .failed ? Theme.bad : .white)
                        Text(String(format: "%.1f MB", Double(item.size) / 1_048_576)).foregroundStyle(Theme.dim)
                        if let e = item.error { Text(e).foregroundStyle(Theme.bad) }
                        if !places(item).isEmpty {
                            Text("On " + places(item).joined(separator: " and ")).font(.footnote).foregroundStyle(Theme.dim)
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

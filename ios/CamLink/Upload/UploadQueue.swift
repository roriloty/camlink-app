import Foundation
import Network
import UIKit
import ImageIO
import UniformTypeIdentifiers

enum ItemStatus: String, Codable { case queued = "QUEUED", editing = "EDITING", uploading = "UPLOADING", done = "DONE", failed = "FAILED", deleting = "DELETING" }

/// One uploaded copy: kind "ftp" (ref = "dir|name") or "drive" (ref = file id).
struct Remote: Codable, Hashable { let kind: String; let ref: String }

struct UploadItem: Codable, Identifiable, Equatable {
    let id: String
    let name: String
    /// Path relative to Documents (e.g. "CamLink/DSC01234.JPG").
    let file: String
    let time: Date
    var size: Int64
    var status: ItemStatus = .queued
    var progress: Double = 0
    /// "FTP" / "Drive" while sending.
    var dest: String?
    var remotes: [Remote] = []
    var error: String?
    /// Bumped when the thumbnail changes (after auto-edit), so the UI reloads it.
    var thumbVersion = 0
    var attempts = 0
    var notBefore: Date?
    var inQueue: Bool { status != .done }
}

/// Small upload summary for the status bar.
struct UploadState: Equatable {
    var pending = 0
    var current: String?
    var editing = false
    var progress: Double = 0
    var uploaded = 0
    var failed = 0
    var lastError: String?
}

/// Thrown inside an upload when the user deleted that photo meanwhile.
struct UploadCancelled: Error {}

/// Thread-safe set of uploads the user deleted while they were queued or running.
private final class CancelSet {
    private let l = NSLock(); private var s = Set<String>()
    func insert(_ id: String) { l.lock(); s.insert(id); l.unlock() }
    func remove(_ id: String) { l.lock(); s.remove(id); l.unlock() }
    func contains(_ id: String) -> Bool { l.lock(); defer { l.unlock() }; return s.contains(id) }
}

/// Upload queue + history (port of UploadWorker.kt, Uploads and UploadHistory.kt): one file at a time in shooting
/// order, auto-edit first, every version to every destination, retried with back-off, survives restarts.
/// iOS only runs it while CamLink is open (plus the ~30 s iOS grants after switching away).
@MainActor
final class UploadQueue: ObservableObject {
    static let shared = UploadQueue()
    static let MAX_ITEMS = 500
    static let MAX_ATTEMPTS = 5

    @Published private(set) var items: [UploadItem] = []
    @Published private(set) var state = UploadState()

    private let cancelled = CancelSet()
    private var worker: Task<Void, Never>?
    private var wakeUp: CheckedContinuation<Void, Never>?
    private let monitor = NWPathMonitor()
    private var online = true
    private let support: URL

    private init() {
        support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CamLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: support.appendingPathComponent("thumbs"), withIntermediateDirectories: true)
        items = load().map {
            var i = $0
            // An app restart interrupts a running upload; run it again.
            if i.status == .editing || i.status == .uploading { i.status = .queued; i.progress = 0; i.dest = nil }
            return i
        }
        state.pending = items.filter { $0.status == .queued }.count
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in
                self?.online = path.status == .satisfied
                if path.status == .satisfied { self?.poke() }
            }
        }
        monitor.start(queue: DispatchQueue(label: "camlink.net"))
        // Finish deletes that were interrupted.
        for i in items where i.status == .deleting { cancelled.insert(i.id); Task { await purge(i.id) } }
        startWorker()
    }

    func thumbURL(_ id: String) -> URL { support.appendingPathComponent("thumbs/\(id).jpg") }
    func find(_ id: String) -> UploadItem? { items.first { $0.id == id } }

    // ------------------------------------------------------------------ queue side

    /// Queues `file` for upload if FTP and/or Drive is on and the file type is wanted.
    func enqueue(file: URL, name: String, size: Int64) {
        let s = UploadSettingsStore.shared.settings
        guard s.anyDestination, s.wants(name) else { return }
        let id = UUID().uuidString
        writeThumb(id: id, from: file)
        items.insert(UploadItem(id: id, name: name, file: PhotoStore.relative(file), time: Date(), size: size), at: 0)
        if items.count > Self.MAX_ITEMS { items = Array(items.prefix(Self.MAX_ITEMS)) }
        state.pending += 1
        save()
        poke()
    }

    nonisolated func enqueueFromAnyThread(file: URL, name: String, size: Int64) {
        Task { @MainActor in self.enqueue(file: file, name: name, size: size) }
    }

    private func change(_ id: String, persist: Bool = true, _ f: (inout UploadItem) -> Void) {
        guard let i = items.firstIndex(where: { $0.id == id }) else { return }
        f(&items[i])
        if persist { save() }
    }

    func isCancelled(_ id: String) -> Bool { cancelled.contains(id) || find(id).map { $0.status == .deleting } ?? true }

    // ------------------------------------------------------------------ user actions

    /// Deletes the photo everywhere: stops its upload, removes the device copy, the FTP copies, and moves the
    /// Drive copies to Drive's trash.
    func delete(_ id: String) {
        guard let item = find(id) else { return }
        cancelled.insert(id)
        let running = item.status == .editing || item.status == .uploading
        if item.status == .queued { state.pending = max(0, state.pending - 1) }
        change(id) { $0.status = .deleting; $0.error = nil }
        // A running upload notices the flag on its next chunk and purges itself.
        if !running { Task { await purge(id) } }
    }

    /// Tries a failed upload again.
    func retry(_ id: String) {
        guard find(id) != nil else { return }
        change(id) { $0.status = .queued; $0.error = nil; $0.progress = 0; $0.attempts = 0; $0.notBefore = nil }
        state.pending += 1
        poke()
    }

    /// Removes finished uploads from the list (the files stay where they are).
    func clearUploaded() {
        let gone = items.filter { $0.status == .done }
        items.removeAll { $0.status == .done }
        gone.forEach { try? FileManager.default.removeItem(at: thumbURL($0.id)) }
        save()
    }

    /// Re-checks the queue now (e.g. after the settings changed or the network came back).
    func poke() {
        if let w = wakeUp { wakeUp = nil; w.resume() }
        startWorker()
    }

    // ------------------------------------------------------------------ worker

    private func startWorker() {
        guard worker == nil else { return }
        worker = Task { [weak self] in
            while let self, !Task.isCancelled {
                if let next = self.nextItem() {
                    await self.process(next)
                } else {
                    await self.sleep()
                }
            }
        }
    }

    private func nextItem() -> UploadItem? {
        guard online else { return nil }
        let now = Date()
        // Oldest first: shooting order.
        return items.reversed().first { $0.status == .queued && ($0.notBefore ?? .distantPast) <= now }
    }

    private func sleep() async {
        let soonest = items.filter { $0.status == .queued }.compactMap { $0.notBefore }.min()
        let delay = soonest.map { max(1, $0.timeIntervalSinceNow) } ?? 3600
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            wakeUp = c
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(min(delay, 3600) * 1_000_000_000))
                if let self, let w = self.wakeUp { self.wakeUp = nil; w.resume() }
            }
        }
    }

    private func process(_ item: UploadItem) async {
        let id = item.id, name = item.name
        let settings = UploadSettingsStore.shared.settings
        let bg = UIApplication.shared.beginBackgroundTask(withName: "upload") {}
        defer { UIApplication.shared.endBackgroundTask(bg) }
        if isCancelled(id) { return }
        guard settings.anyDestination else {
            change(id) { $0.status = .failed; $0.error = "Upload is switched off" }
            finished(ok: false, error: nil, final: true)
            return
        }
        let original = PhotoStore.resolve(item.file)
        var edited: URL?
        var sent: [Remote] = []
        let doneKey = "\(id)|"
        var done = UserDefaults.standard.dictionary(forKey: "upload_done") as? [String: Bool] ?? [:]
        func markDone(_ k: String) { done[k] = true; UserDefaults.standard.set(done, forKey: "upload_done") }
        func begin(_ label: String, _ dest: String) throws {
            if isCancelled(id) { throw UploadCancelled() }
            state.current = "\(label) → \(dest)"; state.progress = 0; state.editing = false
            change(id) { $0.status = .uploading; $0.dest = dest; $0.progress = 0; $0.error = nil }
        }
        defer { if let edited { try? FileManager.default.removeItem(at: edited) } }
        do {
            guard FileManager.default.fileExists(atPath: original.path) else { throw FTPClient.FTPError(message: "Photo no longer exists on this device") }
            let lower = name.lowercased()
            if settings.autoEdit && (lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg")) {
                state.current = name; state.editing = true; state.progress = 0
                change(id) { $0.status = .editing; $0.error = nil }
                let out = FileManager.default.temporaryDirectory.appendingPathComponent("autoedit/\(name)")
                let ok = await Task.detached(priority: .userInitiated) { AutoEditor.edit(src: original, out: out) }.value
                if ok {
                    edited = out
                    writeThumb(id: id, from: out)
                    change(id) { $0.thumbVersion += 1 }
                }
            }
            // What goes where: the edited copy and/or the original, each to its folder.
            struct Job { let ftpDir: String; let driveSub: String; let remoteName: String; let size: Int64; let file: URL }
            var jobs: [Job] = []
            let sendOriginal = edited == nil || settings.uploadOriginalToo
            if let e = edited {
                let sameFolder = sendOriginal && settings.editedSub() == settings.originalSub()
                let ns = name as NSString
                let remote = sameFolder ? "\(ns.deletingPathExtension)_edited.\(ns.pathExtension.isEmpty ? "JPG" : ns.pathExtension)" : name
                let sz = ((try? FileManager.default.attributesOfItem(atPath: e.path))?[.size] as? NSNumber)?.int64Value ?? 1
                jobs.append(Job(ftpDir: settings.editedDir(), driveSub: settings.editedSub(), remoteName: remote, size: max(1, sz), file: e))
            }
            if sendOriginal {
                jobs.append(Job(ftpDir: settings.originalDir(), driveSub: settings.originalSub(), remoteName: name, size: max(1, item.size), file: original))
            }
            var driveFolders: [String: String] = [:]
            for job in jobs {
                if settings.isComplete {
                    let key = doneKey + "ftp|" + job.ftpDir + "/" + job.remoteName
                    if done[key] != true {
                        try begin(job.remoteName, "FTP")
                        let progress = progressHandler(id, job.size)
                        try await blocking { try FTPClient.upload(settings, dir: job.ftpDir, name: job.remoteName, file: job.file, progress: progress) }
                        markDone(key)
                        record(id, Remote(kind: "ftp", ref: job.ftpDir + "|" + job.remoteName), &sent)
                    }
                }
                if settings.driveReady {
                    let key = doneKey + "drive|" + job.driveSub + "/" + job.remoteName
                    if done[key] != true {
                        try begin(job.remoteName, "Drive")
                        // Subfolders are found by name in Drive each run, so a reinstalled app reuses them.
                        var parent = settings.driveFolderId
                        if !job.driveSub.isEmpty {
                            if let p = driveFolders[job.driveSub] { parent = p } else {
                                parent = try await DriveAPI.call { try await DriveAPI.ensureFolder($0, parent: settings.driveFolderId, name: job.driveSub) }
                                driveFolders[job.driveSub] = parent
                            }
                        }
                        // The same name and size already in the folder is the same photo: don't upload it again.
                        let existing = try await DriveAPI.call { [parent] in try await DriveAPI.findFiles($0, parent: parent, name: job.remoteName) }
                        let fileId: String
                        if let same = existing.first(where: { $0.size == job.size }) { fileId = same.id } else {
                            let progress = progressHandler(id, job.size)
                            fileId = try await DriveAPI.call { [parent] in
                                try await DriveAPI.upload($0, parent: parent, name: job.remoteName, file: job.file, size: job.size, progress: progress)
                            }
                            // Same name but different content (e.g. re-edited): replace it. The old one goes to the trash.
                            for old in existing { _ = try? await DriveAPI.call { try await DriveAPI.trash($0, id: old.id) } }
                        }
                        markDone(key)
                        record(id, Remote(kind: "drive", ref: fileId), &sent)
                    }
                }
            }
            if isCancelled(id) { throw UploadCancelled() }
            // Only once every version is safely on every destination.
            if settings.deleteAfterUpload {
                try? FileManager.default.removeItem(at: original)
                DebugLog.d("Deleted \(name) from this device after upload")
            }
            forget(prefix: doneKey)
            change(id) { $0.status = .done; $0.progress = 1; $0.dest = nil; $0.error = nil }
            finished(ok: true, error: nil, final: true)
        } catch {
            if error is UploadCancelled || isCancelled(id) {
                // Deleted while uploading: remove whatever already reached a server, then the photo.
                forget(prefix: doneKey)
                await purge(id, extra: sent)
                state.current = nil; state.progress = 0; state.editing = false
                state.pending = max(0, state.pending - 1)
                return
            }
            let attempts = (find(id)?.attempts ?? 0) + 1
            let final = attempts >= Self.MAX_ATTEMPTS
            DebugLog.d("Upload of \(name) failed (attempt \(attempts)): \(error.localizedDescription)")
            finished(ok: false, error: error.localizedDescription, final: final)
            change(id) {
                $0.attempts = attempts
                $0.status = final ? .failed : .queued
                $0.dest = nil; $0.progress = 0
                $0.error = error.localizedDescription + (final ? "" : " · retrying")
                // Exponential back-off like WorkManager: 20 s, 40 s, 80 s…
                $0.notBefore = final ? nil : Date().addingTimeInterval(20 * pow(2, Double(attempts - 1)))
            }
            if final { forget(prefix: doneKey) }
        }
    }

    private func record(_ id: String, _ r: Remote, _ sent: inout [Remote]) {
        sent.append(r)
        change(id) { if !$0.remotes.contains(r) { $0.remotes.append(r) } }
    }

    /// Progress callback usable from any thread; throws the upload out (via the cancel check) once deleted.
    private nonisolated func progressHandler(_ id: String, _ total: Int64) -> (Int64) throws -> Void {
        var last = -1
        let cancelled = self.cancelled
        return { [weak self] sent in
            if cancelled.contains(id) { throw UploadCancelled() }
            let p = min(1, Double(sent) / Double(max(total, 1)))
            let pct = Int(p * 100)
            guard pct != last else { return }
            last = pct
            Task { @MainActor in
                guard let self else { return }
                self.state.progress = p
                self.change(id, persist: false) { $0.progress = p }
            }
        }
    }

    private func finished(ok: Bool, error: String?, final: Bool) {
        state.current = nil; state.progress = 0; state.editing = false
        if ok || final { state.pending = max(0, state.pending - 1) }
        if ok { state.uploaded += 1 }
        if !ok && final { state.failed += 1 }
        if !ok, let error { state.lastError = error }
    }

    private func forget(prefix: String) {
        var done = UserDefaults.standard.dictionary(forKey: "upload_done") as? [String: Bool] ?? [:]
        done = done.filter { !$0.key.hasPrefix(prefix) }
        UserDefaults.standard.set(done, forKey: "upload_done")
    }

    /// Removes every copy, then the entry itself.
    private func purge(_ id: String, extra: [Remote] = []) async {
        guard let item = find(id) else { return }
        var remotes = item.remotes
        for r in extra where !remotes.contains(r) { remotes.append(r) }
        let errors = await deleteRemotes(remotes)
        try? FileManager.default.removeItem(at: PhotoStore.resolve(item.file))
        if !errors.isEmpty {
            DebugLog.d("Delete of \(item.name) incomplete: \(errors.map { $0.1 })")
            // Keep what couldn't be removed so the user can try again.
            let left = remotes.filter { r in errors.contains { $0.0 == r } }
            change(id) { $0.status = .done; $0.remotes = left; $0.error = "Couldn't delete: " + errors[0].1 }
            cancelled.remove(id)
            return
        }
        try? FileManager.default.removeItem(at: thumbURL(id))
        items.removeAll { $0.id == id }
        cancelled.remove(id)
        save()
    }

    private func deleteRemotes(_ remotes: [Remote]) async -> [(Remote, String)] {
        guard !remotes.isEmpty else { return [] }
        let s = UploadSettingsStore.shared.settings
        var errors: [(Remote, String)] = []
        let ftp = remotes.filter { $0.kind == "ftp" }
        if !ftp.isEmpty {
            let pairs = ftp.map { r -> (String, String) in
                let parts = r.ref.split(separator: "|", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
                return (parts[0], parts.count > 1 ? parts[1] : "")
            }
            do { try await blocking { try FTPClient.delete(s, pairs) } } catch {
                ftp.forEach { errors.append(($0, error.localizedDescription)) }
            }
        }
        for r in remotes where r.kind == "drive" {
            do { try await DriveAPI.call { try await DriveAPI.trash($0, id: r.ref) } } catch { errors.append((r, error.localizedDescription)) }
        }
        return errors
    }

    // ------------------------------------------------------------------ thumbnails + storage

    /// ~320 px JPEG, upright (works for JPEG and Sony ARW previews).
    nonisolated func writeThumb(id: String, from file: URL) {
        guard let src = CGImageSourceCreateWithURL(file as CFURL, nil) else { return }
        let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                     kCGImageSourceCreateThumbnailWithTransform: true,
                                     kCGImageSourceThumbnailMaxPixelSize: 320]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary),
              let dest = CGImageDestinationCreateWithURL(thumbURLNonisolated(id) as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: 0.82] as CFDictionary)
        CGImageDestinationFinalize(dest)
    }

    private nonisolated func thumbURLNonisolated(_ id: String) -> URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CamLink/thumbs/\(id).jpg")
    }

    private var historyURL: URL { support.appendingPathComponent("uploads.json") }

    private func save() {
        let snapshot = items
        let url = historyURL
        DispatchQueue.global(qos: .utility).async {
            if let d = try? JSONEncoder().encode(snapshot) { try? d.write(to: url, options: .atomic) }
        }
    }

    private func load() -> [UploadItem] {
        guard let d = try? Data(contentsOf: historyURL) else { return [] }
        return (try? JSONDecoder().decode([UploadItem].self, from: d)) ?? []
    }
}

/// Runs blocking work (FTP) off the main actor.
func blocking<T>(_ f: @escaping () throws -> T) async throws -> T {
    try await withCheckedThrowingContinuation { c in
        DispatchQueue.global(qos: .userInitiated).async { c.resume(with: Result { try f() }) }
    }
}

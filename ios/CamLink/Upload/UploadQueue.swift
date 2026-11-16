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
    /// Auto-edit has run (or wasn't possible); the photo is ready to upload. Optional so older history still loads.
    var editDone: Bool?
    /// The edited copy, relative to Application Support/CamLink; kept until the upload succeeds or finally fails,
    /// so a retry doesn't edit again.
    var editedFile: String?
    var inQueue: Bool { status != .done }
}

/// One upload in progress: what it is sending ("DSC01041.JPG → Drive") and how far it is.
struct ActiveUpload: Equatable { var label: String; var progress: Double }

/// Small upload summary for the status bars. Editing and several uploads run at the same time (port of Android's
/// UploadState).
struct UploadState: Equatable {
    /// Photos waiting for or in upload (edited or not yet).
    var pending = 0
    /// Photo being auto-edited right now, if any.
    var editingName: String?
    /// Uploads running right now, by photo id.
    var active: [String: ActiveUpload] = [:]
    var uploaded = 0
    var failed = 0
    var lastError: String?

    var uploading: Int { active.count }
    /// Average progress (0..1) of the running uploads.
    var progress: Double { active.isEmpty ? 0 : active.values.map(\.progress).reduce(0, +) / Double(active.count) }

    /// One line for the Upload only screen, e.g. "Uploading 2 photos · 45% · editing DSC01042.JPG · 3 waiting".
    func describe() -> String {
        var parts: [String] = []
        if active.count == 1, let a = active.values.first { parts.append("Uploading \(a.label) \(Int(a.progress * 100))%") }
        else if active.count > 1 { parts.append("Uploading \(active.count) photos · \(Int(progress * 100))%") }
        if let e = editingName { parts.append("editing \(e)") }
        let waiting = max(0, pending - active.count - (editingName != nil ? 1 : 0))
        if waiting > 0 { parts.append("\(waiting) waiting") }
        let line = parts.joined(separator: " · ")
        return line.prefix(1).uppercased() + line.dropFirst()
    }

    /// Tiny indicator for the camera screen: "↑ 3 · 2× 45% · editing".
    func indicator() -> String {
        var t = "↑ \(pending)"
        if uploading > 0 { t += " · " + (uploading > 1 ? "\(uploading)× " : "") + "\(Int(progress * 100))%" }
        if editingName != nil { t += " · editing" }
        if uploading == 0 && editingName == nil { t += " waiting" }
        return t
    }
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

/// Upload queue + history (port of UploadWorker.kt, Uploads and UploadHistory.kt), in shooting order. Auto-edit runs
/// one photo at a time (memory); edited photos upload in parallel, up to `MAX_PARALLEL` at once, so the next photo is
/// edited while earlier ones upload. FTP and Drive of one photo go one after another. Retried with back-off,
/// survives restarts. iOS only runs it while CamLink is open (plus the ~30 s iOS grants after switching away);
/// interrupted uploads start again when the app comes back.
@MainActor
final class UploadQueue: ObservableObject {
    static let shared = UploadQueue()
    static let MAX_ITEMS = 500
    static let MAX_ATTEMPTS = 5
    /// Uploads that run at the same time (each photo is one job).
    static let MAX_PARALLEL = 3

    @Published private(set) var items: [UploadItem] = []
    @Published private(set) var state = UploadState()

    private let cancelled = CancelSet()
    private var worker: Task<Void, Never>?
    private var wakeUp: CheckedContinuation<Void, Never>?
    private let monitor = NWPathMonitor()
    private var online = true
    private let support: URL
    /// Photos whose upload job is running.
    private var running = Set<String>()
    /// Photo being auto-edited.
    private var editingId: String?
    /// Drive subfolder lookups shared by parallel uploads, so two can't both create "Edited".
    private var driveFolderTasks: [String: Task<String, Error>] = [:]

    private init() {
        support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("CamLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: support.appendingPathComponent("thumbs"), withIntermediateDirectories: true)
        try? FileManager.default.createDirectory(at: support.appendingPathComponent("edited"), withIntermediateDirectories: true)
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
        let running = item.status == .editing || item.status == .uploading || self.running.contains(id)
        if !running && item.status == .queued { state.pending = max(0, state.pending - 1) }
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
                self.schedule()
                await self.sleep()
            }
        }
    }

    private static func isJpeg(_ name: String) -> Bool { let l = name.lowercased(); return l.hasSuffix(".jpg") || l.hasSuffix(".jpeg") }

    private func needsEdit(_ i: UploadItem, _ s: UploadSettings) -> Bool { s.autoEdit && Self.isJpeg(i.name) && i.editDone != true }

    /// Starts the next edit (one at a time) and as many uploads as there are free slots, oldest first.
    private func schedule() {
        let s = UploadSettingsStore.shared.settings
        let now = Date()
        if running.isEmpty && editingId == nil { driveFolderTasks.removeAll() } // look subfolders up again next run
        let oldestFirst = Array(items.reversed())
        if editingId == nil, let e = oldestFirst.first(where: { $0.status == .queued && !running.contains($0.id) && needsEdit($0, s) }) {
            startEdit(e)
        }
        guard online else { return }
        while running.count < Self.MAX_PARALLEL,
              let u = oldestFirst.first(where: { $0.status == .queued && !running.contains($0.id) && $0.id != editingId &&
                                                 !needsEdit($0, s) && ($0.notBefore ?? .distantPast) <= now }) {
            running.insert(u.id)
            Task { [weak self] in
                guard let self else { return }
                await self.process(u)
                self.running.remove(u.id)
                self.state.active[u.id] = nil
                self.poke()
            }
        }
    }

    private func startEdit(_ item: UploadItem) {
        let id = item.id, name = item.name
        editingId = id
        state.editingName = name
        change(id) { $0.status = .editing; $0.error = nil }
        let original = PhotoStore.resolve(item.file)
        let ext = (name as NSString).pathExtension.isEmpty ? "JPG" : (name as NSString).pathExtension
        let rel = "edited/\(id).\(ext)"
        let out = support.appendingPathComponent(rel)
        Task { [weak self] in
            let bg = UIApplication.shared.beginBackgroundTask(withName: "autoedit") {}
            let ok = await Task.detached(priority: .userInitiated) { AutoEditor.edit(src: original, out: out) }.value
            UIApplication.shared.endBackgroundTask(bg)
            guard let self else { return }
            self.editingId = nil
            self.state.editingName = nil
            if self.isCancelled(id) {
                try? FileManager.default.removeItem(at: out)
                await self.purge(id)
                self.state.pending = max(0, self.state.pending - 1)
            } else {
                if ok { self.writeThumb(id: id, from: out) }
                // If editing failed, the original is uploaded instead.
                self.change(id) {
                    $0.editDone = true; $0.editedFile = ok ? rel : nil; $0.status = .queued
                    if ok { $0.thumbVersion += 1 }
                }
            }
            self.poke()
        }
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

    /// Id of the Drive subfolder `name` in `parent`, created once even when several uploads ask at the same time.
    private func driveSubfolder(parent: String, name: String) async throws -> String {
        let key = parent + "|" + name
        let task: Task<String, Error>
        if let t = driveFolderTasks[key] { task = t } else {
            task = Task { try await DriveAPI.call { try await DriveAPI.ensureFolder($0, parent: parent, name: name) } }
            driveFolderTasks[key] = task
        }
        do { return try await task.value } catch { driveFolderTasks[key] = nil; throw error }
    }

    private func editedURL(_ item: UploadItem) -> URL? { item.editedFile.map { support.appendingPathComponent($0) } }

    private func process(_ item: UploadItem) async {
        let id = item.id, name = item.name
        let settings = UploadSettingsStore.shared.settings
        let bg = UIApplication.shared.beginBackgroundTask(withName: "upload") {}
        defer { UIApplication.shared.endBackgroundTask(bg) }
        if isCancelled(id) { return }
        guard settings.anyDestination else {
            change(id) { $0.status = .failed; $0.error = "Upload is switched off" }
            finished(id, ok: false, error: nil, final: true)
            return
        }
        let original = PhotoStore.resolve(item.file)
        // The edit only counts while auto-edit is still on (it may have been switched off since).
        let edited: URL? = settings.autoEdit ? editedURL(item).flatMap { FileManager.default.fileExists(atPath: $0.path) ? $0 : nil } : nil
        var sent: [Remote] = []
        let doneKey = "\(id)|"
        func isDone(_ k: String) -> Bool { (UserDefaults.standard.dictionary(forKey: "upload_done") as? [String: Bool])?[k] == true }
        func markDone(_ k: String) {
            // Read fresh each time: parallel uploads write their own keys.
            var d = UserDefaults.standard.dictionary(forKey: "upload_done") as? [String: Bool] ?? [:]
            d[k] = true; UserDefaults.standard.set(d, forKey: "upload_done")
        }
        func begin(_ label: String, _ dest: String) throws {
            if isCancelled(id) { throw UploadCancelled() }
            state.active[id] = ActiveUpload(label: "\(label) → \(dest)", progress: 0)
            change(id) { $0.status = .uploading; $0.dest = dest; $0.progress = 0; $0.error = nil }
        }
        func dropEdited() { if let e = editedURL(item) { try? FileManager.default.removeItem(at: e) } }
        do {
            guard FileManager.default.fileExists(atPath: original.path) else { throw FTPClient.FTPError(message: "Photo no longer exists on this device") }
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
            for job in jobs {
                if settings.isComplete {
                    let key = doneKey + "ftp|" + job.ftpDir + "/" + job.remoteName
                    if !isDone(key) {
                        try begin(job.remoteName, "FTP")
                        let progress = progressHandler(id, job.size)
                        try await blocking { try FTPClient.upload(settings, dir: job.ftpDir, name: job.remoteName, file: job.file, progress: progress) }
                        markDone(key)
                        record(id, Remote(kind: "ftp", ref: job.ftpDir + "|" + job.remoteName), &sent)
                    }
                }
                if settings.driveReady {
                    let key = doneKey + "drive|" + job.driveSub + "/" + job.remoteName
                    if !isDone(key) {
                        try begin(job.remoteName, "Drive")
                        // Subfolders are found by name in Drive each run, so a reinstalled app reuses them.
                        var parent = settings.driveFolderId
                        if !job.driveSub.isEmpty { parent = try await driveSubfolder(parent: settings.driveFolderId, name: job.driveSub) }
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
            dropEdited()
            change(id) { $0.status = .done; $0.progress = 1; $0.dest = nil; $0.error = nil; $0.editedFile = nil }
            finished(id, ok: true, error: nil, final: true)
        } catch {
            if error is UploadCancelled || isCancelled(id) {
                // Deleted while uploading: remove whatever already reached a server, then the photo.
                forget(prefix: doneKey)
                await purge(id, extra: sent)
                state.active[id] = nil
                state.pending = max(0, state.pending - 1)
                return
            }
            let attempts = (find(id)?.attempts ?? 0) + 1
            let final = attempts >= Self.MAX_ATTEMPTS
            DebugLog.d("Upload of \(name) failed (attempt \(attempts)): \(error.localizedDescription)")
            finished(id, ok: false, error: error.localizedDescription, final: final)
            change(id) {
                $0.attempts = attempts
                $0.status = final ? .failed : .queued
                $0.dest = nil; $0.progress = 0
                $0.error = error.localizedDescription + (final ? "" : " · retrying")
                // Exponential back-off like WorkManager: 20 s, 40 s, 80 s…
                $0.notBefore = final ? nil : Date().addingTimeInterval(20 * pow(2, Double(attempts - 1)))
            }
            if final {
                forget(prefix: doneKey)
                dropEdited()
                // A later Retry edits again.
                change(id) { $0.editedFile = nil; $0.editDone = nil }
            }
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
                self.state.active[id]?.progress = p
                self.change(id, persist: false) { $0.progress = p }
            }
        }
    }

    private func finished(_ id: String, ok: Bool, error: String?, final: Bool) {
        state.active[id] = nil
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
        if let e = editedURL(item) { try? FileManager.default.removeItem(at: e) }
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

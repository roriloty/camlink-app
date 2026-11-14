import Foundation
import Security
import Photos

/// In-app log (Settings › Debug log), so USB behaviour on a real iPhone/iPad can be reported back.
enum DebugLog {
    private static let lock = NSLock()
    private static var lines: [String] = []
    private static let fmt: DateFormatter = { let f = DateFormatter(); f.dateFormat = "HH:mm:ss.SSS"; return f }()

    static func d(_ s: String) {
        let line = fmt.string(from: Date()) + "  " + s
        NSLog("CamLink: %@", s)
        lock.lock(); lines.append(line); if lines.count > 2000 { lines.removeFirst(lines.count - 2000) }; lock.unlock()
    }
    static var text: String { lock.lock(); defer { lock.unlock() }; return lines.joined(separator: "\n") }
    static func clear() { lock.lock(); lines.removeAll(); lock.unlock() }
}

/// Small Keychain wrapper (FTP password, Google refresh token).
enum Keychain {
    private static let service = "app.camlink.usb"

    static func get(_ key: String) -> String? {
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: key,
                                  kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String?) {
        let q: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: key]
        SecItemDelete(q as CFDictionary)
        guard let value, !value.isEmpty else { return }
        var add = q
        add[kSecValueData] = Data(value.utf8)
        add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(add as CFDictionary, nil)
    }
}

/// Saves the camera's files exactly as received into Documents/CamLink (visible in the Files app under
/// "On My iPhone › CamLink"), and optionally adds them to the Photos library.
enum PhotoStore {
    static var folder: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("CamLink", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Path relative to Documents, stored in the upload history (the app container path changes between installs).
    static func relative(_ url: URL) -> String { "CamLink/" + url.lastPathComponent }
    static func resolve(_ rel: String) -> URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent(rel)
    }

    static func save(name: String, bytes: Data) throws -> URL {
        var url = folder.appendingPathComponent(name)
        // Never overwrite an earlier photo with the same name (the camera's counter can wrap or be reset).
        var n = 1
        let base = (name as NSString).deletingPathExtension, ext = (name as NSString).pathExtension
        while FileManager.default.fileExists(atPath: url.path) {
            url = folder.appendingPathComponent("\(base)_\(n).\(ext)"); n += 1
        }
        try bytes.write(to: url, options: .atomic)
        let s = UploadSettingsStore.shared.settings
        if s.saveToPhotos { photos.async { addToPhotos(url, name: url.lastPathComponent) } }
        let lower = name.lowercased()
        if s.saveEditedLocally && (lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg")) { saveEditedCopy(of: url) }
        return url
    }

    /// Photos work runs one item at a time (also keeps the "CamLink" album from being created twice).
    private static let photos = DispatchQueue(label: "camlink.photos", qos: .utility)
    static let ALBUM = "CamLink"

    /// "Save edited copy to phone": auto-edits the JPEG and adds the edited copy to the Photos album "CamLink",
    /// whether or not an upload destination is set up. The original is saved as before.
    static func saveEditedCopy(of original: URL) {
        photos.async {
            let ns = original.lastPathComponent as NSString
            let name = "\(ns.deletingPathExtension)_edited.\(ns.pathExtension.isEmpty ? "JPG" : ns.pathExtension)"
            let out = FileManager.default.temporaryDirectory.appendingPathComponent("edited-local/\(UUID().uuidString)/\(name)")
            guard AutoEditor.edit(src: original, out: out) else { return }
            addToPhotos(out, name: name)
            try? FileManager.default.removeItem(at: out.deletingLastPathComponent())
            DebugLog.d("Saved edited copy \(name) to Photos")
        }
    }

    /// Adds a file to Photos, into the "CamLink" album when full library access is allowed (otherwise just to the
    /// library). Blocks until done; call on `photos`.
    private static func addToPhotos(_ url: URL, name: String) {
        var status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        if status == .notDetermined { status = wait { done in PHPhotoLibrary.requestAuthorization(for: .readWrite) { done($0) } } }
        var album: PHAssetCollection?
        if status == .authorized { album = findOrCreateAlbum() }
        else if status != .limited {
            // Without library access, adding still works ("add only"), just not into the album.
            var add = PHPhotoLibrary.authorizationStatus(for: .addOnly)
            if add == .notDetermined { add = wait { done in PHPhotoLibrary.requestAuthorization(for: .addOnly) { done($0) } } }
            guard add == .authorized || add == .limited else { DebugLog.d("Photos: no permission"); return }
        }
        do {
            try PHPhotoLibrary.shared().performChangesAndWait {
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = name
                req.addResource(with: .photo, fileURL: url, options: opts)
                if let album, let ph = req.placeholderForCreatedAsset {
                    PHAssetCollectionChangeRequest(for: album)?.addAssets([ph] as NSArray)
                }
            }
        } catch { DebugLog.d("Photos: couldn't add \(name): \(error.localizedDescription)") }
    }

    private static func findOrCreateAlbum() -> PHAssetCollection? {
        let opts = PHFetchOptions()
        opts.predicate = NSPredicate(format: "title = %@", ALBUM)
        if let a = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: opts).firstObject { return a }
        var id: String?
        try? PHPhotoLibrary.shared().performChangesAndWait {
            id = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: ALBUM).placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let id else { return nil }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [id], options: nil).firstObject
    }

    private static func wait(_ f: (@escaping (PHAuthorizationStatus) -> Void) -> Void) -> PHAuthorizationStatus {
        let sem = DispatchSemaphore(value: 0)
        var result = PHAuthorizationStatus.notDetermined
        f { result = $0; sem.signal() }
        sem.wait()
        return result
    }

    static func mime(_ name: String) -> String {
        switch (name as NSString).pathExtension.lowercased() {
        case "jpg", "jpeg": return "image/jpeg"
        case "arw", "srf", "sr2": return "image/x-sony-arw"
        case "hif", "heif", "heic": return "image/heif"
        case "tif", "tiff": return "image/tiff"
        default: return "application/octet-stream"
        }
    }
}

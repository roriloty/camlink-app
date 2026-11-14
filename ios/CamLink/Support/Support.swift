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
        if UploadSettingsStore.shared.settings.saveToPhotos { addToPhotos(url) }
        return url
    }

    static func addToPhotos(_ url: URL) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else { return }
            PHPhotoLibrary.shared().performChanges({
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = url.lastPathComponent
                req.addResource(with: .photo, fileURL: url, options: opts)
            }) { ok, err in if !ok { DebugLog.d("Photos: couldn't add \(url.lastPathComponent): \(err?.localizedDescription ?? "?")") } }
        }
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

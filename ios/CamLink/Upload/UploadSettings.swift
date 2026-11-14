import Foundation

enum UploadFilter: String, CaseIterable, Codable { case all = "ALL", jpegOnly = "JPEG_ONLY", rawOnly = "RAW_ONLY" }

/// All upload settings: FTP and Google Drive destinations, plus what gets uploaded and where (port of FtpSettings.kt).
struct UploadSettings: Codable, Equatable {
    /// FTP destination on.
    var enabled = false
    var host = ""
    var port = 21
    var username = ""
    /// Kept in the Keychain, not in this struct's saved JSON.
    var password = ""
    /// Remote folder, created if missing (e.g. "/photos/shoot"). Empty = login folder.
    var directory = ""
    /// Explicit FTPS (AUTH TLS) instead of plain FTP.
    var tls = false
    var filter: UploadFilter = .all
    /// Edit JPEGs in the user's Lightroom style before uploading (originals stay untouched).
    var autoEdit = false
    /// With auto-edit on: also upload the unedited original.
    var uploadOriginalToo = false
    var originalSubfolder = false
    var originalFolderName = "Original"
    var editedSubfolder = false
    var editedFolderName = "Edited"
    /// Delete the photo from this device once every version of it has uploaded successfully.
    var deleteAfterUpload = false
    /// Google Drive destination.
    var driveEnabled = false
    var driveAccount = ""
    var driveFolderId = ""
    var driveFolderPath = ""
    /// iOS only: also add each received photo to the Photos library.
    var saveToPhotos = true

    enum CodingKeys: String, CodingKey {
        case enabled, host, port, username, directory, tls, filter, autoEdit, uploadOriginalToo, originalSubfolder,
             originalFolderName, editedSubfolder, editedFolderName, deleteAfterUpload, driveEnabled, driveAccount,
             driveFolderId, driveFolderPath, saveToPhotos
    }

    init() {}
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        func v<T: Decodable>(_ k: CodingKeys, _ d: T) -> T { (try? c.decodeIfPresent(T.self, forKey: k)) ?? d }
        enabled = v(.enabled, false); host = v(.host, ""); port = v(.port, 21); username = v(.username, "")
        directory = v(.directory, ""); tls = v(.tls, false); filter = v(.filter, .all); autoEdit = v(.autoEdit, false)
        uploadOriginalToo = v(.uploadOriginalToo, false); originalSubfolder = v(.originalSubfolder, false)
        originalFolderName = v(.originalFolderName, "Original"); editedSubfolder = v(.editedSubfolder, false)
        editedFolderName = v(.editedFolderName, "Edited"); deleteAfterUpload = v(.deleteAfterUpload, false)
        driveEnabled = v(.driveEnabled, false); driveAccount = v(.driveAccount, ""); driveFolderId = v(.driveFolderId, "")
        driveFolderPath = v(.driveFolderPath, ""); saveToPhotos = v(.saveToPhotos, true)
    }

    /// FTP is on and usable.
    var isComplete: Bool { enabled && !host.trimmingCharacters(in: .whitespaces).isEmpty }
    var driveReady: Bool { driveEnabled && !driveAccount.isEmpty && !driveFolderId.isEmpty }
    var anyDestination: Bool { isComplete || driveReady }

    private static func clean(_ s: String) -> String { s.trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "/")) }

    /// Subfolder names (empty = none) used for Drive, where folders are addressed by id.
    func originalSub() -> String { originalSubfolder ? (Self.clean(originalFolderName).isEmpty ? "Original" : Self.clean(originalFolderName)) : "" }
    func editedSub() -> String { editedSubfolder ? (Self.clean(editedFolderName).isEmpty ? "Edited" : Self.clean(editedFolderName)) : "" }

    /// Remote FTP folder for originals / edited copies (main folder, or a subfolder of it).
    func originalDir() -> String { originalSubfolder ? join(directory, originalFolderName.isEmpty ? "Original" : originalFolderName) : directory }
    func editedDir() -> String { editedSubfolder ? join(directory, editedFolderName.isEmpty ? "Edited" : editedFolderName) : directory }

    private func join(_ base: String, _ sub: String) -> String {
        var b = base.trimmingCharacters(in: .whitespaces)
        while b.hasSuffix("/") { b.removeLast() }
        let s = Self.clean(sub)
        return b.isEmpty ? s : "\(b)/\(s)"
    }

    func wants(_ fileName: String) -> Bool {
        let n = fileName.lowercased()
        let jpeg = n.hasSuffix(".jpg") || n.hasSuffix(".jpeg")
        switch filter { case .all: return true; case .jpegOnly: return jpeg; case .rawOnly: return !jpeg }
    }
}

/// Persists the settings (UserDefaults + Keychain for the password); readable from the UI and the upload queue.
final class UploadSettingsStore: ObservableObject {
    static let shared = UploadSettingsStore()
    private let key = "upload_settings"
    @Published private(set) var settings: UploadSettings

    private init() {
        var s = (UserDefaults.standard.data(forKey: key).flatMap { try? JSONDecoder().decode(UploadSettings.self, from: $0) }) ?? UploadSettings()
        s.password = Keychain.get("ftp_password") ?? ""
        settings = s
    }

    func save(_ s: UploadSettings) {
        var c = s
        c.host = s.host.trimmingCharacters(in: .whitespaces)
        c.directory = s.directory.trimmingCharacters(in: .whitespaces)
        c.originalFolderName = s.originalFolderName.trimmingCharacters(in: .whitespaces)
        c.editedFolderName = s.editedFolderName.trimmingCharacters(in: .whitespaces)
        if let d = try? JSONEncoder().encode(c) { UserDefaults.standard.set(d, forKey: key) }
        Keychain.set("ftp_password", c.password)
        settings = c
    }
}

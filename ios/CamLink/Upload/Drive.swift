import Foundation
import AuthenticationServices
import CryptoKit
import UIKit

/// Google sign-in for Drive with the limited `drive.file` scope (only files and folders CamLink created).
/// OAuth 2 for installed apps: Google's sign-in page in an ASWebAuthenticationSession, PKCE, no client secret.
/// The iOS OAuth client ID comes from Info.plist (`GoogleClientID`, set at build time).
enum DriveAuth {
    static let SCOPE = "https://www.googleapis.com/auth/drive.file"
    static var clientId: String {
        (Bundle.main.object(forInfoDictionaryKey: "GoogleClientID") as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
    }
    static var configured: Bool { clientId.hasSuffix(".apps.googleusercontent.com") }
    /// Google's iOS redirect: the client ID reversed, as a URL scheme.
    private static var scheme: String { clientId.components(separatedBy: ".").reversed().joined(separator: ".") }
    private static var redirect: String { "\(scheme):/oauth2redirect" }

    struct AuthError: LocalizedError { let message: String; var errorDescription: String? { message } }

    private static var cached: (token: String, expires: Date)?
    private static let lock = NSLock()

    /// Shows Google's sign-in (any account, "Use another account"), returns the chosen e-mail.
    @MainActor
    static func signIn(hint: String? = nil) async throws -> String {
        guard configured else { throw AuthError(message: "Google Drive isn't set up in this build (no iOS OAuth client ID).") }
        let verifier = randomString(64)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8))).base64URL
        var c = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        c.queryItems = [
            .init(name: "client_id", value: clientId), .init(name: "redirect_uri", value: redirect),
            .init(name: "response_type", value: "code"), .init(name: "scope", value: "openid email \(SCOPE)"),
            .init(name: "code_challenge", value: challenge), .init(name: "code_challenge_method", value: "S256"),
            .init(name: "prompt", value: "select_account consent"),
        ] + (hint.map { [.init(name: "login_hint", value: $0)] } ?? [])
        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: c.url!, callbackURLScheme: scheme) { url, error in
                if let url { cont.resume(returning: url) } else {
                    cont.resume(throwing: (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                                ? AuthError(message: "Sign-in cancelled") : (error ?? AuthError(message: "Sign-in failed")))
                }
            }
            session.presentationContextProvider = PresentationAnchor.shared
            session.prefersEphemeralWebBrowserSession = false
            session.start()
        }
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let e = items.first(where: { $0.name == "error" })?.value { throw AuthError(message: "Google: \(e)") }
        guard let code = items.first(where: { $0.name == "code" })?.value else { throw AuthError(message: "Google didn't return a sign-in code") }
        let j = try await tokenRequest(["code": code, "client_id": clientId, "redirect_uri": redirect,
                                        "grant_type": "authorization_code", "code_verifier": verifier])
        guard let refresh = j["refresh_token"] as? String, let access = j["access_token"] as? String else {
            throw AuthError(message: "Google didn't grant offline access")
        }
        if let scopes = j["scope"] as? String, !scopes.contains(SCOPE) {
            throw AuthError(message: "Please allow CamLink to manage the files it creates in your Drive")
        }
        Keychain.set("google_refresh", refresh)
        remember(access, j["expires_in"] as? Double)
        return (j["id_token"] as? String).flatMap(emailFromIdToken) ?? "Google account"
    }

    /// Access token for background work (never shows UI).
    static func token() async throws -> String {
        lock.lock(); let c = cached; lock.unlock()
        if let c, c.expires > Date().addingTimeInterval(60) { return c.token }
        guard let refresh = Keychain.get("google_refresh") else { throw AuthError(message: "Google Drive needs you to sign in again (open Settings)") }
        let j = try await tokenRequest(["refresh_token": refresh, "client_id": clientId, "grant_type": "refresh_token"])
        guard let access = j["access_token"] as? String else { throw AuthError(message: "Google Drive needs you to sign in again (open Settings)") }
        remember(access, j["expires_in"] as? Double)
        return access
    }

    /// Drops a token Google rejected, so the next request gets a fresh one.
    static func invalidate() { lock.lock(); cached = nil; lock.unlock() }

    /// Signs out: revokes the app's access to the account's Drive.
    static func signOut() async {
        if let t = Keychain.get("google_refresh") {
            var r = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            r.httpMethod = "POST"
            r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            r.httpBody = "token=\(t.formEncoded)".data(using: .utf8)
            _ = try? await URLSession.shared.data(for: r)
        }
        Keychain.set("google_refresh", nil)
        invalidate()
    }

    private static func remember(_ token: String, _ expiresIn: Double?) {
        lock.lock(); cached = (token, Date().addingTimeInterval(expiresIn ?? 3000)); lock.unlock()
    }

    private static func tokenRequest(_ form: [String: String]) async throws -> [String: Any] {
        var r = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        r.httpMethod = "POST"
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        r.httpBody = form.map { "\($0.key)=\($0.value.formEncoded)" }.joined(separator: "&").data(using: .utf8)
        let (data, resp) = try await URLSession.shared.data(for: r)
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if (resp as? HTTPURLResponse)?.statusCode != 200 {
            let e = (j["error_description"] as? String) ?? (j["error"] as? String) ?? "HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)"
            if (j["error"] as? String) == "invalid_grant" { Keychain.set("google_refresh", nil) }
            throw AuthError(message: "Google sign-in: \(e)")
        }
        return j
    }

    private static func emailFromIdToken(_ jwt: String) -> String? {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b = String(parts[1]).replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b.count % 4 != 0 { b += "=" }
        guard let d = Data(base64Encoded: b), let j = try? JSONSerialization.jsonObject(with: d) as? [String: Any] else { return nil }
        return j["email"] as? String
    }

    private static func randomString(_ n: Int) -> String {
        let chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return String((0..<n).map { _ in chars[Int.random(in: 0..<chars.count)] })
    }
}

private final class PresentationAnchor: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = PresentationAnchor()
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.flatMap { $0.windows }.first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}

extension Data {
    var base64URL: String {
        base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
}

extension String {
    var formEncoded: String {
        var allowed = CharacterSet.alphanumerics; allowed.insert(charactersIn: "-._~")
        return addingPercentEncoding(withAllowedCharacters: allowed) ?? self
    }
}

/// The few Google Drive v3 REST calls the app needs (port of DriveApi.kt).
enum DriveAPI {
    static let API = "https://www.googleapis.com/drive/v3"
    static let UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    static let FOLDER = "application/vnd.google-apps.folder"

    struct Folder: Identifiable, Hashable { let id: String; let name: String }
    struct FileInfo { let id: String; let size: Int64 }
    struct Unauthorized: LocalizedError { var errorDescription: String? { "Google rejected the sign-in" } }
    struct DriveError: LocalizedError { let message: String; var errorDescription: String? { message } }

    private static func quote(_ s: String) -> String { s.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'") }

    /// Runs `block` with a token, refreshing it once if Google rejects it.
    static func call<T>(_ block: (String) async throws -> T) async throws -> T {
        do { return try await block(try await DriveAuth.token()) } catch is Unauthorized {
            DriveAuth.invalidate()
            return try await block(try await DriveAuth.token())
        }
    }

    private static func request(_ token: String, _ url: String, method: String = "GET", json: [String: Any]? = nil) async throws -> [String: Any] {
        var r = URLRequest(url: URL(string: url)!)
        r.httpMethod = method
        r.timeoutInterval = 60
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let json {
            r.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
            r.httpBody = try JSONSerialization.data(withJSONObject: json)
        }
        let (data, resp) = try await URLSession.shared.data(for: r)
        return try parse(data, resp)
    }

    private static func parse(_ data: Data, _ resp: URLResponse) throws -> [String: Any] {
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 401 { throw Unauthorized() }
        let j = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if !(200...299).contains(code) {
            let msg = ((j["error"] as? [String: Any])?["message"] as? String) ?? "HTTP \(code)"
            throw DriveError(message: "Google Drive: \(msg)")
        }
        return j
    }

    private static func q(_ s: String) -> String { s.formEncoded }

    /// Folders this app created and can still reach, without their own Original/Edited subfolders.
    static func appFolders(_ token: String) async throws -> [Folder] {
        var all: [(Folder, [String])] = []
        var page: String? = nil
        repeat {
            var url = "\(API)/files?q=\(q("mimeType='\(FOLDER)' and trashed=false"))&orderBy=name&pageSize=1000&corpora=allDrives" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true&fields=\(q("nextPageToken,files(id,name,parents)"))"
            if let page { url += "&pageToken=\(q(page))" }
            let j = try await request(token, url)
            for f in (j["files"] as? [[String: Any]]) ?? [] {
                guard let id = f["id"] as? String, let name = f["name"] as? String else { continue }
                all.append((Folder(id: id, name: name), (f["parents"] as? [String]) ?? []))
            }
            page = (j["nextPageToken"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        } while page != nil && all.count < 5000
        let ids = Set(all.map { $0.0.id })
        return all.filter { !$0.1.contains(where: ids.contains) }.map { $0.0 }
    }

    static func createFolder(_ token: String, parent: String, name: String) async throws -> String {
        let j = try await request(token, "\(API)/files?supportsAllDrives=true&fields=id", method: "POST",
                                  json: ["name": name, "mimeType": FOLDER, "parents": [parent]])
        guard let id = j["id"] as? String else { throw DriveError(message: "Google Drive didn't create the folder") }
        return id
    }

    /// Id of the folder `name` inside `parent`, creating it if needed.
    static func ensureFolder(_ token: String, parent: String, name: String) async throws -> String {
        let query = "'\(quote(parent))' in parents and name='\(quote(name))' and mimeType='\(FOLDER)' and trashed=false"
        let j = try await request(token, "\(API)/files?q=\(q(query))&corpora=allDrives&supportsAllDrives=true&includeItemsFromAllDrives=true&fields=\(q("files(id)"))&pageSize=10")
        if let id = ((j["files"] as? [[String: Any]])?.first?["id"]) as? String { return id }
        return try await createFolder(token, parent: parent, name: name)
    }

    /// Files called `name` that CamLink can see in `parent` (read fresh from Drive).
    static func findFiles(_ token: String, parent: String, name: String) async throws -> [FileInfo] {
        let query = "'\(quote(parent))' in parents and name='\(quote(name))' and mimeType!='\(FOLDER)' and trashed=false"
        let j = try await request(token, "\(API)/files?q=\(q(query))&corpora=allDrives&supportsAllDrives=true&includeItemsFromAllDrives=true&fields=\(q("files(id,size)"))&pageSize=100")
        return ((j["files"] as? [[String: Any]]) ?? []).compactMap { f in
            guard let id = f["id"] as? String else { return nil }
            return FileInfo(id: id, size: Int64((f["size"] as? String) ?? "-1") ?? -1)
        }
    }

    /// Moves a file to Drive's trash (recoverable for 30 days). A file that's already gone counts as done.
    static func trash(_ token: String, id: String) async throws {
        do { _ = try await request(token, "\(API)/files/\(q(id))?supportsAllDrives=true&fields=id", method: "PATCH", json: ["trashed": true]) }
        catch let e as DriveError where e.message.contains("File not found") { return }
    }

    /// Resumable upload of `file` as `name` into `parent`; returns the new file's id.
    static func upload(_ token: String, parent: String, name: String, file: URL, size: Int64, progress: @escaping (Int64) throws -> Void) async throws -> String {
        var start = URLRequest(url: URL(string: "\(UPLOAD)?uploadType=resumable&supportsAllDrives=true&fields=id")!)
        start.httpMethod = "POST"
        start.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        start.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        start.setValue(PhotoStore.mime(name), forHTTPHeaderField: "X-Upload-Content-Type")
        start.setValue(String(size), forHTTPHeaderField: "X-Upload-Content-Length")
        start.httpBody = try JSONSerialization.data(withJSONObject: ["name": name, "parents": [parent]])
        let (sd, sr) = try await URLSession.shared.data(for: start)
        let http = sr as? HTTPURLResponse
        guard let loc = http?.value(forHTTPHeaderField: "Location"), let locURL = URL(string: loc), (200...299).contains(http?.statusCode ?? 0) else {
            _ = try parse(sd, sr) // throws with Google's message
            throw DriveError(message: "Google Drive didn't start the upload")
        }
        var put = URLRequest(url: locURL)
        put.httpMethod = "PUT"
        put.timeoutInterval = 120
        put.setValue(PhotoStore.mime(name), forHTTPHeaderField: "Content-Type")
        let delegate = ProgressDelegate(progress)
        let (data, resp) = try await URLSession.shared.upload(for: put, fromFile: file, delegate: delegate)
        let j = try parse(data, resp)
        guard let id = j["id"] as? String else { throw DriveError(message: "Google Drive didn't return the file id") }
        return id
    }

    private final class ProgressDelegate: NSObject, URLSessionTaskDelegate {
        let progress: (Int64) throws -> Void
        init(_ p: @escaping (Int64) throws -> Void) { progress = p }
        func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
            // Deleted by the user meanwhile: stop sending.
            do { try progress(totalBytesSent) } catch { task.cancel() }
        }
    }
}

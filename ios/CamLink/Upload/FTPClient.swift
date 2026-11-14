import Foundation

/// Plain FTP / explicit FTPS (AUTH TLS, PROT P) client — iOS has no built-in FTP upload anymore, so this is a small
/// blocking implementation over Foundation streams (port of FtpUploader.kt). Passive mode only.
/// Like Apache commons-net's FTPSClient on Android, the server certificate chain isn't validated
/// (FTP servers usually have self-signed certificates).
final class FTPClient {
    struct FTPError: LocalizedError { let message: String; var errorDescription: String? { message } }

    private let host: String
    private let port: Int
    private let tls: Bool
    private var control: Conn!
    private(set) var lastReply = ""

    private init(host: String, port: Int, tls: Bool) { self.host = host; self.port = port; self.tls = tls }

    // ------------------------------------------------------------------ public API (mirrors FtpUploader)

    static func connect(_ s: UploadSettings) throws -> FTPClient {
        let f = FTPClient(host: s.host, port: s.port, tls: s.tls)
        try f.open(user: s.username.isEmpty ? "anonymous" : s.username, password: s.password)
        return f
    }

    /// Connects, logs in and opens the folder; returns a short summary for the "Test" button.
    static func test(_ s: UploadSettings) throws -> String {
        let f = try connect(s)
        defer { f.close() }
        try f.enterDirectory(s.directory)
        return "Connected to \(s.host) · folder \(f.pwd() ?? "?")"
    }

    /// Deletes files given as (folder, name) pairs; a file that is already gone counts as deleted.
    static func delete(_ s: UploadSettings, _ files: [(String, String)]) throws {
        let f = try connect(s)
        defer { f.close() }
        let home = f.pwd() ?? "/"
        for (dir, name) in files {
            _ = try f.cmd("CWD \(home)")
            if !dir.isEmpty, try f.cmd("CWD \(dir)").code / 100 != 2 { continue } // folder gone: nothing to delete
            if try f.cmd("DELE \(name)").code / 100 != 2 {
                if try f.cmd("SIZE \(name)").code == 213 { throw FTPError(message: "FTP refused to delete \(name) (\(f.lastReply))") }
            }
            _ = try? f.cmd("DELE \(name).part")
        }
    }

    /// Uploads `file` into `dir` as `name` (under a temporary name first, then renamed).
    static func upload(_ s: UploadSettings, dir: String, name: String, file: URL, progress: (Int64) throws -> Void) throws {
        let f = try connect(s)
        defer { f.close() }
        try f.enterDirectory(dir)
        guard let input = InputStream(url: file) else { throw FTPError(message: "Photo no longer exists on this device") }
        let tmp = "\(name).part"
        try f.store(tmp, from: input, progress: progress)
        if try f.cmd("RNFR \(tmp)").code == 350, try f.cmd("RNTO \(name)").code / 100 == 2 { return }
        _ = try f.cmd("DELE \(name)")
        guard try f.cmd("RNFR \(tmp)").code == 350, try f.cmd("RNTO \(name)").code / 100 == 2 else {
            throw FTPError(message: "Upload failed: \(f.lastReply)")
        }
    }

    // ------------------------------------------------------------------ protocol

    private func open(user: String, password: String) throws {
        control = try Conn(host: host, port: port)
        let hello = try reply()
        guard hello.code / 100 == 2 else { throw FTPError(message: "Server refused connection (\(hello.code))") }
        if tls {
            let r = try cmd("AUTH TLS")
            guard r.code / 100 == 2 else { throw FTPError(message: "Server doesn't support FTPS (\(lastReply))") }
            try control.startTLS(peer: host)
            _ = try cmd("PBSZ 0")
            _ = try cmd("PROT P")
        }
        var r = try cmd("USER \(user)")
        if r.code == 331 { r = try cmd("PASS \(password)") }
        guard r.code / 100 == 2 else { throw FTPError(message: "Login failed: \(lastReply)") }
        _ = try cmd("TYPE I")
        _ = try? cmd("OPTS UTF8 ON")
    }

    func close() {
        if control != nil { _ = try? cmd("QUIT", timeout: 3); control.close() }
    }

    func pwd() -> String? {
        guard let r = try? cmd("PWD"), r.code == 257 else { return nil }
        let t = r.text
        if let a = t.firstIndex(of: "\""), let b = t[t.index(after: a)...].firstIndex(of: "\"") { return String(t[t.index(after: a)..<b]) }
        return t
    }

    /// Changes into `dir`, creating each missing level.
    func enterDirectory(_ dir: String) throws {
        let d = dir.trimmingCharacters(in: .whitespaces)
        if d.isEmpty { return }
        if d.hasPrefix("/") { _ = try cmd("CWD /") }
        for part in d.split(separator: "/").map(String.init) where !part.isEmpty {
            if try cmd("CWD \(part)").code / 100 != 2 {
                let mk = try cmd("MKD \(part)")
                let cd = mk.code / 100 == 2 ? try cmd("CWD \(part)").code : 0
                if cd / 100 != 2 {
                    throw FTPError(message: "Can't open or create folder \"\(part)\" (\(lastReply))")
                }
            }
        }
    }

    private func store(_ name: String, from input: InputStream, progress: (Int64) throws -> Void) throws {
        let data = try openDataConnection()
        let r = try cmd("STOR \(name)")
        guard r.code == 150 || r.code == 125 else { data.close(); throw FTPError(message: "Upload refused: \(lastReply)") }
        if tls { try data.startTLS(peer: host) }
        input.open()
        defer { input.close() }
        var buf = [UInt8](repeating: 0, count: 256 * 1024)
        var sent: Int64 = 0
        while true {
            let n = input.read(&buf, maxLength: buf.count)
            if n < 0 { data.close(); throw FTPError(message: "Couldn't read the photo") }
            if n == 0 { break }
            try data.write(Array(buf[0..<n]))
            sent += Int64(n)
            do { try progress(sent) } catch { data.close(); throw error }
        }
        data.close()
        let done = try reply(timeout: 120)
        guard done.code / 100 == 2 else { throw FTPError(message: "Upload failed: \(lastReply)") }
    }

    private func openDataConnection() throws -> Conn {
        let r = try cmd("PASV")
        if r.code == 227, let open = r.text.firstIndex(of: "("), let close = r.text.firstIndex(of: ")") {
            let nums = r.text[r.text.index(after: open)..<close].split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }
            if nums.count == 6 {
                // Like commons-net's NAT workaround: always connect to the server we're talking to.
                return try Conn(host: host, port: nums[4] * 256 + nums[5])
            }
        }
        let e = try cmd("EPSV")
        if e.code == 229, let a = e.text.range(of: "(|||"), let b = e.text[a.upperBound...].firstIndex(of: "|"),
           let p = Int(e.text[a.upperBound..<b]) {
            return try Conn(host: host, port: p)
        }
        throw FTPError(message: "Server didn't open a passive data connection (\(lastReply))")
    }

    @discardableResult
    private func cmd(_ line: String, timeout: TimeInterval = 30) throws -> (code: Int, text: String) {
        try control.write(Array((line + "\r\n").utf8))
        return try reply(timeout: timeout)
    }

    private func reply(timeout: TimeInterval = 30) throws -> (code: Int, text: String) {
        var first = try control.readLine(timeout: timeout)
        var text = first
        if first.count >= 4, first[first.index(first.startIndex, offsetBy: 3)] == "-" {
            let code = String(first.prefix(3))
            while true {
                let l = try control.readLine(timeout: timeout)
                text += "\n" + l
                if l.hasPrefix(code + " ") { first = l; break }
            }
        }
        lastReply = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return (Int(first.prefix(3)) ?? 0, text)
    }

    // ------------------------------------------------------------------ socket

    /// A TCP connection with blocking reads/writes and a timeout, able to switch to TLS mid-stream.
    private final class Conn {
        let input: InputStream
        let output: OutputStream
        private var pending: [UInt8] = []

        init(host: String, port: Int, timeout: TimeInterval = 15) throws {
            var i: InputStream?, o: OutputStream?
            Stream.getStreamsToHost(withName: host, port: port, inputStream: &i, outputStream: &o)
            guard let i, let o else { throw FTPError(message: "Can't connect to \(host):\(port)") }
            input = i; output = o
            input.open(); output.open()
            let end = Date().addingTimeInterval(timeout)
            while output.streamStatus == .opening || input.streamStatus == .opening {
                if Date() > end { close(); throw FTPError(message: "Connection to \(host):\(port) timed out") }
                Thread.sleep(forTimeInterval: 0.01)
            }
            if let e = output.streamError ?? input.streamError { throw FTPError(message: "Can't connect to \(host): \(e.localizedDescription)") }
        }

        func startTLS(peer: String) throws {
            let ssl: [String: Any] = [
                kCFStreamSSLLevel as String: kCFStreamSocketSecurityLevelNegotiatedSSL as String,
                kCFStreamSSLValidatesCertificateChain as String: false,
                kCFStreamSSLPeerName as String: peer,
            ]
            let key = Stream.PropertyKey(kCFStreamPropertySSLSettings as String)
            guard input.setProperty(ssl, forKey: key) && output.setProperty(ssl, forKey: key) else {
                throw FTPError(message: "Couldn't start TLS")
            }
        }

        func write(_ bytes: [UInt8], timeout: TimeInterval = 60) throws {
            var off = 0
            let end = Date().addingTimeInterval(timeout)
            while off < bytes.count {
                if output.hasSpaceAvailable {
                    let n = bytes[off...].withUnsafeBufferPointer { output.write($0.baseAddress!, maxLength: $0.count) }
                    if n < 0 { throw FTPError(message: "Connection lost: \(output.streamError?.localizedDescription ?? "write failed")") }
                    off += n
                } else {
                    if let e = output.streamError { throw FTPError(message: "Connection lost: \(e.localizedDescription)") }
                    if Date() > end { throw FTPError(message: "Server stopped responding") }
                    Thread.sleep(forTimeInterval: 0.005)
                }
            }
        }

        func readLine(timeout: TimeInterval) throws -> String {
            let end = Date().addingTimeInterval(timeout)
            while true {
                if let nl = pending.firstIndex(of: 0x0A) {
                    var line = Array(pending[..<nl]); pending.removeSubrange(...nl)
                    if line.last == 0x0D { line.removeLast() }
                    return String(decoding: line, as: UTF8.self)
                }
                if input.hasBytesAvailable {
                    var buf = [UInt8](repeating: 0, count: 4096)
                    let n = input.read(&buf, maxLength: buf.count)
                    if n < 0 { throw FTPError(message: "Connection lost: \(input.streamError?.localizedDescription ?? "read failed")") }
                    if n == 0 { throw FTPError(message: "Server closed the connection") }
                    pending += buf[0..<n]
                } else {
                    if let e = input.streamError { throw FTPError(message: "Connection lost: \(e.localizedDescription)") }
                    if input.streamStatus == .atEnd { throw FTPError(message: "Server closed the connection") }
                    if Date() > end { throw FTPError(message: "Server didn't answer in time") }
                    Thread.sleep(forTimeInterval: 0.005)
                }
            }
        }

        func close() { input.close(); output.close() }
    }
}

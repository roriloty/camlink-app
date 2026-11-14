import Foundation
import CamLinkCore

/// Sony Alpha "PC Remote" protocol over PTP (port of SonyCamera.kt). All methods block and must be called from a
/// single background thread at a time.
final class SonyCamera {
    let ptp: PtpTransport

    /// Last read current value of every Sony property.
    private(set) var props: [Int: Int64] = [:]
    /// Full descriptors from the last read (current value, allowed values, enabled flag).
    private(set) var propInfo: [Int: PropInfo] = [:]
    private(set) var protocolVersion = 200

    init(ptp: PtpTransport) { self.ptp = ptp }

    func connect() throws {
        var lastError: Error?
        for attempt in 1...3 {
            do { try handshake(); return } catch {
                lastError = error
                DebugLog.d("Attempt \(attempt) failed: \(error.localizedDescription)")
                if attempt < 3 { Thread.sleep(forTimeInterval: 0.5 * Double(attempt)) }
            }
        }
        throw lastError!
    }

    private func handshake() throws {
        // ImageCaptureCore already sent GetDeviceInfo and opened the PTP session (it owns the session, so we never
        // send OpenSession / CloseSession ourselves). Go straight to Sony's three-phase SDIO connect.
        DebugLog.d(String(format: "SDIO connect 1 -> 0x%04X", try ptp.call(Sony.OC_SDIO_CONNECT, [1, 0, 0]).code))
        DebugLog.d(String(format: "SDIO connect 2 -> 0x%04X", try ptp.call(Sony.OC_SDIO_CONNECT, [2, 0, 0]).code))
        var info: Data?
        for _ in 0..<20 {
            info = try ptp.call(Sony.OC_SDIO_GET_EXT_DEVICE_INFO, [Sony.PROTOCOL_300, 1]).data
            if let i = info, i.count >= 2 { break }
            Thread.sleep(forTimeInterval: 0.05)
        }
        if let i = info, i.count >= 2 { protocolVersion = PtpCodec.le16(i, 0) }
        DebugLog.d("Ext device info: protocol \(protocolVersion) (\(info?.count ?? 0) bytes)")
        DebugLog.d(String(format: "SDIO connect 3 -> 0x%04X", try ptp.call(Sony.OC_SDIO_CONNECT, [3, 0, 0]).code))

        // Tell the camera the app (not the body dials) has priority, like Sony's own software does.
        _ = try? setProp(Sony.DPC_PRIORITY_MODE, Ptp.DT_INT8, 1)
        // Right after the handshake the body reports placeholder (zero) values; wait until they are real.
        let until = Date().addingTimeInterval(2)
        while (try refreshProps()[Sony.DPC_FOCUS_MODE] ?? 0) == 0 && Date() < until { Thread.sleep(forTimeInterval: 0.1) }

        // Images are only transferred when the save destination includes the PC (1 = PC, 0x10 = camera, 0x11 = both).
        if props[Sony.DPC_STORE_DESTINATION] == 0x10 { _ = try? setProp(Sony.DPC_STORE_DESTINATION, Ptp.DT_UINT16, 0x11) }
        DebugLog.d("Connected: \(props.count) properties, focus mode=\(props[Sony.DPC_FOCUS_MODE].map(String.init) ?? "?"), save dest=\(props[Sony.DPC_STORE_DESTINATION].map { String(format: "0x%llX", $0) } ?? "?")")
    }

    // ------------------------------------------------------------------ properties

    @discardableResult
    func refreshProps() throws -> [Int: Int64] {
        guard let d = try ptp.call(Sony.OC_SDIO_GET_ALL_EXT_PROP_INFO).data else { return props }
        let info = SonyParse.allProps(d)
        propInfo = info
        props = info.mapValues { $0.current }
        return props
    }

    @discardableResult
    func setProp(_ code: Int, _ type: Int, _ value: Int64) throws -> PtpResponse {
        try ptp.call(Sony.OC_SDIO_SET_EXT_PROP, [UInt32(code)], sendData: PtpCodec.pack(type: type, value: value))
    }

    @discardableResult
    func control(_ code: Int, _ type: Int, _ value: Int64) throws -> PtpResponse {
        try ptp.call(Sony.OC_SDIO_CONTROL_DEVICE, [UInt32(code)], sendData: PtpCodec.pack(type: type, value: value))
    }

    // ------------------------------------------------------------------ live view

    /// The current live view JPEG, or nil if not ready yet.
    func liveView() throws -> Data? {
        let r = try ptp.transact(Ptp.OC_GET_OBJECT, [Sony.HANDLE_LIVEVIEW], timeout: 3)
        if !r.ok {
            if [Ptp.RC_ACCESS_DENIED, Ptp.RC_INVALID_OBJECT_HANDLE, Ptp.RC_DEVICE_BUSY].contains(r.code) { return nil }
            throw PtpError(r.code, String(format: "Live view failed: 0x%04X", r.code))
        }
        guard let d = r.data else { return nil }
        return SonyParse.liveViewJpeg(d)
    }

    // ------------------------------------------------------------------ focus

    /// Moves the Flexible Spot AF point. `fx`, `fy` are fractions (0..1) of the live view frame.
    /// The A7 III has no 0xD2DC (AF area position) control; it exposes the spot position as 0xD232 (settable)
    /// mirrored by 0xD230 (current). Newer bodies use 0xD2DC, so that is tried when present.
    func setAfPoint(_ fx: Double, _ fy: Double) throws {
        // The position only applies to the "Flexible Spot" family of focus areas (codes >= 0x101).
        if let area = props[Sony.DPC_FOCUS_AREA], area < 0x101 {
            _ = try? setProp(Sony.DPC_FOCUS_AREA, Ptp.DT_UINT16, 0x0102) // Flexible Spot: M
            try refreshProps()
        }
        let before = props[Sony.DPC_FOCUS_POS_CURRENT]
        if propInfo[Sony.DPC_AF_AREA_POSITION] != nil {
            let x = min(max(Int(fx * 640), 0), 639), y = min(max(Int(fy * 480), 0), 479)
            try control(Sony.DPC_AF_AREA_POSITION, Ptp.DT_UINT32, (Int64(x) << 16) | Int64(y))
            DebugLog.d("AF point via D2DC -> \(x),\(y)")
            return
        }
        let x = min(max(Int(fx * Double(Sony.FOCUS_W)), 0), Sony.FOCUS_W - 1)
        let y = min(max(Int(fy * Double(Sony.FOCUS_H)), 0), Sony.FOCUS_H - 1)
        let v = (Int64(x) << 16) | Int64(y)
        let r1 = try ptp.transact(Sony.OC_SDIO_SET_EXT_PROP, [UInt32(Sony.DPC_FOCUS_POS_SET)], sendData: PtpCodec.pack(type: Ptp.DT_UINT32, value: v)).code
        try refreshProps()
        var after = props[Sony.DPC_FOCUS_POS_CURRENT]
        DebugLog.d(String(format: "AF point %d,%d: set D232 -> 0x%04X, D230 %@ -> %@", x, y, r1, fmtPos(before), fmtPos(after)))
        if after != v {
            // Some firmware only accepts it as a control (button-style) value.
            let r2 = try ptp.transact(Sony.OC_SDIO_CONTROL_DEVICE, [UInt32(Sony.DPC_FOCUS_POS_SET)], sendData: PtpCodec.pack(type: Ptp.DT_UINT32, value: v)).code
            try refreshProps()
            after = props[Sony.DPC_FOCUS_POS_CURRENT]
            DebugLog.d(String(format: "AF point retry as control -> 0x%04X, D230 now %@", r2, fmtPos(after)))
        }
    }

    private func fmtPos(_ v: Int64?) -> String { v.map { "\($0 >> 16),\($0 & 0xFFFF)" } ?? "?" }

    /// Turns a camera "dial" by `notches` (aperture, shutter, ISO, exposure comp).
    /// Sony protocol 2 bodies change these by stepping: 0x01 = one notch up, 0xFF = one notch down.
    func step(_ code: Int, _ notches: Int) throws {
        let v = notches >= 0 ? min(notches, 0x7F) : 0x100 + max(notches, -0x7F)
        try control(code, Ptp.DT_UINT8, Int64(v))
    }

    /// Sets a property to an exact value (white balance, focus mode, AF area).
    func setValue(_ code: Int, _ value: Int64) throws {
        try setProp(code, propInfo[code]?.type ?? Ptp.DT_UINT16, value)
    }

    func halfPress(_ down: Bool) throws { try control(Sony.DPC_SHUTTER_HALF, Ptp.DT_UINT16, down ? Sony.PRESS : Sony.RELEASE) }
    func fullPress(_ down: Bool) throws { try control(Sony.DPC_SHUTTER_FULL, Ptp.DT_UINT16, down ? Sony.PRESS : Sony.RELEASE) }

    func focusInfo() -> FocusInfo? { FocusInfo.from(props: props) }

    /// Focus state from the last property read: 2/3/6 = in focus (1 idle, 5 focusing on an A7 III).
    var isFocusLocked: Bool { [2, 3, 6].contains(props[Sony.DPC_FOCUS_FOUND] ?? 0) }
    var isManualFocus: Bool { props[Sony.DPC_FOCUS_MODE] == 1 }

    func recover() { ptp.recover() }

    /// Number of finished images waiting in the camera's transfer buffer (0 if none).
    var pendingImages: Int { SonyParse.pendingImages(props[Sony.DPC_OBJECT_IN_MEMORY]) }

    struct Image { let fileName: String; let bytes: Data }

    /// Downloads the next captured image byte-for-byte (original JPEG/ARW exactly as the camera wrote it).
    func downloadImage() throws -> Image {
        guard let info = try ptp.call(Ptp.OC_GET_OBJECT_INFO, [Sony.HANDLE_CAPTURED]).data else {
            throw PtpError(Ptp.RC_GENERAL_ERROR, "Camera sent no object info")
        }
        let name = SonyParse.fileName(objectInfo: info) ?? "DSC_\(Int(Date().timeIntervalSince1970 * 1000)).JPG"
        // No GetThumb here: the A7 III stalls the pipe on it, which then broke the image download.
        guard let bytes = try ptp.call(Ptp.OC_GET_OBJECT, [Sony.HANDLE_CAPTURED], timeout: 60).data else {
            throw PtpError(Ptp.RC_GENERAL_ERROR, "Camera sent no image data")
        }
        return Image(fileName: name, bytes: bytes)
    }
}

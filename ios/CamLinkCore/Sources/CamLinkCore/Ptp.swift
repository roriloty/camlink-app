import Foundation

/// PTP constants (same values as `Ptp` in the Android app's PtpUsb.kt).
public enum Ptp {
    public static let TYPE_COMMAND = 1
    public static let TYPE_DATA = 2
    public static let TYPE_RESPONSE = 3
    public static let TYPE_EVENT = 4

    public static let RC_OK = 0x2001
    public static let RC_GENERAL_ERROR = 0x2002
    public static let RC_INVALID_OBJECT_HANDLE = 0x2009
    public static let RC_ACCESS_DENIED = 0x200F
    public static let RC_DEVICE_BUSY = 0x2019
    public static let RC_SESSION_ALREADY_OPEN = 0x201E

    public static let OC_GET_DEVICE_INFO = 0x1001
    public static let OC_OPEN_SESSION = 0x1002
    public static let OC_CLOSE_SESSION = 0x1003
    public static let OC_GET_OBJECT_INFO = 0x1008
    public static let OC_GET_OBJECT = 0x1009
    /// Never sent: the A7 III stalls the pipe on GetThumb, which then breaks the image download.
    public static let OC_GET_THUMB = 0x100A

    public static let DT_INT8 = 0x0001
    public static let DT_UINT8 = 0x0002
    public static let DT_INT16 = 0x0003
    public static let DT_UINT16 = 0x0004
    public static let DT_INT32 = 0x0005
    public static let DT_UINT32 = 0x0006
    public static let DT_STR = 0xFFFF
}

public struct PtpError: Error, LocalizedError {
    public let code: Int
    public let message: String
    public init(_ code: Int, _ message: String) { self.code = code; self.message = message }
    public var errorDescription: String? { message }
}

/// PTP response: response code, response params and the data phase (if the device sent one).
public struct PtpResponse {
    public let code: Int
    public let params: [UInt32]
    public let data: Data?
    public var ok: Bool { code == Ptp.RC_OK }
    public init(code: Int, params: [UInt32], data: Data?) { self.code = code; self.params = params; self.data = data }
}

/// Little-endian helpers and the PTP container layout.
public enum PtpCodec {
    public static func le16(_ d: Data, _ o: Int) -> Int {
        let b = d.startIndex + o
        return Int(d[b]) | (Int(d[b + 1]) << 8)
    }
    public static func le32(_ d: Data, _ o: Int) -> UInt32 {
        let b = d.startIndex + o
        return UInt32(d[b]) | (UInt32(d[b + 1]) << 8) | (UInt32(d[b + 2]) << 16) | (UInt32(d[b + 3]) << 24)
    }

    /// A PTP operation request container: u32 length, u16 type (1), u16 code, u32 transaction id, u32 params.
    public static func command(op: Int, transactionId: UInt32, params: [UInt32]) -> Data {
        var d = Data()
        appendLE32(&d, UInt32(12 + 4 * params.count))
        appendLE16(&d, UInt16(Ptp.TYPE_COMMAND))
        appendLE16(&d, UInt16(truncatingIfNeeded: op))
        appendLE32(&d, transactionId)
        for p in params { appendLE32(&d, p) }
        return d
    }

    public static func appendLE16(_ d: inout Data, _ v: UInt16) { d.append(UInt8(v & 0xFF)); d.append(UInt8(v >> 8)) }
    public static func appendLE32(_ d: inout Data, _ v: UInt32) {
        d.append(UInt8(v & 0xFF)); d.append(UInt8((v >> 8) & 0xFF)); d.append(UInt8((v >> 16) & 0xFF)); d.append(UInt8(v >> 24))
    }

    /// Value of `type` packed little-endian, as the camera expects it in a SetExtDeviceProp data phase.
    public static func pack(type: Int, value: Int64) -> Data {
        let size: Int
        switch type {
        case Ptp.DT_INT8, Ptp.DT_UINT8: size = 1
        case Ptp.DT_INT16, Ptp.DT_UINT16: size = 2
        default: size = 4
        }
        return Data((0..<size).map { UInt8(truncatingIfNeeded: value >> (8 * Int64($0))) })
    }

    /// Parses a response container (code + params). Returns nil if `d` isn't one.
    public static func parseResponse(_ d: Data) -> (code: Int, params: [UInt32])? {
        guard d.count >= 12, le16(d, 4) == Ptp.TYPE_RESPONSE else { return nil }
        let len = min(Int(le32(d, 0)), d.count)
        let np = max(0, (len - 12) / 4)
        return (le16(d, 6), (0..<np).map { le32(d, 12 + 4 * $0) })
    }

    /// Parses an event container (code + params). Returns nil if `d` isn't one.
    public static func parseEvent(_ d: Data) -> (code: Int, params: [UInt32])? {
        guard d.count >= 12, le16(d, 4) == Ptp.TYPE_EVENT else { return nil }
        let len = min(Int(le32(d, 0)), d.count)
        let np = max(0, (len - 12) / 4)
        return (le16(d, 6), (0..<np).map { le32(d, 12 + 4 * $0) })
    }

    /// The payload of a data phase. ImageCaptureCore normally hands over the bare payload; if a full data
    /// container (with its 12-byte header) comes back instead, the header is removed.
    public static func payload(_ d: Data) -> Data {
        if d.count >= 12, le16(d, 4) == Ptp.TYPE_DATA, Int(le32(d, 0)) == d.count {
            return d.subdata(in: (d.startIndex + 12)..<d.endIndex)
        }
        return d
    }

    public static func hex(_ d: Data, max: Int = 32) -> String {
        d.prefix(max).map { String(format: "%02X", $0) }.joined(separator: " ") + (d.count > max ? " …" : "")
    }
}

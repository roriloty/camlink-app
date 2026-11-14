import Foundation
import CoreGraphics

/// Sony Alpha "PC Remote" protocol constants and the parsers from the Android app's SonyCamera.kt.
public enum Sony {
    public static let OC_SDIO_CONNECT = 0x9201
    public static let OC_SDIO_GET_EXT_DEVICE_INFO = 0x9202
    public static let OC_SDIO_SET_EXT_PROP = 0x9205
    public static let OC_SDIO_CONTROL_DEVICE = 0x9207
    public static let OC_SDIO_GET_ALL_EXT_PROP_INFO = 0x9209

    public static let HANDLE_CAPTURED: UInt32 = 0xFFFF_C001
    public static let HANDLE_LIVEVIEW: UInt32 = 0xFFFF_C002

    public static let EVENT_OBJECT_ADDED = 0xC201
    public static let EVENT_PROP_CHANGED = 0xC203

    public static let DPC_FOCUS_MODE = 0x500A
    public static let DPC_FOCUS_FOUND = 0xD213
    public static let DPC_OBJECT_IN_MEMORY = 0xD215
    public static let DPC_BATTERY = 0xD218
    public static let DPC_STORE_DESTINATION = 0xD222
    public static let DPC_FOCUS_AREA = 0xD22C
    public static let DPC_PRIORITY_MODE = 0xD25A
    public static let DPC_SHUTTER_HALF = 0xD2C1
    public static let DPC_SHUTTER_FULL = 0xD2C2
    public static let DPC_MANUAL_FOCUS_ADJUST = 0xD2D1
    public static let DPC_AF_AREA_POSITION = 0xD2DC
    /// A7 III-era bodies: current Flexible Spot position (read) and its setter. Value = x<<16 | y.
    public static let DPC_FOCUS_POS_CURRENT = 0xD230
    public static let DPC_FOCUS_POS_SET = 0xD232

    public static let DPC_WHITE_BALANCE = 0x5005
    public static let DPC_F_NUMBER = 0x5007
    public static let DPC_EXPOSURE_PROGRAM = 0x500E
    public static let DPC_EXPOSURE_COMP = 0x5010
    public static let DPC_SHUTTER_SPEED = 0xD20D
    public static let DPC_ISO = 0xD21E

    public static let PRESS: Int64 = 2
    public static let RELEASE: Int64 = 1

    /// Flexible Spot coordinate space on the A7 III (centre reads 320,214 → 640 x 428, 3:2).
    public static let FOCUS_W = 640
    public static let FOCUS_H = 428

    public static let PROTOCOL_300: UInt32 = 300
}

/// One device property as reported by the camera.
public struct PropInfo: Equatable {
    public let code: Int
    public let type: Int
    public let getSet: Int
    public let enabled: Int
    public let current: Int64
    public let enumValues: [Int64]?
    /// Greyed out on the camera (e.g. aperture with a manual lens, shutter in A mode).
    public var isDisabled: Bool { enabled == 0 }
}

public enum SonyParse {
    private struct Reader {
        let d: [UInt8]
        var pos = 0
        var remaining: Int { d.count - pos }
        mutating func u8() throws -> Int { guard pos + 1 <= d.count else { throw E.eof }; defer { pos += 1 }; return Int(d[pos]) }
        mutating func u16() throws -> Int {
            guard pos + 2 <= d.count else { throw E.eof }
            defer { pos += 2 }; return Int(d[pos]) | (Int(d[pos + 1]) << 8)
        }
        mutating func u32() throws -> UInt32 {
            guard pos + 4 <= d.count else { throw E.eof }
            defer { pos += 4 }
            return UInt32(d[pos]) | (UInt32(d[pos + 1]) << 8) | (UInt32(d[pos + 2]) << 16) | (UInt32(d[pos + 3]) << 24)
        }
        mutating func u64() throws -> UInt64 { let lo = UInt64(try u32()); let hi = UInt64(try u32()); return lo | (hi << 32) }
        func peek16() -> Int? { pos + 2 <= d.count ? Int(d[pos]) | (Int(d[pos + 1]) << 8) : nil }
        mutating func skip(_ n: Int) throws { guard pos + n <= d.count else { throw E.eof }; pos += n }
        enum E: Error { case eof, badType }
    }

    private static func readValue(_ b: inout Reader, _ type: Int) throws -> Int64 {
        switch type {
        case 0x0001: return Int64(Int8(truncatingIfNeeded: try b.u8()))
        case 0x0002: return Int64(try b.u8())
        case 0x0003: return Int64(Int16(truncatingIfNeeded: try b.u16()))
        case 0x0004: return Int64(try b.u16())
        case 0x0005: return Int64(Int32(bitPattern: try b.u32()))
        case 0x0006: return Int64(try b.u32())
        case 0x0007, 0x0008: return Int64(bitPattern: try b.u64())
        case 0x0009, 0x000A: let lo = try b.u64(); _ = try b.u64(); return Int64(bitPattern: lo)
        case Ptp.DT_STR: let n = try b.u8(); try b.skip(2 * n); return 0
        default:
            if type & 0x4000 != 0 {
                let n = Int(try b.u32())
                let elem = type & 0x0FFF
                for _ in 0..<n { _ = try readValue(&b, elem) }
                return 0
            }
            throw Reader.E.badType
        }
    }

    /// Parses the SDIO GetAllExtDevicePropInfo (0x9209) blob into code -> descriptor.
    public static func allProps(_ data: Data) -> [Int: PropInfo] {
        var out: [Int: PropInfo] = [:]
        guard data.count > 8 else { return out }
        var b = Reader(d: [UInt8](data), pos: 8) // u32 count + u32 reserved
        do {
            while b.remaining >= 6 {
                let code = try b.u16()
                let type = try b.u16()
                let getSet = try b.u8()
                let enabled = try b.u8()
                _ = try readValue(&b, type) // factory default
                if type == Ptp.DT_STR && b.remaining == 0 { break }
                let cur = try readValue(&b, type)
                var enums: [Int64]? = nil
                if b.remaining == 0 {
                    out[code] = PropInfo(code: code, type: type, getSet: getSet, enabled: enabled, current: cur, enumValues: nil)
                    break
                }
                let form = try b.u8()
                switch form {
                case 1: for _ in 0..<3 { _ = try readValue(&b, type) }
                case 2:
                    let n = try b.u16()
                    var list: [Int64] = []
                    for _ in 0..<n { list.append(try readValue(&b, type)) }
                    enums = list
                default: break
                }
                // Newer bodies append a second ("settable values") enum list; a property code would be >= 0x200.
                if b.remaining >= 2, let peek = b.peek16(), peek < 0x200, form == 2 {
                    _ = try b.u16()
                    var list: [Int64] = []
                    for _ in 0..<peek { list.append(try readValue(&b, type)) }
                    enums = list
                }
                out[code] = PropInfo(code: code, type: type, getSet: getSet, enabled: enabled, current: cur, enumValues: enums)
            }
        } catch {
            // Truncated / unknown layout: keep what was parsed so far.
        }
        return out
    }

    /// ObjectInfo dataset: the Filename string starts at byte 52.
    public static func fileName(objectInfo oi: Data) -> String? {
        let d = [UInt8](oi)
        guard d.count >= 53 else { return nil }
        let n = Int(d[52])
        guard n > 0, 53 + 2 * n <= d.count else { return nil }
        var units: [UInt16] = []
        for i in 0..<n {
            let c = UInt16(d[53 + 2 * i]) | (UInt16(d[54 + 2 * i]) << 8)
            if c == 0 { break }
            units.append(c)
        }
        let s = String(utf16CodeUnits: units, count: units.count)
        return s.trimmingCharacters(in: .whitespaces).isEmpty ? nil : s
    }

    /// The JPEG inside a live view object: Sony prepends u32 offset to the JPEG and u32 JPEG size.
    public static func liveViewJpeg(_ data: Data) -> Data? {
        let d = [UInt8](data)
        guard d.count >= 8 else { return nil }
        let off = Int(UInt32(d[0]) | (UInt32(d[1]) << 8) | (UInt32(d[2]) << 16) | (UInt32(d[3]) << 24))
        let len = Int(UInt32(d[4]) | (UInt32(d[5]) << 8) | (UInt32(d[6]) << 16) | (UInt32(d[7]) << 24))
        if off >= 8, off < d.count - 1, d[off] == 0xFF, d[off + 1] == 0xD8 {
            let n = (len > 0 && off + len <= d.count) ? len : d.count - off
            return Data(d[off..<(off + n)])
        }
        if d.count >= 2 {
            for i in 0..<(d.count - 1) where d[i] == 0xFF && d[i + 1] == 0xD8 { return Data(d[i...]) }
        }
        return nil
    }

    /// Number of finished images waiting in the camera's transfer buffer (0 if none), from 0xD215.
    public static func pendingImages(_ v: Int64?) -> Int {
        guard let v else { return 0 }
        return v >= 0x8000 ? max(1, Int(v - 0x8000)) : 0
    }
}

/// Kind of AF area, from 0xD22C.
public enum AreaKind { case wide, zone, center, spot }

/// The camera's AF area and frame, as far as the body reports it over USB.
public struct FocusInfo: Equatable {
    public let kind: AreaKind
    /// Spot centre as a fraction of the frame (SPOT only).
    public let spot: CGPoint?
    /// Spot size as a fraction of the frame width.
    public let size: Double
    /// 1 idle, 5 focusing, 2/3/6 in focus, 7 failed (A7 III values of 0xD213).
    public let state: Int
    public var locked: Bool { state == 2 || state == 3 || state == 6 }
    public var focusing: Bool { state == 5 }
    public var failed: Bool { state == 7 }

    public static func from(props: [Int: Int64]) -> FocusInfo? {
        guard let area = props[Sony.DPC_FOCUS_AREA] else { return nil }
        let state = Int(props[Sony.DPC_FOCUS_FOUND] ?? 1)
        let low = Int(area & 0xFF)
        let isSpot = area >= 0x100 && !(area >= 0x200 && low <= 3)
        let kind: AreaKind = isSpot ? .spot : (low == 1 ? .wide : (low == 2 ? .zone : .center))
        var spot: CGPoint? = nil
        if kind == .spot, let p = props[Sony.DPC_FOCUS_POS_SET] {
            spot = CGPoint(x: min(max(Double(p >> 16) / Double(Sony.FOCUS_W), 0), 1),
                           y: min(max(Double(p & 0xFFFF) / Double(Sony.FOCUS_H), 0), 1))
        }
        let sz: Double
        switch area >= 0x200 ? low - 3 : low { case 1: sz = 0.055; case 2: sz = 0.085; case 3: sz = 0.12; default: sz = 0.085 }
        return FocusInfo(kind: kind, spot: spot, size: sz, state: state)
    }
}

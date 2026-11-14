import Foundation

/// One exposure setting shown in the control bar.
public struct SettingUi: Equatable, Identifiable {
    public var id: Int { code }
    public let code: Int
    public let label: String
    public let value: String
    /// False when the body greys it out (e.g. shutter in A mode, EV when the EV dial is not at 0).
    public let adjustable: Bool
    /// Read-only by design (exposure mode is the physical dial on the body).
    public let readOnly: Bool
    public let raw: Int64
    /// For list settings (WB, focus mode, AF area): every choice the camera offers, as (value, label).
    public let options: [Option]

    public struct Option: Equatable, Hashable { public let value: Int64; public let label: String }
}

/// Turns raw Sony property values into the labels a photographer expects (port of Exposure.kt;
/// value encodings verified against an ILCE-7M3).
public enum Exposure {
    /// Order of the controls in the UI.
    public static let CODES = [
        Sony.DPC_EXPOSURE_PROGRAM, Sony.DPC_F_NUMBER, Sony.DPC_SHUTTER_SPEED,
        Sony.DPC_ISO, Sony.DPC_EXPOSURE_COMP, Sony.DPC_WHITE_BALANCE,
        Sony.DPC_FOCUS_MODE, Sony.DPC_FOCUS_AREA,
    ]

    /// WB, focus mode and AF area are lists (set directly); the others are dials (stepped one notch at a time).
    public static func isList(_ code: Int) -> Bool {
        code == Sony.DPC_WHITE_BALANCE || code == Sony.DPC_FOCUS_AREA || code == Sony.DPC_FOCUS_MODE
    }

    /// Reported as display-only on protocol-2 bodies but accepted when set directly (as libgphoto2 does).
    static let ALWAYS_SETTABLE: Set<Int> = [Sony.DPC_FOCUS_MODE]

    public static func build(_ info: [Int: PropInfo]) -> [SettingUi] {
        CODES.compactMap { code in
            guard let p = info[code] else { return nil }
            let readOnly = code == Sony.DPC_EXPOSURE_PROGRAM
            let adjustable = !readOnly && (p.enabled == 1 || (ALWAYS_SETTABLE.contains(code) && p.enumValues != nil))
            var options: [SettingUi.Option] = []
            if isList(code) {
                var seen = Set<Int64>()
                for v in p.enumValues ?? [] where seen.insert(v).inserted { options.append(.init(value: v, label: format(code, v))) }
            }
            return SettingUi(code: code, label: label(code), value: format(code, p.current), adjustable: adjustable,
                             readOnly: readOnly, raw: p.current, options: options)
        }
    }

    public static func label(_ code: Int) -> String {
        switch code {
        case Sony.DPC_EXPOSURE_PROGRAM: return "MODE"
        case Sony.DPC_F_NUMBER: return "APERTURE"
        case Sony.DPC_SHUTTER_SPEED: return "SHUTTER"
        case Sony.DPC_ISO: return "ISO"
        case Sony.DPC_EXPOSURE_COMP: return "EV"
        case Sony.DPC_WHITE_BALANCE: return "WB"
        case Sony.DPC_FOCUS_AREA: return "AF AREA"
        case Sony.DPC_FOCUS_MODE: return "FOCUS"
        default: return String(format: "%04X", code)
        }
    }

    public static func format(_ code: Int, _ v: Int64) -> String {
        switch code {
        case Sony.DPC_EXPOSURE_PROGRAM: return mode(v)
        case Sony.DPC_F_NUMBER: return v <= 0 ? "--" : "F" + trim(Double(v) / 100.0)
        case Sony.DPC_SHUTTER_SPEED: return shutter(v)
        case Sony.DPC_ISO: return iso(v)
        case Sony.DPC_EXPOSURE_COMP: return ev(v)
        case Sony.DPC_WHITE_BALANCE: return whiteBalance(v)
        case Sony.DPC_FOCUS_AREA: return focusArea(v)
        case Sony.DPC_FOCUS_MODE: return focusMode(v)
        default: return String(v)
        }
    }

    public static func focusMode(_ v: Int64) -> String {
        switch v {
        case 1: return "MF"; case 2: return "AF-S"; case 3: return "AF Macro"
        case 0x8004: return "AF-C"; case 0x8005: return "AF-A"; case 0x8006: return "DMF"
        case 0x8008: return "AF-D"; case 0x8009: return "Preset"
        default: return "AF"
        }
    }

    /// Focus area codes as listed by the A7 III (0x2xx = the same areas with Lock-on AF).
    public static func focusArea(_ v: Int64) -> String {
        let base: String
        switch v & 0xFF {
        case 0x01: base = v < 0x100 ? "Wide" : "Spot S"
        case 0x02: base = v < 0x100 ? "Zone" : "Spot M"
        case 0x03: base = v < 0x100 ? "Center" : "Spot L"
        case 0x04: base = "Exp. Spot"
        case 0x05: base = "Spot M"
        case 0x06: base = "Spot L"
        case 0x07: base = "Exp. Spot"
        default: base = String(format: "0x%llX", v)
        }
        switch v {
        case 0x201: return "Lock Wide"; case 0x202: return "Lock Zone"; case 0x203: return "Lock Center"
        case 0x204: return "Lock Spot S"; case 0x205: return "Lock Spot M"; case 0x206: return "Lock Spot L"
        case 0x207: return "Lock Exp."
        default: return base
        }
    }

    static func trim(_ d: Double) -> String {
        let r = (d * 10).rounded() / 10.0
        return r == r.rounded(.down) ? String(Int64(r)) : String(r)
    }

    static func mode(_ v: Int64) -> String {
        switch v {
        case 1: return "M"; case 2: return "P"; case 3: return "A"; case 4: return "S"
        case 0x8000: return "AUTO"; case 0x8001: return "AUTO+"
        case 0x8050: return "Movie P"; case 0x8051: return "Movie A"; case 0x8052: return "Movie S"; case 0x8053: return "Movie M"
        case 0x8084: return "S&Q P"; case 0x8085: return "S&Q A"; case 0x8086: return "S&Q S"; case 0x8087: return "S&Q M"
        default: return "SCN"
        }
    }

    /// Encoded as numerator<<16 | denominator; 0 = BULB.
    static func shutter(_ v: Int64) -> String {
        if v == 0 { return "BULB" }
        let n = Int(v >> 16), d = Int(v & 0xFFFF)
        if d == 0 { return "--" }
        if d == 1 { return "\(n)\"" }
        if n == 1 { return "1/\(d)" }
        return trim(Double(n) / Double(d)) + "\""
    }

    /// 0xFFFFFF = auto; the low 24 bits are the ISO; upper bits flag multi-frame NR.
    static func iso(_ v: Int64) -> String {
        let base = v & 0xFFFFFF
        if base == 0xFFFFFF { return "AUTO" }
        return String(base) + ((v >> 24) != 0 ? " NR" : "")
    }

    /// Signed thousandths of a stop.
    static func ev(_ v: Int64) -> String {
        let s = Double(Int16(truncatingIfNeeded: v)) / 1000.0
        if s > 0 { return "+" + String(format: "%.1f", s) }
        if s < 0 { return String(format: "%.1f", s) }
        return "±0.0"
    }

    static func whiteBalance(_ v: Int64) -> String {
        switch v {
        case 0x0002: return "Auto"; case 0x0004: return "Daylight"; case 0x8011: return "Shade"
        case 0x8010: return "Cloudy"; case 0x0006: return "Tungsten"; case 0x8001: return "Fluor. Warm"
        case 0x8002: return "Fluor. Cool"; case 0x8003: return "Fluor. Day W"; case 0x8004: return "Fluor. Day"
        case 0x0007: return "Flash"; case 0x8030: return "Underwater"; case 0x8012: return "K / Filter"
        case 0x8020: return "Custom 1"; case 0x8021: return "Custom 2"; case 0x8022: return "Custom 3"
        default: return String(format: "0x%llX", v)
        }
    }
}

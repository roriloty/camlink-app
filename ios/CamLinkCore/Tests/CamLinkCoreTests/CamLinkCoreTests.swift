import XCTest
@testable import CamLinkCore

/// The Swift auto-edit must give the same pixels as the Kotlin/Python implementation. The golden files are the
/// Python reference's output (D:/camlink-autoedit/work/autoedit_ref.py, which the Kotlin code matches) for three
/// synthetic scenes made with integer-only formulas, so the inputs are identical everywhere.
final class AutoEditGoldenTests: XCTestCase {
    static func noise(_ x: Int, _ y: Int) -> Int { (x * 131 + y * 197 + (x * y % 97) * 7) % 23 }

    static func px(_ scene: String, _ x: Int, _ y: Int, _ w: Int, _ h: Int) -> (Int, Int, Int) {
        let u = x * 1000 / w, v = y * 1000 / h, n = noise(x, y)
        switch scene {
        case "day":
            if (u - 700) * (u - 700) + (v - 200) * (v - 200) < 3600 { return (255, 255, 250) }
            if v < 450 { return (90 + v * 100 / 450 + n % 5, 140 + v * 60 / 450 + n % 4, 200 + v * 40 / 450 + n % 6) }
            return (60 + (u * v % 53) + n, 80 + 2 * n, 40 + n)
        case "night":
            for (cu, cv) in [(200, 300), (520, 610), (810, 420), (350, 820)] where (u - cu) * (u - cu) + (v - cv) * (v - cv) < 144 {
                return (255, 220, 150)
            }
            if v > 500 && (u / 50 + v / 50) % 7 == 0 { return (180, 160, 90) }
            return (6 + n % 5, 6 + n % 4, 10 + n % 6)
        default:
            return (110 + u / 20 + n % 7, 120 + v / 25 + n % 5, 115 + n % 9)
        }
    }

    static func image(_ scene: String, _ w: Int, _ h: Int) -> [UInt32] {
        var out = [UInt32](repeating: 0, count: w * h)
        for y in 0..<h { for x in 0..<w {
            let (r, g, b) = px(scene, x, y, w, h)
            out[y * w + x] = 0xFF00_0000 | (UInt32(r) << 16) | (UInt32(g) << 8) | UInt32(b)
        } }
        return out
    }

    func golden(_ scene: String) throws -> [UInt8] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "\(scene)_ref.rgb", withExtension: "deflate", subdirectory: "Golden"))
        let raw = try (Data(contentsOf: url) as NSData).decompressed(using: .zlib) as Data
        return [UInt8](raw.dropFirst(8))
    }

    func check(_ scene: String) throws {
        let small = Self.image(scene, 180, 120)
        var full = Self.image(scene, 360, 240)
        let plan = AutoEditCore.plan(small: small, w: 180, h: 120)
        full.withUnsafeMutableBufferPointer { p in
            var y = 0
            while y < 240 { let rows = min(256, 240 - y); AutoEditCore.render(p.baseAddress! + y * 360, width: 360, rows: rows, y0: y, fullH: 240, plan: plan); y += rows }
        }
        let ref = try golden(scene)
        XCTAssertEqual(ref.count, 360 * 240 * 3)
        var maxDiff = 0, differing = 0
        for i in 0..<(360 * 240) {
            let p = full[i]
            let mine = [Int((p >> 16) & 0xFF), Int((p >> 8) & 0xFF), Int(p & 0xFF)]
            var d = 0
            for c in 0..<3 { d = max(d, abs(mine[c] - Int(ref[3 * i + c]))) }
            if d > 0 { differing += 1 }
            maxDiff = max(maxDiff, d)
        }
        print("\(scene): night=\(plan.night) max diff \(maxDiff)/255, \(differing) of \(360 * 240) pixels differ")
        // Same tolerance the Kotlin port was verified with: at most 1/255 from floating-point library differences.
        XCTAssertLessThanOrEqual(maxDiff, 1, scene)
        XCTAssertLessThan(Double(differing) / Double(360 * 240), 0.001, scene)
    }

    func testDay() throws { try check("day") }
    func testNight() throws { try check("night") }
    func testFlat() throws { try check("flat") }
}

final class PtpParseTests: XCTestCase {
    func testCommandContainer() {
        let d = PtpCodec.command(op: 0x9201, transactionId: 5, params: [1, 0, 0])
        XCTAssertEqual([UInt8](d), [24, 0, 0, 0, 1, 0, 0x01, 0x92, 5, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0])
    }

    func testResponseAndPayload() {
        var r = Data(); PtpCodec.appendLE32(&r, 16); PtpCodec.appendLE16(&r, 3); PtpCodec.appendLE16(&r, 0x2001)
        PtpCodec.appendLE32(&r, 7); PtpCodec.appendLE32(&r, 0xABCD)
        let p = PtpCodec.parseResponse(r)
        XCTAssertEqual(p?.code, 0x2001); XCTAssertEqual(p?.params, [0xABCD])
        var d = Data(); PtpCodec.appendLE32(&d, 15); PtpCodec.appendLE16(&d, 2); PtpCodec.appendLE16(&d, 0x1009)
        PtpCodec.appendLE32(&d, 7); d.append(contentsOf: [1, 2, 3])
        XCTAssertEqual([UInt8](PtpCodec.payload(d)), [1, 2, 3])
        XCTAssertEqual([UInt8](PtpCodec.payload(Data([9, 9]))), [9, 9])
    }

    func testAllProps() {
        var d = Data(); PtpCodec.appendLE32(&d, 2); PtpCodec.appendLE32(&d, 0)
        // 0x5007 F-number, UINT16, enum form with 3 values
        PtpCodec.appendLE16(&d, 0x5007); PtpCodec.appendLE16(&d, 4); d.append(1); d.append(1)
        PtpCodec.appendLE16(&d, 0); PtpCodec.appendLE16(&d, 280); d.append(2); PtpCodec.appendLE16(&d, 3)
        PtpCodec.appendLE16(&d, 280); PtpCodec.appendLE16(&d, 400); PtpCodec.appendLE16(&d, 560)
        // 0xD215 pending images, UINT16, no form
        PtpCodec.appendLE16(&d, 0xD215); PtpCodec.appendLE16(&d, 4); d.append(0); d.append(1)
        PtpCodec.appendLE16(&d, 0); PtpCodec.appendLE16(&d, 0x8001); d.append(0)
        let p = SonyParse.allProps(d)
        XCTAssertEqual(p[0x5007]?.current, 280)
        XCTAssertEqual(p[0x5007]?.enumValues, [280, 400, 560])
        XCTAssertEqual(SonyParse.pendingImages(p[0xD215]?.current), 1)
        XCTAssertEqual(Exposure.format(Sony.DPC_F_NUMBER, 280), "F2.8")
        XCTAssertEqual(Exposure.format(Sony.DPC_SHUTTER_SPEED, (1 << 16) | 250), "1/250")
        XCTAssertEqual(Exposure.format(Sony.DPC_EXPOSURE_COMP, Int64(UInt16(bitPattern: -700))), "-0.7")
    }

    func testFileNameAndLiveView() {
        var oi = Data(count: 52); oi.append(4)
        for c in "A.JP".utf16 { oi.append(UInt8(c & 0xFF)); oi.append(UInt8(c >> 8)) }
        XCTAssertEqual(SonyParse.fileName(objectInfo: oi), "A.JP")
        var lv = Data(); PtpCodec.appendLE32(&lv, 10); PtpCodec.appendLE32(&lv, 3); lv.append(contentsOf: [0, 0, 0xFF, 0xD8, 0x11, 0x22])
        XCTAssertEqual([UInt8](SonyParse.liveViewJpeg(lv)!), [0xFF, 0xD8, 0x11])
    }
}

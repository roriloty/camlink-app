import Foundation

/// "Auto edit" in the user's Lightroom style: a line-for-line port of the Android app's
/// `app/src/main/java/app/camlink/usb/edit/AutoEditCore.kt`, which is checked against the Python reference
/// (D:/camlink-autoedit/work/autoedit_ref.py). Keep the arithmetic in the same order as the Kotlin code so the
/// results stay identical.
///
///  1. Highlights pulled down locally (blurred-luminance mask, detail kept)
///  2. Whites / blacks: darkest 0.1% → black, brightest 0.1% → white
///  3. Learned style curve on that stretched histogram (Auto, shadows, contrast)
///  4. Highlight guard: the top of the curve is eased down until (almost) nothing new clips.
///  5. Local contrast boost (fine + medium detail); it never pushes a colour channel to white.
///  6. Night rule: an almost all black frame with only small lights is kept as shot.
///
/// Pixels are packed ARGB `UInt32` values (0xAARRGGBB), the same layout as `android.graphics.Bitmap`.
public enum AutoEditCore {
    static let Q: [Double] = [0.1, 1.0, 5.0, 15.0, 30.0, 50.0, 70.0, 85.0, 95.0, 99.0, 99.9]
    static let MIN_SPAN = 0.35
    static let HL_K = 0.3
    static let HL_T0 = 0.45
    static let DETAIL_FINE = 0.15
    static let DETAIL_MID = 0.10
    static let DETAIL_FINE_FRAC = 0.0033
    static let DETAIL_MID_FRAC = 0.022
    static let NIGHT_DARK_LO = 0.85
    static let NIGHT_DARK_HI = 0.93
    static let NIGHT_P95_LO = 0.20
    static let NIGHT_P95_HI = 0.35
    static let NIGHT_SHADOW = 0.9
    static let CLIP_BUDGET = 0.0005
    static let CLIP_LEVEL = 0.985
    static let BASE_LONG = 160
    static let BASE_SIGMA_FRAC = 0.03
    public static let LUT_N = 1024

    /// Everything decided from a small copy of the photo; then applied to the full-size pixels.
    public final class Plan {
        public let base: [Double]; public let bw: Int; public let bh: Int
        public let lut: [Double]
        /// Low-pass luminance of the edited small copy (sw x sh), for the local contrast boost.
        public let lowF: [Double]; public let lowM: [Double]; public let sw: Int; public let sh: Int
        /// 0 = normal photo, 1 = night scene.
        public let night: Double

        init(base: [Double], bw: Int, bh: Int, lut: [Double], lowF: [Double], lowM: [Double], sw: Int, sh: Int, night: Double) {
            self.base = base; self.bw = bw; self.bh = bh; self.lut = lut
            self.lowF = lowF; self.lowM = lowM; self.sw = sw; self.sh = sh; self.night = night
        }
    }

    @inline(__always) static func lum(_ r: Double, _ g: Double, _ b: Double) -> Double {
        let a = 0.2126 * r
        let c = 0.7152 * g
        let d = 0.0722 * b
        return (a + c) + d
    }

    // ------------------------------------------------------------------ plan

    public static func plan(small: [UInt32], w: Int, h: Int, weights: [[Double]] = AutoEditWeights.W) -> Plan {
        let n = w * h
        var L = [Double](repeating: 0, count: n)
        for i in 0..<n { let p = small[i]; L[i] = lum(r(p), g(p), b(p)) }
        let (bs, bw, bh) = baseSmall(L, w, h)
        // Highlight recovery on the small copy, then statistics of the result.
        var Lp = [Double](repeating: 0, count: n)
        var pre = [Double](repeating: 0, count: n * 3)
        var wasClipped = [Bool](repeating: false, count: n)
        var rowB = [Double](repeating: 0, count: w)
        for y in 0..<h {
            upsampleRow(bs, bw, bh, y, w, h, &rowB)
            for x in 0..<w {
                let i = y * w + x
                let c = recover(small[i], rowB[x])
                pre[3 * i] = c.0; pre[3 * i + 1] = c.1; pre[3 * i + 2] = c.2
                Lp[i] = lum(c.0, c.1, c.2)
                let p = small[i]
                wasClipped[i] = jmax(r(p), jmax(g(p), b(p))) >= CLIP_LEVEL
            }
        }
        let sorted = Lp.sorted()
        let op = Q.map { percentile(sorted, $0) }
        let lo = op[0]
        let hi = jmax(op[Q.count - 1], op[0] + MIN_SPAN)
        let npct = op.map { clamp01(($0 - lo) / (hi - lo)) }
        var f1 = 0, f2 = 0, f3 = 0, f4 = 0, f5 = 0
        for v in Lp {
            if v > 0.6 { f1 += 1 }; if v > 0.8 { f2 += 1 }; if v > 0.95 { f3 += 1 }
            if v < 0.05 { f4 += 1 }; if v < 0.15 { f5 += 1 }
        }
        var x = [Double](repeating: 0, count: 1 + Q.count + 5 + 2)
        x[0] = 1.0
        for i in 0..<Q.count { x[1 + i] = npct[i] }
        let nn = Double(n)
        x[12] = Double(f1) / nn; x[13] = Double(f2) / nn; x[14] = Double(f3) / nn
        x[15] = Double(f4) / nn; x[16] = Double(f5) / nn
        x[17] = hi - lo; x[18] = npct[5] * npct[5]
        var ep = [Double](repeating: 0, count: Q.count)
        for j in 0..<Q.count {
            var s = 0.0
            for k in 0..<x.count { s += x[k] * weights[k][j] }
            ep[j] = clamp01(npct[j] + s)
            if j > 0 { ep[j] = jmax(ep[j], ep[j - 1]) }
        }
        // Night rule.
        var dark = 0
        for v in Lp where v < 0.1 { dark += 1 }
        let p95 = percentile(sorted, 95.0)
        let night = smoothstep(NIGHT_DARK_LO, NIGHT_DARK_HI, Double(dark) / Double(n)) *
            (1 - smoothstep(NIGHT_P95_LO, NIGHT_P95_HI, p95))
        if night > 0 {
            for j in 0..<Q.count {
                let target = Q[j] <= 85.0 ? op[j] * NIGHT_SHADOW : op[j]
                ep[j] = (1 - night) * ep[j] + night * target
                if j > 0 { ep[j] = jmax(ep[j], ep[j - 1]) }
            }
        }
        // Highlight guard: ease the top of the curve down until no detail newly blows out.
        var lut = buildLut(op, ep)
        for _ in 0..<40 {
            var clipped = 0
            let nl = Double(lut.count - 1)
            lut.withUnsafeBufferPointer { lb in
                for i in 0..<n where !wasClipped[i] {
                    let o = lutRgb(pre[3 * i], pre[3 * i + 1], pre[3 * i + 2], lb, nl)
                    if jmax(o.0, jmax(o.1, o.2)) >= CLIP_LEVEL { clipped += 1 }
                }
            }
            if Double(clipped) / Double(n) <= CLIP_BUDGET { break }
            for j in 7..<Q.count { ep[j] = ep[6] + (ep[j] - ep[6]) * 0.97 }
            lut = buildLut(op, ep)
        }
        // Low-pass copies of the edited small image for the local contrast boost.
        var Lo = [Double](repeating: 0, count: n)
        let nl = Double(lut.count - 1)
        lut.withUnsafeBufferPointer { lb in
            for i in 0..<n {
                let o = lutRgb(pre[3 * i], pre[3 * i + 1], pre[3 * i + 2], lb, nl)
                Lo[i] = lum(o.0, o.1, o.2)
            }
        }
        let lowF = gaussian(Lo, w, h, DETAIL_FINE_FRAC * Double(max(w, h)))
        let lowM = gaussian(Lo, w, h, DETAIL_MID_FRAC * Double(max(w, h)))
        return Plan(base: bs, bw: bw, bh: bh, lut: lut, lowF: lowF, lowM: lowM, sw: w, sh: h, night: night)
    }

    // ------------------------------------------------------------------ render

    /// Edits `rows` full-width rows starting at global row `y0`, in place. Safe to call for different rows from
    /// several threads at once.
    public static func render(_ pixels: UnsafeMutablePointer<UInt32>, width: Int, rows: Int, y0: Int, fullH: Int, plan: Plan) {
        var rowB = [Double](repeating: 0, count: width)
        var rowF = [Double](repeating: 0, count: width)
        var rowM = [Double](repeating: 0, count: width)
        let nl = Double(plan.lut.count - 1)
        let amount = 1 - plan.night
        plan.lut.withUnsafeBufferPointer { lb in
            for ry in 0..<rows {
                let y = y0 + ry
                upsampleRow(plan.base, plan.bw, plan.bh, y, width, fullH, &rowB)
                upsampleRow(plan.lowF, plan.sw, plan.sh, y, width, fullH, &rowF)
                upsampleRow(plan.lowM, plan.sw, plan.sh, y, width, fullH, &rowM)
                let row = pixels + ry * width
                for x in 0..<width {
                    let p = row[x]
                    let c = recover(p, rowB[x])
                    var o = lutRgb(c.0, c.1, c.2, lb, nl)
                    boost(&o, rowF[x], rowM[x], amount)
                    row[x] = (p & 0xFF00_0000) | (to8(o.0) << 16) | (to8(o.1) << 8) | to8(o.2)
                }
            }
        }
    }

    /// Local contrast: add back fine + medium detail, never touching deep blacks or pushing a channel to white.
    @inline(__always) static func boost(_ o: inout (Double, Double, Double), _ lowF: Double, _ lowM: Double, _ amount: Double) {
        if amount <= 0 { return }
        let L = lum(o.0, o.1, o.2)
        let d = amount * (DETAIL_FINE * (L - lowF) + DETAIL_MID * (L - lowM))
        let mx = jmax(o.0, jmax(o.1, o.2))
        let guardK = smoothstep(0.0, 0.08, L) * (1 - smoothstep(0.80, CLIP_LEVEL, mx))
        let ln = clamp01(L + d * guardK)
        var k = ln / jmax(L, 1e-4)
        if mx < CLIP_LEVEL { k = jmin(k, 0.98 / jmax(mx, 1e-4)) }
        o.0 = clamp01(o.0 * k); o.1 = clamp01(o.1 * k); o.2 = clamp01(o.2 * k)
    }

    // ------------------------------------------------------------------ steps

    /// Highlight-recovered colour.
    @inline(__always) static func recover(_ p: UInt32, _ B: Double) -> (Double, Double, Double) {
        let r = r(p), g = g(p), b = b(p)
        let L = lum(r, g, b)
        let m = smoothstep(HL_T0, 1.0, B)
        let lp = clamp01(L - HL_K * m * (B - HL_T0))
        let k = lp / jmax(L, 1e-4)
        return (clamp01(r * k), clamp01(g * k), clamp01(b * k))
    }

    /// Tone curve on luminance, colours scaled to keep their hue; near-white colours roll off per channel.
    @inline(__always) static func lutRgb(_ r: Double, _ g: Double, _ b: Double, _ lut: UnsafeBufferPointer<Double>, _ nl: Double) -> (Double, Double, Double) {
        let L = lum(r, g, b)
        let k = lut[idx(L, nl)] / jmax(L, 1e-4)
        var orr = r * k, og = g * k, ob = b * k
        let t = clamp01((jmax(orr, jmax(og, ob)) - 0.9) / 0.2)
        if t > 0 {
            orr = orr * (1 - t) + lut[idx(r, nl)] * t
            og = og * (1 - t) + lut[idx(g, nl)] * t
            ob = ob * (1 - t) + lut[idx(b, nl)] * t
        }
        return (clamp01(orr), clamp01(og), clamp01(ob))
    }

    @inline(__always) static func idx(_ v: Double, _ nl: Double) -> Int {
        let i = Int((v * nl).rounded(.toNearestOrEven))
        return i < 0 ? 0 : (i > Int(nl) ? Int(nl) : i)
    }
    @inline(__always) static func to8(_ v: Double) -> UInt32 { UInt32((clamp01(v) * 255.0).rounded(.toNearestOrEven)) }
    @inline(__always) static func r(_ p: UInt32) -> Double { Double((p >> 16) & 0xFF) / 255.0 }
    @inline(__always) static func g(_ p: UInt32) -> Double { Double((p >> 8) & 0xFF) / 255.0 }
    @inline(__always) static func b(_ p: UInt32) -> Double { Double(p & 0xFF) / 255.0 }

    /// Kotlin's `coerceIn(0.0, 1.0)`.
    @inline(__always) static func clamp01(_ v: Double) -> Double { v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v) }
    @inline(__always) static func clamp(_ v: Double, _ lo: Double, _ hi: Double) -> Double { v < lo ? lo : (v > hi ? hi : v) }
    /// java.lang.Math.max / min semantics (0.0 beats -0.0), so results match the JVM bit for bit.
    @inline(__always) static func jmax(_ a: Double, _ b: Double) -> Double {
        if a != b { return a > b ? a : b }
        return a.sign == .minus ? b : a
    }
    @inline(__always) static func jmin(_ a: Double, _ b: Double) -> Double {
        if a != b { return a < b ? a : b }
        return a.sign == .minus ? a : b
    }

    @inline(__always) static func smoothstep(_ a: Double, _ b: Double, _ x: Double) -> Double {
        let t = clamp01((x - a) / (b - a))
        return t * t * (3 - 2 * t)
    }

    /// numpy.percentile (linear) on a sorted array.
    static func percentile(_ s: [Double], _ q: Double) -> Double {
        let pos = q / 100.0 * Double(s.count - 1)
        let lo = Int(pos.rounded(.down)); let hi = min(lo + 1, s.count - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - Double(lo))
    }

    // ------------------------------------------------------------------ base layer

    static func baseSmall(_ L: [Double], _ w: Int, _ h: Int) -> ([Double], Int, Int) {
        let s = Double(BASE_LONG) / Double(max(w, h))
        let bw = max(1, Int(Double(w) * s)); let bh = max(1, Int(Double(h) * s))
        let ys = (0...bh).map { Int(Double($0) * Double(h) / Double(bh)) }
        let xs = (0...bw).map { Int(Double($0) * Double(w) / Double(bw)) }
        var out = [Double](repeating: 0, count: bw * bh)
        for by in 0..<bh {
            for bx in 0..<bw {
                var sum = 0.0
                for y in ys[by]..<ys[by + 1] { let row = y * w; for x in xs[bx]..<xs[bx + 1] { sum += L[row + x] } }
                out[by * bw + bx] = sum / Double((ys[by + 1] - ys[by]) * (xs[bx + 1] - xs[bx]))
            }
        }
        return (gaussian(out, bw, bh, BASE_SIGMA_FRAC * Double(max(bw, bh))), bw, bh)
    }

    static func gaussian(_ a: [Double], _ w: Int, _ h: Int, _ sigma: Double) -> [Double] {
        let r = Int((3 * sigma).rounded(.up))
        var k = (0..<(2 * r + 1)).map { i -> Double in let d = Double(i - r) / sigma; return exp(-0.5 * d * d) }
        var ks = 0.0
        for v in k { ks += v }
        for i in 0..<k.count { k[i] /= ks }
        @inline(__always) func refl(_ i: Int, _ n: Int) -> Int { var j = abs(i); if j >= n { j = 2 * (n - 1) - j }; return j }
        var tmp = [Double](repeating: 0, count: w * h)
        var out = [Double](repeating: 0, count: w * h)
        k.withUnsafeBufferPointer { kb in
            a.withUnsafeBufferPointer { ab in
                tmp.withUnsafeMutableBufferPointer { tb in
                    for y in 0..<h {                       // vertical (axis 0) first, as in the reference
                        for x in 0..<w {
                            var s = 0.0
                            for i in 0..<kb.count { s += kb[i] * ab[refl(y + i - r, h) * w + x] }
                            tb[y * w + x] = s
                        }
                    }
                }
            }
            tmp.withUnsafeBufferPointer { tb in
                out.withUnsafeMutableBufferPointer { ob in
                    for y in 0..<h {
                        for x in 0..<w {
                            var s = 0.0
                            for i in 0..<kb.count { s += kb[i] * tb[y * w + refl(x + i - r, w)] }
                            ob[y * w + x] = s
                        }
                    }
                }
            }
        }
        return out
    }

    /// Bilinear, pixel-centre aligned, edges clamped — one output row.
    static func upsampleRow(_ bs: [Double], _ bw: Int, _ bh: Int, _ y: Int, _ w: Int, _ h: Int, _ out: inout [Double]) {
        let fy = clamp((Double(y) + 0.5) * Double(bh) / Double(h) - 0.5, 0.0, Double(bh - 1))
        let y0 = Int(fy.rounded(.down)); let y1 = min(y0 + 1, bh - 1); let wy = fy - Double(y0)
        bs.withUnsafeBufferPointer { b in
            out.withUnsafeMutableBufferPointer { o in
                for x in 0..<w {
                    let fx = clamp((Double(x) + 0.5) * Double(bw) / Double(w) - 0.5, 0.0, Double(bw - 1))
                    let x0 = Int(fx.rounded(.down)); let x1 = min(x0 + 1, bw - 1); let wx = fx - Double(x0)
                    let va = b[y0 * bw + x0], vb = b[y0 * bw + x1], vc = b[y1 * bw + x0], vd = b[y1 * bw + x1]
                    let top = va * (1 - wx) + vb * wx
                    let bottom = vc * (1 - wx) + vd * wx
                    o[x] = top * (1 - wy) + bottom * wy
                }
            }
        }
    }

    // ------------------------------------------------------------------ curve

    static func buildLut(_ op: [Double], _ ep: [Double]) -> [Double] {
        var xs: [Double] = [0.0]; var ys: [Double] = [0.0]
        for i in 0..<op.count {
            if op[i] > xs[xs.count - 1] + 1e-3 {
                xs.append(op[i]); ys.append(jmin(1.0, jmax(ep[i], ys[ys.count - 1] + 1e-4)))
            }
        }
        if xs[xs.count - 1] < 1 - 1e-3 {
            let prevX = xs[xs.count - 1]
            let last = ys[ys.count - 1]
            xs.append(1.0); ys.append(jmin(1.0, jmax(last + (1 - prevX), last)))
        }
        for i in 1..<ys.count { ys[i] = jmax(jmin(ys[i], 1.0), ys[i - 1]) }
        return (0..<LUT_N).map { clamp01(pchip(xs, ys, Double($0) / (Double(LUT_N) - 1.0))) }
    }

    /// Monotone cubic (Fritsch–Carlson), identical to the reference.
    static func pchip(_ xs: [Double], _ ys: [Double], _ xq: Double) -> Double {
        let n = xs.count
        let h = (0..<(n - 1)).map { xs[$0 + 1] - xs[$0] }
        let d = (0..<(n - 1)).map { (ys[$0 + 1] - ys[$0]) / h[$0] }
        var m = [Double](repeating: 0, count: n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        if n > 2 {
            for i in 1..<(n - 1) {
                if d[i - 1] * d[i] <= 0 { m[i] = 0.0 } else {
                    let w1 = 2 * h[i] + h[i - 1]; let w2 = h[i] + 2 * h[i - 1]
                    m[i] = (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
                }
            }
        }
        var i = 0
        while i < n - 2 && xq >= xs[i + 1] { i += 1 }
        let t = (xq - xs[i]) / h[i]
        let t2 = t * t; let t3 = t2 * t
        let a = (2 * t3 - 3 * t2 + 1) * ys[i]
        let b = (t3 - 2 * t2 + t) * h[i] * m[i]
        let c = (-2 * t3 + 3 * t2) * ys[i + 1]
        let e = (t3 - t2) * h[i] * m[i + 1]
        return ((a + b) + c) + e
    }
}

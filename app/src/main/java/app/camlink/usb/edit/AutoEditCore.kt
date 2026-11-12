package app.camlink.usb.edit

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * "Auto edit" in the user's Lightroom style. Pure Kotlin (no Android types) so it can be unit-tested on a PC
 * against the Python reference (D:/camlink-autoedit/work/autoedit_ref.py), which this mirrors step for step.
 *
 *  1. Highlights pulled down locally (blurred-luminance mask, detail kept)        — step 3
 *  2. Whites / blacks: darkest 0.1% → black, brightest 0.1% → white               — steps 4, 7
 *  3. Learned style curve on that stretched histogram (Auto, shadows, contrast)   — steps 2, 5, 6
 *  4. Highlight guard: if the curve would blow out detail the original still had, its top is
 *     eased down until (almost) nothing new clips.
 *  5. Local contrast boost (fine + medium detail) so the result keeps the user's punch; it never
 *     pushes a colour channel to white.
 *  6. Night rule: a frame that is almost all black with only small lights is kept as shot
 *     (darks a touch darker, lights untouched, no detail boost) instead of being brightened.
 *
 * Pixels are packed ARGB ints (as in android.graphics.Bitmap).
 */
object AutoEditCore {
    private val Q = doubleArrayOf(0.1, 1.0, 5.0, 15.0, 30.0, 50.0, 70.0, 85.0, 95.0, 99.0, 99.9)
    private const val MIN_SPAN = 0.35
    private const val HL_K = 0.3
    private const val HL_T0 = 0.45
    /** Local contrast (Texture / Clarity-like), matched to the user's edits. */
    private const val DETAIL_FINE = 0.15
    private const val DETAIL_MID = 0.10
    private const val DETAIL_FINE_FRAC = 0.0033
    private const val DETAIL_MID_FRAC = 0.022
    /** Night rule: nearly all near-black with only small lights → keep it dark (user's intent for night shots). */
    private const val NIGHT_DARK_LO = 0.85
    private const val NIGHT_DARK_HI = 0.93
    private const val NIGHT_P95_LO = 0.20
    private const val NIGHT_P95_HI = 0.35
    private const val NIGHT_SHADOW = 0.9
    /** Highlight rule: at most 0.05% of pixels may newly blow out to white. */
    private const val CLIP_BUDGET = 0.0005
    private const val CLIP_LEVEL = 0.985
    private const val BASE_LONG = 160
    private const val BASE_SIGMA_FRAC = 0.03
    const val LUT_N = 1024

    /** Everything decided from a small copy of the photo; then applied to the full-size pixels. */
    class Plan(
        val base: DoubleArray, val bw: Int, val bh: Int, val lut: DoubleArray,
        /** Low-pass luminance of the edited small copy (sw x sh), for the local contrast boost. */
        val lowF: DoubleArray, val lowM: DoubleArray, val sw: Int, val sh: Int,
        /** 0 = normal photo, 1 = night scene. */
        val night: Double,
    )

    private fun lum(r: Double, g: Double, b: Double) = 0.2126 * r + 0.7152 * g + 0.0722 * b

    // ------------------------------------------------------------------ plan

    fun plan(small: IntArray, w: Int, h: Int, weights: Array<DoubleArray> = AutoEditWeights.W): Plan {
        val n = w * h
        val L = DoubleArray(n)
        for (i in 0 until n) { val p = small[i]; L[i] = lum(r(p), g(p), b(p)) }
        val (bs, bw, bh) = baseSmall(L, w, h)
        // Highlight recovery on the small copy, then statistics of the result.
        val Lp = DoubleArray(n)
        val pre = DoubleArray(n * 3)
        val wasClipped = BooleanArray(n)
        val rowB = DoubleArray(w)
        for (y in 0 until h) {
            upsampleRow(bs, bw, bh, y, w, h, rowB)
            for (x in 0 until w) {
                val i = y * w + x
                val c = recover(small[i], rowB[x])
                pre[3 * i] = c[0]; pre[3 * i + 1] = c[1]; pre[3 * i + 2] = c[2]
                Lp[i] = lum3(c)
                val p = small[i]
                wasClipped[i] = max(r(p), max(g(p), b(p))) >= CLIP_LEVEL
            }
        }
        val sorted = Lp.copyOf().also { it.sort() }
        val op = DoubleArray(Q.size) { percentile(sorted, Q[it]) }
        val lo = op[0]; val hi = max(op[Q.size - 1], op[0] + MIN_SPAN)
        val npct = DoubleArray(Q.size) { ((op[it] - lo) / (hi - lo)).coerceIn(0.0, 1.0) }
        var f1 = 0; var f2 = 0; var f3 = 0; var f4 = 0; var f5 = 0
        for (v in Lp) { if (v > 0.6) f1++; if (v > 0.8) f2++; if (v > 0.95) f3++; if (v < 0.05) f4++; if (v < 0.15) f5++ }
        val x = DoubleArray(1 + Q.size + 5 + 2)
        x[0] = 1.0
        for (i in Q.indices) x[1 + i] = npct[i]
        val nn = n.toDouble()
        x[12] = f1 / nn; x[13] = f2 / nn; x[14] = f3 / nn; x[15] = f4 / nn; x[16] = f5 / nn
        x[17] = hi - lo; x[18] = npct[5] * npct[5]
        val ep = DoubleArray(Q.size)
        for (j in Q.indices) {
            var s = 0.0
            for (k in x.indices) s += x[k] * weights[k][j]
            ep[j] = (npct[j] + s).coerceIn(0.0, 1.0)
            if (j > 0) ep[j] = max(ep[j], ep[j - 1])
        }
        // Night rule.
        var dark = 0
        for (v in Lp) if (v < 0.1) dark++
        val p95 = percentile(sorted, 95.0)
        val night = smoothstep(NIGHT_DARK_LO, NIGHT_DARK_HI, dark.toDouble() / n) *
            (1 - smoothstep(NIGHT_P95_LO, NIGHT_P95_HI, p95))
        if (night > 0) {
            for (j in Q.indices) {
                val target = if (Q[j] <= 85.0) op[j] * NIGHT_SHADOW else op[j]
                ep[j] = (1 - night) * ep[j] + night * target
                if (j > 0) ep[j] = max(ep[j], ep[j - 1])
            }
        }
        // Highlight guard: ease the top of the curve down until no detail newly blows out.
        var lut = buildLut(op, ep)
        val c = DoubleArray(3)
        for (iter in 0 until 40) {
            var clipped = 0
            for (i in 0 until n) {
                if (wasClipped[i]) continue
                c[0] = pre[3 * i]; c[1] = pre[3 * i + 1]; c[2] = pre[3 * i + 2]
                if (lutMaxChannel(c, lut) >= CLIP_LEVEL) clipped++
            }
            if (clipped.toDouble() / n <= CLIP_BUDGET) break
            for (j in 7 until Q.size) ep[j] = ep[6] + (ep[j] - ep[6]) * 0.97
            lut = buildLut(op, ep)
        }
        // Low-pass copies of the edited small image for the local contrast boost.
        val Lo = DoubleArray(n)
        for (i in 0 until n) {
            c[0] = pre[3 * i]; c[1] = pre[3 * i + 1]; c[2] = pre[3 * i + 2]
            val o = outTmp.get(); lutRgb(c, lut, (lut.size - 1).toDouble(), o)
            Lo[i] = lum(o[0], o[1], o[2])
        }
        val lowF = gaussian(Lo, w, h, DETAIL_FINE_FRAC * max(w, h))
        val lowM = gaussian(Lo, w, h, DETAIL_MID_FRAC * max(w, h))
        return Plan(bs, bw, bh, lut, lowF, lowM, w, h, night)
    }

    // ------------------------------------------------------------------ render

    /** Edits [rows] full-width rows starting at global row [y0], in place. */
    fun render(pixels: IntArray, offset: Int, width: Int, rows: Int, y0: Int, fullH: Int, plan: Plan) {
        val rowB = DoubleArray(width)
        val rowF = DoubleArray(width)
        val rowM = DoubleArray(width)
        val lut = plan.lut
        val nl = (lut.size - 1).toDouble()
        val o = DoubleArray(3)
        for (ry in 0 until rows) {
            val y = y0 + ry
            upsampleRow(plan.base, plan.bw, plan.bh, y, width, fullH, rowB)
            upsampleRow(plan.lowF, plan.sw, plan.sh, y, width, fullH, rowF)
            upsampleRow(plan.lowM, plan.sw, plan.sh, y, width, fullH, rowM)
            val base = offset + ry * width
            for (x in 0 until width) {
                val p = pixels[base + x]
                lutRgb(recover(p, rowB[x]), lut, nl, o)
                boost(o, rowF[x], rowM[x], 1 - plan.night)
                pixels[base + x] = (p and 0xFF000000.toInt()) or (to8(o[0]) shl 16) or (to8(o[1]) shl 8) or to8(o[2])
            }
        }
    }

    /** Local contrast: add back fine + medium detail, never touching deep blacks or pushing a channel to white. */
    private fun boost(o: DoubleArray, lowF: Double, lowM: Double, amount: Double) {
        if (amount <= 0) return
        val L = lum(o[0], o[1], o[2])
        val d = amount * (DETAIL_FINE * (L - lowF) + DETAIL_MID * (L - lowM))
        val mx = max(o[0], max(o[1], o[2]))
        val guard = smoothstep(0.0, 0.08, L) * (1 - smoothstep(0.80, CLIP_LEVEL, mx))
        val ln = (L + d * guard).coerceIn(0.0, 1.0)
        var k = ln / max(L, 1e-4)
        if (mx < CLIP_LEVEL) k = min(k, 0.98 / max(mx, 1e-4))
        o[0] = (o[0] * k).coerceIn(0.0, 1.0); o[1] = (o[1] * k).coerceIn(0.0, 1.0); o[2] = (o[2] * k).coerceIn(0.0, 1.0)
    }

    // ------------------------------------------------------------------ steps

    /** Returns the highlight-recovered colour as three doubles packed in a reusable holder. */
    private val tmp = ThreadLocal.withInitial { DoubleArray(3) }

    private fun recover(p: Int, B: Double): DoubleArray {
        val o = tmp.get()
        val r = r(p); val g = g(p); val b = b(p)
        val L = lum(r, g, b)
        val m = smoothstep(HL_T0, 1.0, B)
        val lp = (L - HL_K * m * (B - HL_T0)).coerceIn(0.0, 1.0)
        val k = lp / max(L, 1e-4)
        o[0] = (r * k).coerceIn(0.0, 1.0); o[1] = (g * k).coerceIn(0.0, 1.0); o[2] = (b * k).coerceIn(0.0, 1.0)
        return o
    }

    private fun lum3(c: DoubleArray) = lum(c[0], c[1], c[2])

    private val outTmp = ThreadLocal.withInitial { DoubleArray(3) }

    /** Tone curve on luminance, colours scaled to keep their hue; near-white colours roll off per channel. */
    private fun lutRgb(c: DoubleArray, lut: DoubleArray, nl: Double, o: DoubleArray) {
        val r = c[0]; val g = c[1]; val b = c[2]
        val L = lum(r, g, b)
        val k = lut[idx(L, nl)] / max(L, 1e-4)
        var orr = r * k; var og = g * k; var ob = b * k
        val t = ((max(orr, max(og, ob)) - 0.9) / 0.2).coerceIn(0.0, 1.0)
        if (t > 0) {
            orr = orr * (1 - t) + lut[idx(r, nl)] * t
            og = og * (1 - t) + lut[idx(g, nl)] * t
            ob = ob * (1 - t) + lut[idx(b, nl)] * t
        }
        o[0] = orr.coerceIn(0.0, 1.0); o[1] = og.coerceIn(0.0, 1.0); o[2] = ob.coerceIn(0.0, 1.0)
    }

    private fun applyLut(c: DoubleArray, lut: DoubleArray, nl: Double): Int {
        val o = outTmp.get(); lutRgb(c, lut, nl, o)
        return (to8(o[0]) shl 16) or (to8(o[1]) shl 8) or to8(o[2])
    }

    private fun lutMaxChannel(c: DoubleArray, lut: DoubleArray): Double {
        val o = outTmp.get(); lutRgb(c, lut, (lut.size - 1).toDouble(), o)
        return max(o[0], max(o[1], o[2]))
    }

    private fun idx(v: Double, nl: Double) = Math.rint(v * nl).toInt().coerceIn(0, nl.toInt())
    private fun to8(v: Double) = Math.rint(v.coerceIn(0.0, 1.0) * 255.0).toInt()
    private fun r(p: Int) = ((p shr 16) and 0xFF) / 255.0
    private fun g(p: Int) = ((p shr 8) and 0xFF) / 255.0
    private fun b(p: Int) = (p and 0xFF) / 255.0

    private fun smoothstep(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t)
    }

    /** numpy.percentile (linear) on a sorted array. */
    private fun percentile(s: DoubleArray, q: Double): Double {
        val pos = q / 100.0 * (s.size - 1)
        val lo = floor(pos).toInt(); val hi = min(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    // ------------------------------------------------------------------ base layer

    private fun baseSmall(L: DoubleArray, w: Int, h: Int): Triple<DoubleArray, Int, Int> {
        val s = BASE_LONG.toDouble() / max(w, h)
        val bw = max(1, (w * s).toInt()); val bh = max(1, (h * s).toInt())
        val ys = IntArray(bh + 1) { (it * h.toDouble() / bh).toInt() }
        val xs = IntArray(bw + 1) { (it * w.toDouble() / bw).toInt() }
        val out = DoubleArray(bw * bh)
        for (by in 0 until bh) for (bx in 0 until bw) {
            var sum = 0.0
            for (y in ys[by] until ys[by + 1]) { val row = y * w; for (x in xs[bx] until xs[bx + 1]) sum += L[row + x] }
            out[by * bw + bx] = sum / ((ys[by + 1] - ys[by]) * (xs[bx + 1] - xs[bx]))
        }
        return Triple(gaussian(out, bw, bh, BASE_SIGMA_FRAC * max(bw, bh)), bw, bh)
    }

    private fun gaussian(a: DoubleArray, w: Int, h: Int, sigma: Double): DoubleArray {
        val r = ceil(3 * sigma).toInt()
        val k = DoubleArray(2 * r + 1) { val d = (it - r) / sigma; exp(-0.5 * d * d) }
        val ks = k.sum(); for (i in k.indices) k[i] /= ks
        fun refl(i: Int, n: Int): Int { var j = abs(i); if (j >= n) j = 2 * (n - 1) - j; return j }
        val tmp = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {          // vertical (axis 0) first, as in the reference
            var s = 0.0
            for (i in k.indices) s += k[i] * a[refl(y + i - r, h) * w + x]
            tmp[y * w + x] = s
        }
        val out = DoubleArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0
            for (i in k.indices) s += k[i] * tmp[y * w + refl(x + i - r, w)]
            out[y * w + x] = s
        }
        return out
    }

    /** Bilinear, pixel-centre aligned, edges clamped — one output row. */
    private fun upsampleRow(bs: DoubleArray, bw: Int, bh: Int, y: Int, w: Int, h: Int, out: DoubleArray) {
        val fy = ((y + 0.5) * bh / h - 0.5).coerceIn(0.0, (bh - 1).toDouble())
        val y0 = floor(fy).toInt(); val y1 = min(y0 + 1, bh - 1); val wy = fy - y0
        for (x in 0 until w) {
            val fx = ((x + 0.5) * bw / w - 0.5).coerceIn(0.0, (bw - 1).toDouble())
            val x0 = floor(fx).toInt(); val x1 = min(x0 + 1, bw - 1); val wx = fx - x0
            val a = bs[y0 * bw + x0]; val b = bs[y0 * bw + x1]; val c = bs[y1 * bw + x0]; val d = bs[y1 * bw + x1]
            out[x] = (a * (1 - wx) + b * wx) * (1 - wy) + (c * (1 - wx) + d * wx) * wy
        }
    }

    // ------------------------------------------------------------------ curve

    private fun buildLut(op: DoubleArray, ep: DoubleArray): DoubleArray {
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        xs.add(0.0); ys.add(0.0)
        for (i in op.indices) {
            if (op[i] > xs.last() + 1e-3) { xs.add(op[i]); ys.add(min(1.0, max(ep[i], ys.last() + 1e-4))) }
        }
        if (xs.last() < 1 - 1e-3) {
            val prevX = xs.last()
            xs.add(1.0); ys.add(min(1.0, max(ys.last() + (1 - prevX), ys.last())))
        }
        for (i in 1 until ys.size) ys[i] = max(min(ys[i], 1.0), ys[i - 1])
        val X = xs.toDoubleArray(); val Y = ys.toDoubleArray()
        return DoubleArray(LUT_N) { pchip(X, Y, it / (LUT_N - 1.0)).coerceIn(0.0, 1.0) }
    }

    /** Monotone cubic (Fritsch–Carlson), identical to the reference. */
    private fun pchip(xs: DoubleArray, ys: DoubleArray, xq: Double): Double {
        val n = xs.size
        val h = DoubleArray(n - 1) { xs[it + 1] - xs[it] }
        val d = DoubleArray(n - 1) { (ys[it + 1] - ys[it]) / h[it] }
        val m = DoubleArray(n)
        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0) 0.0 else {
                val w1 = 2 * h[i] + h[i - 1]; val w2 = h[i] + 2 * h[i - 1]
                (w1 + w2) / (w1 / d[i - 1] + w2 / d[i])
            }
        }
        var i = 0
        while (i < n - 2 && xq >= xs[i + 1]) i++
        val t = (xq - xs[i]) / h[i]
        val t2 = t * t; val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * ys[i] + (t3 - 2 * t2 + t) * h[i] * m[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] + (t3 - t2) * h[i] * m[i + 1]
    }
}

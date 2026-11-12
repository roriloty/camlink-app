package app.camlink.usb.ptp

import app.camlink.usb.DebugLog
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sony Alpha "PC Remote" protocol over PTP (the same vendor extension libgphoto2 uses).
 * All methods block and must be called from a single background thread at a time.
 */
class SonyCamera(private val ptp: PtpUsb) : Closeable {

    companion object {
        const val SONY_VENDOR_ID = 0x054C

        private const val OC_SDIO_CONNECT = 0x9201
        private const val OC_SDIO_GET_EXT_DEVICE_INFO = 0x9202
        private const val OC_SDIO_SET_EXT_PROP = 0x9205
        private const val OC_SDIO_CONTROL_DEVICE = 0x9207
        private const val OC_SDIO_GET_ALL_EXT_PROP_INFO = 0x9209

        const val HANDLE_CAPTURED = 0xFFFFC001.toInt()
        const val HANDLE_LIVEVIEW = 0xFFFFC002.toInt()

        // Property / control codes
        const val DPC_FOCUS_MODE = 0x500A
        const val DPC_FOCUS_FOUND = 0xD213
        const val DPC_OBJECT_IN_MEMORY = 0xD215
        const val DPC_BATTERY = 0xD218
        const val DPC_STORE_DESTINATION = 0xD222
        const val DPC_FOCUS_AREA = 0xD22C
        const val DPC_PRIORITY_MODE = 0xD25A
        const val DPC_SHUTTER_HALF = 0xD2C1
        const val DPC_SHUTTER_FULL = 0xD2C2
        const val DPC_MANUAL_FOCUS_ADJUST = 0xD2D1
        const val DPC_AF_AREA_POSITION = 0xD2DC
        /** A7 III-era bodies: current Flexible Spot position (read) and its setter. Value = x<<16 | y. */
        const val DPC_FOCUS_POS_CURRENT = 0xD230
        const val DPC_FOCUS_POS_SET = 0xD232

        // Exposure settings
        const val DPC_WHITE_BALANCE = 0x5005
        const val DPC_F_NUMBER = 0x5007
        const val DPC_EXPOSURE_PROGRAM = 0x500E
        const val DPC_EXPOSURE_COMP = 0x5010
        const val DPC_SHUTTER_SPEED = 0xD20D
        const val DPC_ISO = 0xD21E

        const val PRESS = 2
        const val RELEASE = 1

        /** Flexible Spot coordinate space on the A7 III (centre reads 320,214 → 640 x 428, 3:2). */
        const val FOCUS_W = 640
        const val FOCUS_H = 428

        private const val PROTOCOL_300 = 300
    }

    /** One device property as reported by the camera. */
    class PropInfo(
        val code: Int, val type: Int, val getSet: Int, val enabled: Int,
        val current: Long, val enumValues: List<Long>?,
    ) {
        /** Greyed out on the camera (e.g. aperture with a manual lens, shutter in A mode). */
        val isDisabled get() = enabled == 0
    }

    /** Last read current value of every Sony property. */
    @Volatile var props: Map<Int, Long> = emptyMap(); private set
    /** Full descriptors from the last read (current value, allowed values, enabled flag). */
    @Volatile var propInfo: Map<Int, PropInfo> = emptyMap(); private set
    var protocolVersion = 200; private set

    fun connect() {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                handshake()
                return
            } catch (e: Exception) {
                lastError = e
                DebugLog.d("Attempt $attempt failed: ${e.message}")
                if (attempt < 3) {
                    ptp.reset()
                    Thread.sleep(500L * attempt)
                }
            }
        }
        throw lastError!!
    }

    private fun handshake() {
        val di = ptp.transact(Ptp.OC_GET_DEVICE_INFO, timeoutMs = 3000)
        DebugLog.d("GetDeviceInfo -> 0x%04X (%d bytes)".format(di.code, di.data?.size ?: 0))

        val r = ptp.transact(Ptp.OC_OPEN_SESSION, 1)
        DebugLog.d("OpenSession -> 0x%04X".format(r.code))
        if (r.code == Ptp.RC_SESSION_ALREADY_OPEN) {
            // A session left over from another app: close it and start a clean one.
            ptp.transact(Ptp.OC_CLOSE_SESSION)
            val r2 = ptp.transact(Ptp.OC_OPEN_SESSION, 1)
            DebugLog.d("Re-OpenSession -> 0x%04X".format(r2.code))
            if (!r2.ok && r2.code != Ptp.RC_SESSION_ALREADY_OPEN)
                throw PtpException(r2.code, "OpenSession failed: 0x%04X".format(r2.code))
        } else if (!r.ok) {
            throw PtpException(r.code, "OpenSession failed: 0x%04X".format(r.code))
        }

        DebugLog.d("SDIO connect 1 -> 0x%04X".format(ptp.call(OC_SDIO_CONNECT, 1, 0, 0).code))
        DebugLog.d("SDIO connect 2 -> 0x%04X".format(ptp.call(OC_SDIO_CONNECT, 2, 0, 0).code))
        var info: ByteArray? = null
        for (i in 0 until 20) {
            info = ptp.call(OC_SDIO_GET_EXT_DEVICE_INFO, PROTOCOL_300, 1).data
            if (info != null && info.size >= 2) break
            Thread.sleep(50)
        }
        if (info != null && info.size >= 2) {
            protocolVersion = (info[0].toInt() and 0xFF) or ((info[1].toInt() and 0xFF) shl 8)
        }
        DebugLog.d("Ext device info: protocol $protocolVersion (${info?.size ?: 0} bytes)")
        DebugLog.d("SDIO connect 3 -> 0x%04X".format(ptp.call(OC_SDIO_CONNECT, 3, 0, 0).code))

        // Tell the camera the app (not the body dials) has priority, like Sony's own software does.
        runCatching { setProp(DPC_PRIORITY_MODE, Ptp.DT_INT8, 1) }
        // Right after the handshake the body reports placeholder (zero) values; wait until they are real.
        val until = System.currentTimeMillis() + 2000
        while ((refreshProps()[DPC_FOCUS_MODE] ?: 0L) == 0L && System.currentTimeMillis() < until) Thread.sleep(100)

        // Images are only transferred when the save destination includes the PC (1 = PC, 0x10 = camera, 0x11 = both).
        if (props[DPC_STORE_DESTINATION] == 0x10L) runCatching { setProp(DPC_STORE_DESTINATION, Ptp.DT_UINT16, 0x11) }
        DebugLog.d("Connected: ${props.size} properties, focus mode=${props[DPC_FOCUS_MODE]}, " +
            "save dest=${props[DPC_STORE_DESTINATION]}")
    }

    // ------------------------------------------------------------------ properties

    fun refreshProps(): Map<Int, Long> {
        val d = ptp.call(OC_SDIO_GET_ALL_EXT_PROP_INFO).data ?: return props
        val info = parseAllProps(d)
        propInfo = info
        props = info.mapValues { it.value.current }
        return props
    }

    fun setProp(code: Int, type: Int, value: Long) =
        ptp.call(OC_SDIO_SET_EXT_PROP, code, sendData = pack(type, value))

    fun control(code: Int, type: Int, value: Long) =
        ptp.call(OC_SDIO_CONTROL_DEVICE, code, sendData = pack(type, value))

    private fun pack(type: Int, value: Long): ByteArray {
        val size = when (type) {
            Ptp.DT_INT8, Ptp.DT_UINT8 -> 1
            Ptp.DT_INT16, Ptp.DT_UINT16 -> 2
            else -> 4
        }
        return ByteArray(size) { ((value shr (8 * it)) and 0xFF).toByte() }
    }

    // ------------------------------------------------------------------ live view

    /** Returns (bytes, offset, length) of the current live view JPEG, or null if not ready yet. */
    fun liveView(): Triple<ByteArray, Int, Int>? {
        val r = ptp.transact(Ptp.OC_GET_OBJECT, HANDLE_LIVEVIEW, timeoutMs = 3000)
        if (!r.ok) {
            if (r.code == Ptp.RC_ACCESS_DENIED || r.code == Ptp.RC_INVALID_OBJECT_HANDLE ||
                r.code == Ptp.RC_DEVICE_BUSY) return null
            throw PtpException(r.code, "Live view failed: 0x%04X".format(r.code))
        }
        val d = r.data ?: return null
        if (d.size < 8) return null
        // Sony prepends a header: u32 offset to JPEG, u32 JPEG size.
        val off = u32(d, 0).toInt()
        val len = u32(d, 4).toInt()
        if (off in 8 until d.size - 1 && d[off] == 0xFF.toByte() && d[off + 1] == 0xD8.toByte()) {
            return Triple(d, off, if (len > 0 && off + len <= d.size) len else d.size - off)
        }
        for (i in 0 until d.size - 1) {
            if (d[i] == 0xFF.toByte() && d[i + 1] == 0xD8.toByte()) return Triple(d, i, d.size - i)
        }
        return null
    }

    // ------------------------------------------------------------------ focus

    // Short, non-blocking primitives. The view model sequences them and polls focus between calls,
    // so live view keeps streaming while the camera focuses or exposes.

    /**
     * Moves the Flexible Spot AF point. [fx], [fy] are fractions (0..1) of the live view frame.
     * The A7 III has no 0xD2DC (AF area position) control; it exposes the spot position as 0xD232
     * (settable) mirrored by 0xD230 (current). Newer bodies use 0xD2DC, so that is tried when present.
     */
    fun setAfPoint(fx: Float, fy: Float) {
        // The position only applies to the "Flexible Spot" family of focus areas (codes >= 0x101).
        val area = props[DPC_FOCUS_AREA]
        if (area != null && area < 0x101) {
            runCatching { setProp(DPC_FOCUS_AREA, Ptp.DT_UINT16, 0x0102) } // Flexible Spot: M
            refreshProps()
        }
        val before = props[DPC_FOCUS_POS_CURRENT]
        if (propInfo.containsKey(DPC_AF_AREA_POSITION)) {
            val x = (fx * 640).toInt().coerceIn(0, 639); val y = (fy * 480).toInt().coerceIn(0, 479)
            control(DPC_AF_AREA_POSITION, Ptp.DT_UINT32, (x.toLong() shl 16) or y.toLong())
            DebugLog.d("AF point via D2DC -> $x,$y")
            return
        }
        val x = (fx * FOCUS_W).toInt().coerceIn(0, FOCUS_W - 1)
        val y = (fy * FOCUS_H).toInt().coerceIn(0, FOCUS_H - 1)
        val v = (x.toLong() shl 16) or y.toLong()
        val r1 = ptp.transact(OC_SDIO_SET_EXT_PROP, DPC_FOCUS_POS_SET, sendData = pack(Ptp.DT_UINT32, v)).code
        refreshProps()
        var after = props[DPC_FOCUS_POS_CURRENT]
        DebugLog.d("AF point $x,$y: set D232 -> 0x%04X, D230 %s -> %s".format(r1, fmtPos(before), fmtPos(after)))
        if (after != v) {
            // Some firmware only accepts it as a control (button-style) value.
            val r2 = ptp.transact(OC_SDIO_CONTROL_DEVICE, DPC_FOCUS_POS_SET, sendData = pack(Ptp.DT_UINT32, v)).code
            refreshProps()
            after = props[DPC_FOCUS_POS_CURRENT]
            DebugLog.d("AF point retry as control -> 0x%04X, D230 now %s".format(r2, fmtPos(after)))
        }
    }

    private fun fmtPos(v: Long?) = if (v == null) "?" else "${v shr 16},${v and 0xFFFF}"

    /**
     * Turns a camera "dial" by [notches] (aperture, shutter, ISO, exposure comp).
     * Sony protocol 2 bodies change these by stepping: 0x01 = one notch up, 0xFF = one notch down.
     */
    fun step(code: Int, notches: Int) {
        val v = if (notches >= 0) notches.coerceAtMost(0x7F) else (0x100 + notches.coerceAtLeast(-0x7F))
        control(code, Ptp.DT_UINT8, v.toLong())
    }

    /** Sets a property to an exact value (used for white balance, which is a list rather than a dial). */
    fun setValue(code: Int, value: Long) {
        val type = propInfo[code]?.type ?: Ptp.DT_UINT16
        setProp(code, type, value)
    }

    fun halfPress(down: Boolean) = control(DPC_SHUTTER_HALF, Ptp.DT_UINT16, (if (down) PRESS else RELEASE).toLong())
    fun fullPress(down: Boolean) = control(DPC_SHUTTER_FULL, Ptp.DT_UINT16, (if (down) PRESS else RELEASE).toLong())

    /** Kind of AF area, from [DPC_FOCUS_AREA]. */
    enum class AreaKind { WIDE, ZONE, CENTER, SPOT }

    /** Camera's AF area and frame, as far as the body reports it over USB. */
    data class FocusInfo(
        val kind: AreaKind,
        /** Spot centre as a fraction of the frame (SPOT only). */
        val spot: Pair<Float, Float>?,
        /** Spot size as a fraction of the frame width. */
        val size: Float,
        /** 1 idle, 5 focusing, 2/3/6 in focus, 7 failed (A7 III values of 0xD213). */
        val state: Int,
    ) {
        /** 2 = AF-S locked, 3 / 6 = in focus (6 is AF-C's tracking "green dot" state on an A7 III). */
        val locked get() = state == 2 || state == 3 || state == 6
        val focusing get() = state == 5
        /** 7 = could not focus (listed by the body; the only remaining state, not yet observed). */
        val failed get() = state == 7
    }

    fun focusInfo(): FocusInfo? {
        val area = props[DPC_FOCUS_AREA] ?: return null
        val state = (props[DPC_FOCUS_FOUND] ?: 1L).toInt()
        val low = (area and 0xFF).toInt()
        val isSpot = area >= 0x100 && !(area >= 0x200 && low <= 3)
        val kind = when {
            isSpot -> AreaKind.SPOT
            low == 1 -> AreaKind.WIDE
            low == 2 -> AreaKind.ZONE
            else -> AreaKind.CENTER
        }
        val spot = if (kind == AreaKind.SPOT) props[DPC_FOCUS_POS_SET]?.let {
            ((it shr 16).toFloat() / FOCUS_W).coerceIn(0f, 1f) to ((it and 0xFFFF).toFloat() / FOCUS_H).coerceIn(0f, 1f)
        } else null
        // Spot S / M / L / expanded: approximate on-screen sizes of the A7 III frames.
        val size = when (if (area >= 0x200) low - 3 else low) { 1 -> 0.055f; 2 -> 0.085f; 3 -> 0.12f; else -> 0.085f }
        return FocusInfo(kind, spot, size, state)
    }

    /** Focus state from the last property read: 2/3/6 = in focus (1 idle, 5 focusing on an A7 III). */
    fun isFocusLocked(): Boolean = props[DPC_FOCUS_FOUND].let { it == 2L || it == 3L || it == 6L }

    fun isManualFocus() = props[DPC_FOCUS_MODE] == 1L

    /** Clears a stalled pipe (the body stalls requests while it is busy writing a picture). */
    fun recover() = ptp.recover()

    /** Receives camera events, e.g. 0xC201 = new image ready (also when shot with the camera's own button). */
    fun setEventListener(l: (Int, IntArray) -> Unit) { ptp.onEvent = l }

    /** Number of finished images waiting in the camera's transfer buffer (0 if none). */
    fun pendingImages(): Int {
        val v = props[DPC_OBJECT_IN_MEMORY] ?: return 0
        return if (v >= 0x8000) (v - 0x8000).toInt().coerceAtLeast(1) else 0
    }

    class Image(val fileName: String, val bytes: ByteArray, val thumbnail: ByteArray?)

    /** Downloads the next captured image byte-for-byte (original JPEG/ARW exactly as the camera wrote it). */
    fun downloadImage(): Image {
        val info = ptp.call(Ptp.OC_GET_OBJECT_INFO, HANDLE_CAPTURED).data
            ?: throw PtpException(Ptp.RC_GENERAL_ERROR, "Camera sent no object info")
        val name = parseFileName(info) ?: "DSC_${System.currentTimeMillis()}.JPG"
        // No GetThumb here: the A7 III stalls the pipe on it, which then broke the image download.
        val bytes = ptp.call(Ptp.OC_GET_OBJECT, HANDLE_CAPTURED, timeoutMs = 15000).data
            ?: throw PtpException(Ptp.RC_GENERAL_ERROR, "Camera sent no image data")
        return Image(name, bytes, null)
    }

    override fun close() {
        runCatching { ptp.transact(Ptp.OC_CLOSE_SESSION, timeoutMs = 1000) }
        ptp.close()
    }

    // ------------------------------------------------------------------ parsing

    private fun parseFileName(oi: ByteArray): String? {
        // ObjectInfo dataset: the Filename string starts at byte 52.
        if (oi.size < 53) return null
        val n = oi[52].toInt() and 0xFF
        if (n == 0 || 53 + 2 * n > oi.size) return null
        val sb = StringBuilder()
        for (i in 0 until n) {
            val c = ((oi[53 + 2 * i].toInt() and 0xFF) or ((oi[54 + 2 * i].toInt() and 0xFF) shl 8)).toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString().takeIf { it.isNotBlank() }
    }

    /** Parses the SDIO GetAllExtDevicePropInfo blob into code -> descriptor. */
    private fun parseAllProps(d: ByteArray): Map<Int, PropInfo> {
        val out = HashMap<Int, PropInfo>()
        val b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        if (d.size <= 8) return out
        b.position(8) // u32 count + u32 reserved
        try {
            while (b.remaining() >= 6) {
                val code = b.short.toInt() and 0xFFFF
                val type = b.short.toInt() and 0xFFFF
                val getSet = b.get().toInt() and 0xFF
                val enabled = b.get().toInt() and 0xFF
                readValue(b, type) // factory default
                if (type == Ptp.DT_STR && !b.hasRemaining()) break
                val cur = readValue(b, type)
                var enums: List<Long>? = null
                if (!b.hasRemaining()) { out[code] = PropInfo(code, type, getSet, enabled, cur, null); break }
                val form = b.get().toInt() and 0xFF
                when (form) {
                    1 -> repeat(3) { readValue(b, type) }
                    2 -> enums = List(b.short.toInt() and 0xFFFF) { readValue(b, type) }
                }
                // Newer bodies append a second ("settable values") enum list; a property code would be >= 0x200.
                if (b.remaining() >= 2) {
                    val peek = b.getShort(b.position()).toInt() and 0xFFFF
                    if (peek < 0x200 && form == 2) {
                        b.short
                        enums = List(peek) { readValue(b, type) }
                    }
                }
                out[code] = PropInfo(code, type, getSet, enabled, cur, enums)
            }
        } catch (_: Exception) {
            // Truncated / unknown layout: keep what was parsed so far.
        }
        return out
    }

    private fun readValue(b: ByteBuffer, type: Int): Long = when (type) {
        0x0001 -> b.get().toLong()
        0x0002 -> b.get().toLong() and 0xFF
        0x0003 -> b.short.toLong()
        0x0004 -> b.short.toLong() and 0xFFFF
        0x0005 -> b.int.toLong()
        0x0006 -> b.int.toLong() and 0xFFFFFFFFL
        0x0007, 0x0008 -> b.long
        0x0009, 0x000A -> { val lo = b.long; b.long; lo }
        Ptp.DT_STR -> { val n = b.get().toInt() and 0xFF; b.position(b.position() + 2 * n); 0L }
        else -> if (type and 0x4000 != 0) {
            val n = b.int
            val elem = type and 0x0FFF
            repeat(n) { readValue(b, elem) }
            0L
        } else throw IllegalStateException("Unknown PTP data type 0x%04X".format(type))
    }

    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) or ((d[o + 1].toLong() and 0xFF) shl 8) or
            ((d[o + 2].toLong() and 0xFF) shl 16) or ((d[o + 3].toLong() and 0xFF) shl 24))
}

package app.camlink.usb.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import app.camlink.usb.DebugLog
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpException(val code: Int, msg: String) : IOException(msg)

/** PTP response: response code, response params and the data phase (if the device sent one). */
class PtpResponse(val code: Int, val params: IntArray, val data: ByteArray?) {
    val ok get() = code == Ptp.RC_OK
}

/**
 * Minimal PTP-over-USB (Still Image class) transport using Android's USB host API.
 * Not thread safe: callers must serialise transactions.
 */
class PtpUsb private constructor(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : Closeable {

    private var transactionId = 0
    private val readBuf = ByteArray(CHUNK)

    companion object {
        /** Multiple of every USB bulk max packet size (64/512/1024). */
        private const val CHUNK = 16 * 1024 // safe on every Android usbfs; multiple of all max packet sizes
        private const val HEADER = 12

        /** Returns the PTP / Still Image interface of [device], or null if it has none. */
        fun findPtpInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

        fun open(manager: UsbManager, device: UsbDevice): PtpUsb {
            val intf = findPtpInterface(device)
                ?: throw IOException("This USB device has no PTP interface. Set the camera's USB Connection to \"PC Remote\".")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn == null || epOut == null) throw IOException("PTP bulk endpoints not found")
            val epEvent = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN
            }
            DebugLog.d("Device ${device.productName} %04X:%04X, interface #${intf.id} alt ${intf.alternateSetting}, %d endpoints".format(
                device.vendorId, device.productId, intf.endpointCount))
            DebugLog.d("bulk IN 0x%02X (max %d), bulk OUT 0x%02X (max %d), event 0x%02X".format(
                epIn.address, epIn.maxPacketSize, epOut.address, epOut.maxPacketSize, epEvent?.address ?: 0))
            val conn = manager.openDevice(device) ?: throw IOException("Could not open USB device (permission?)")
            if (!conn.claimInterface(intf, true)) {
                conn.close(); throw IOException("Could not claim the camera's USB interface (another app is using it?)")
            }
            DebugLog.d("Interface claimed")
            val p = PtpUsb(conn, intf, epIn, epOut)
            // Like Sony's own USB layer: keep the event (interrupt) pipe drained from the very start.
            // A Sony body stays "busy" and refuses commands while an event sits unread.
            if (epEvent != null) p.startEventReader(epEvent)
            p.waitUntilReady()
            return p
        }
    }

    // ------------------------------------------------------------------ event pipe

    @Volatile private var eventThread: Thread? = null
    /** Called for every PTP event: (event code, params). */
    @Volatile var onEvent: ((Int, IntArray) -> Unit)? = null

    private fun startEventReader(ep: UsbEndpoint) {
        val t = Thread({
            val buf = ByteArray(maxOf(64, ep.maxPacketSize))
            while (!Thread.currentThread().isInterrupted) {
                val n = try { conn.bulkTransfer(ep, buf, 0, buf.size, 250) } catch (_: Exception) { -1 }
                if (n >= 12 && le16(buf, 4) == 4) {
                    val code = le16(buf, 6)
                    val np = (minOf(le32(buf, 0), n) - 12) / 4
                    val params = IntArray(maxOf(0, np)) { le32(buf, 12 + 4 * it) }
                    DebugLog.d("Event 0x%04X %s".format(code, params.joinToString { "0x%X".format(it) }))
                    runCatching { onEvent?.invoke(code, params) }
                } else if (n > 0) {
                    DebugLog.d("Event pipe: $n bytes")
                }
                // n < 0 is the normal 250 ms timeout when nothing is pending.
            }
        }, "ptp-events")
        t.isDaemon = true
        t.start()
        eventThread = t
    }

    /** Polls the Still Image class status until the camera reports OK (max ~4 s). */
    fun waitUntilReady() {
        val end = System.currentTimeMillis() + 4000
        var code = statusCode()
        DebugLog.d("Device status: 0x%04X".format(code))
        while (code == Ptp.RC_DEVICE_BUSY && System.currentTimeMillis() < end) {
            Thread.sleep(100)
            code = statusCode()
        }
        if (code != Ptp.RC_DEVICE_BUSY) DebugLog.d("Device status now: 0x%04X".format(code))
        else DebugLog.d("Camera still busy after 4 s, trying anyway")
    }

    private fun statusCode(): Int {
        val buf = ByteArray(32)
        val n = conn.controlTransfer(0xA1, 0x67, 0, intf.id, buf, buf.size, 1000)
        return if (n >= 4) le16(buf, 2) else -1
    }

    // ------------------------------------------------------------------ recovery helpers

    /** USB CLEAR_FEATURE(ENDPOINT_HALT) on both bulk endpoints. */
    fun clearHalts() {
        val a = conn.controlTransfer(0x02, 0x01, 0, epIn.address, null, 0, 1000)
        val b = conn.controlTransfer(0x02, 0x01, 0, epOut.address, null, 0, 1000)
        DebugLog.d("Clear halt IN=$a OUT=$b")
    }

    /** PTP Still Image class "Get Device Status" (0x67). Logs the status code (0x2001 = OK). */
    fun logStatus() {
        val buf = ByteArray(32)
        val n = conn.controlTransfer(0xA1, 0x67, 0, intf.id, buf, buf.size, 1000)
        val code = if (n >= 4) (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8) else -1
        DebugLog.d("Device status: len=$n code=0x%04X".format(code))
    }

    /** PTP Still Image class "Device Reset" (0x66), clears halts and discards stale data from the camera. */
    fun reset() {
        val r = conn.controlTransfer(0x21, 0x66, 0, intf.id, null, 0, 3000)
        DebugLog.d("Device reset -> $r")
        Thread.sleep(300)
        clearHalts()
        drain()
        transactionId = 0
        logStatus()
    }

    /**
     * Stall recovery from the PTP-over-USB spec (what Sony's layer does on "STALL detected"):
     * read device status, clear the halted endpoints, wait until the camera is no longer busy,
     * and drop any half-sent data. The session stays open, so the next transaction just works.
     */
    fun recover() {
        DebugLog.d("Recovering stalled pipe, status 0x%04X".format(statusCode()))
        clearHalts()
        val end = System.currentTimeMillis() + 5000
        var code = statusCode()
        while (code == Ptp.RC_DEVICE_BUSY && System.currentTimeMillis() < end) {
            Thread.sleep(100)
            code = statusCode()
        }
        drain()
        DebugLog.d("Recovered, status 0x%04X".format(code))
    }

    /** Reads and throws away anything left in the IN pipe (e.g. from a session another app started). */
    fun drain() {
        var total = 0
        for (i in 0 until 8) {
            val r = conn.bulkTransfer(epIn, readBuf, 0, readBuf.size, 150)
            if (r <= 0) break
            total += r
        }
        if (total > 0) DebugLog.d("Drained $total stale bytes")
    }

    /**
     * Runs one PTP transaction.
     * @param sendData payload for an outgoing data phase, or null
     */
    fun transact(op: Int, vararg params: Int, sendData: ByteArray? = null, timeoutMs: Int = 5000): PtpResponse {
        val tid = ++transactionId

        // --- command phase
        val cmd = ByteBuffer.allocate(HEADER + 4 * params.size).order(ByteOrder.LITTLE_ENDIAN)
        cmd.putInt(HEADER + 4 * params.size).putShort(Ptp.TYPE_COMMAND.toShort()).putShort(op.toShort()).putInt(tid)
        params.forEach { cmd.putInt(it) }
        write(cmd.array(), timeoutMs)

        // --- data-out phase
        if (sendData != null) {
            val d = ByteBuffer.allocate(HEADER + sendData.size).order(ByteOrder.LITTLE_ENDIAN)
            d.putInt(HEADER + sendData.size).putShort(Ptp.TYPE_DATA.toShort()).putShort(op.toShort()).putInt(tid)
            d.put(sendData)
            write(d.array(), timeoutMs)
        }

        // --- data-in and/or response phase
        var data: ByteArray? = null
        var n = readPacket(timeoutMs)
        if (n >= HEADER && le16(readBuf, 4) == Ptp.TYPE_DATA) {
            val total = le32(readBuf, 0).toLong() and 0xFFFFFFFFL
            if (total < HEADER || total > Int.MAX_VALUE) throw IOException("Bad PTP data length $total")
            val payload = (total - HEADER).toInt()
            val buf = ByteArray(payload)
            var got = n - HEADER
            System.arraycopy(readBuf, HEADER, buf, 0, got)
            while (got < payload) {
                val want = minOf(CHUNK, payload - got)
                val r = conn.bulkTransfer(epIn, buf, got, want, timeoutMs)
                if (r < 0) throw IOException("USB read failed while receiving data ($got/$payload bytes)")
                got += r
            }
            data = buf
            n = readPacket(timeoutMs)
        }
        // Skip zero-length packets that terminate a data phase.
        var guard = 0
        while (n == 0 && guard++ < 3) n = readPacket(timeoutMs)

        if (n < HEADER || le16(readBuf, 4) != Ptp.TYPE_RESPONSE)
            throw IOException("Unexpected PTP container (len=$n)")
        val code = le16(readBuf, 6)
        val np = (minOf(le32(readBuf, 0), n) - HEADER) / 4
        val rp = IntArray(maxOf(0, np)) { le32(readBuf, HEADER + 4 * it) }
        return PtpResponse(code, rp, data)
    }

    /** Like [transact] but throws if the response is not OK. */
    fun call(op: Int, vararg params: Int, sendData: ByteArray? = null, timeoutMs: Int = 5000): PtpResponse {
        val r = transact(op, *params, sendData = sendData, timeoutMs = timeoutMs)
        if (!r.ok) throw PtpException(r.code, "PTP op 0x%04X failed: 0x%04X".format(op, r.code))
        return r
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        var off = 0
        while (off < bytes.size) {
            val len = minOf(CHUNK, bytes.size - off)
            val r = conn.bulkTransfer(epOut, bytes, off, len, timeoutMs)
            if (r < 0) {
                DebugLog.d("bulk OUT failed: r=$r, offset=$off, len=$len, op=0x%04X".format(
                    if (bytes.size >= 8) (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8) else 0))
                throw IOException("USB write failed")
            }
            off += r
        }
        // Terminate with a zero-length packet when the transfer ends exactly on a packet boundary.
        if (bytes.size % epOut.maxPacketSize == 0) conn.bulkTransfer(epOut, bytes, 0, 0, timeoutMs)
    }

    private fun readPacket(timeoutMs: Int): Int {
        val r = conn.bulkTransfer(epIn, readBuf, 0, readBuf.size, timeoutMs)
        if (r < 0) {
            DebugLog.d("bulk IN failed: r=$r (timeout ${timeoutMs}ms)")
            throw IOException("USB read failed (camera disconnected or timed out)")
        }
        return r
    }

    override fun close() {
        eventThread?.interrupt()
        runCatching { eventThread?.join(500) }
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}

object Ptp {
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3

    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_ACCESS_DENIED = 0x200F
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_SESSION_ALREADY_OPEN = 0x201E

    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_GET_THUMB = 0x100A

    // Data type codes
    const val DT_INT8 = 0x0001
    const val DT_UINT8 = 0x0002
    const val DT_INT16 = 0x0003
    const val DT_UINT16 = 0x0004
    const val DT_INT32 = 0x0005
    const val DT_UINT32 = 0x0006
    const val DT_STR = 0xFFFF
}

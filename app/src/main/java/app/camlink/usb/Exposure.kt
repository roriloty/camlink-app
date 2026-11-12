package app.camlink.usb

import app.camlink.usb.ptp.SonyCamera

/** One exposure setting shown in the control bar. */
data class SettingUi(
    val code: Int,
    val label: String,
    val value: String,
    /** False when the body greys it out (e.g. shutter in A mode, EV when the EV dial is not at 0). */
    val adjustable: Boolean,
    /** Read-only by design (exposure mode is the physical dial on the body). */
    val readOnly: Boolean = false,
    /** Raw current value. */
    val raw: Long = 0,
    /** For list settings (WB, focus mode, AF area): every choice the camera offers, as (value, label). */
    val options: List<Pair<Long, String>> = emptyList(),
)

/**
 * Turns raw Sony property values into the labels a photographer expects.
 * Value encodings verified against an ILCE-7M3.
 */
object Exposure {
    /** Order of the controls in the UI. */
    val CODES = listOf(
        SonyCamera.DPC_EXPOSURE_PROGRAM, SonyCamera.DPC_F_NUMBER, SonyCamera.DPC_SHUTTER_SPEED,
        SonyCamera.DPC_ISO, SonyCamera.DPC_EXPOSURE_COMP, SonyCamera.DPC_WHITE_BALANCE,
        SonyCamera.DPC_FOCUS_MODE, SonyCamera.DPC_FOCUS_AREA,
    )

    /** White balance is a list (set directly); the others are dials (stepped one notch at a time). */
    fun isList(code: Int) = code == SonyCamera.DPC_WHITE_BALANCE || code == SonyCamera.DPC_FOCUS_AREA ||
        code == SonyCamera.DPC_FOCUS_MODE

    /** Reported as display-only on protocol-2 bodies but accepted when set directly (as libgphoto2 does). */
    private val ALWAYS_SETTABLE = setOf(SonyCamera.DPC_FOCUS_MODE)

    fun build(info: Map<Int, SonyCamera.PropInfo>): List<SettingUi> = CODES.mapNotNull { code ->
        val p = info[code] ?: return@mapNotNull null
        val readOnly = code == SonyCamera.DPC_EXPOSURE_PROGRAM
        val adjustable = !readOnly && (p.enabled == 1 || (code in ALWAYS_SETTABLE && p.enumValues != null))
        val options = if (isList(code)) p.enumValues.orEmpty().distinct().map { it to format(code, it) } else emptyList()
        SettingUi(code, label(code), format(code, p.current), adjustable = adjustable, readOnly = readOnly,
            raw = p.current, options = options)
    }

    fun label(code: Int) = when (code) {
        SonyCamera.DPC_EXPOSURE_PROGRAM -> "MODE"
        SonyCamera.DPC_F_NUMBER -> "APERTURE"
        SonyCamera.DPC_SHUTTER_SPEED -> "SHUTTER"
        SonyCamera.DPC_ISO -> "ISO"
        SonyCamera.DPC_EXPOSURE_COMP -> "EV"
        SonyCamera.DPC_WHITE_BALANCE -> "WB"
        SonyCamera.DPC_FOCUS_AREA -> "AF AREA"
        SonyCamera.DPC_FOCUS_MODE -> "FOCUS"
        else -> "%04X".format(code)
    }

    fun format(code: Int, v: Long): String = when (code) {
        SonyCamera.DPC_EXPOSURE_PROGRAM -> mode(v)
        SonyCamera.DPC_F_NUMBER -> if (v <= 0) "--" else "F" + trim(v / 100.0)
        SonyCamera.DPC_SHUTTER_SPEED -> shutter(v)
        SonyCamera.DPC_ISO -> iso(v)
        SonyCamera.DPC_EXPOSURE_COMP -> ev(v)
        SonyCamera.DPC_WHITE_BALANCE -> whiteBalance(v)
        SonyCamera.DPC_FOCUS_AREA -> focusArea(v)
        SonyCamera.DPC_FOCUS_MODE -> focusMode(v)
        else -> v.toString()
    }

    fun focusMode(v: Long) = when (v) {
        1L -> "MF"; 2L -> "AF-S"; 3L -> "AF Macro"
        0x8004L -> "AF-C"; 0x8005L -> "AF-A"; 0x8006L -> "DMF"; 0x8008L -> "AF-D"; 0x8009L -> "Preset"
        else -> "AF"
    }

    /** Focus area codes as listed by the A7 III (0x2xx = the same areas with Lock-on AF). */
    fun focusArea(v: Long): String {
        val base = when (v and 0xFF) {
            0x01L -> if (v < 0x100) "Wide" else "Spot S"
            0x02L -> if (v < 0x100) "Zone" else "Spot M"
            0x03L -> if (v < 0x100) "Center" else "Spot L"
            0x04L -> "Exp. Spot"
            0x05L -> "Spot M"; 0x06L -> "Spot L"; 0x07L -> "Exp. Spot"
            else -> "0x%X".format(v)
        }
        return when (v) {
            0x201L -> "Lock Wide"; 0x202L -> "Lock Zone"; 0x203L -> "Lock Center"
            0x204L -> "Lock Spot S"; 0x205L -> "Lock Spot M"; 0x206L -> "Lock Spot L"; 0x207L -> "Lock Exp."
            else -> base
        }
    }

    private fun trim(d: Double): String {
        val r = Math.round(d * 10) / 10.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun mode(v: Long) = when (v) {
        1L -> "M"; 2L -> "P"; 3L -> "A"; 4L -> "S"
        0x8000L -> "AUTO"; 0x8001L -> "AUTO+"
        0x8050L -> "Movie P"; 0x8051L -> "Movie A"; 0x8052L -> "Movie S"; 0x8053L -> "Movie M"
        0x8084L -> "S&Q P"; 0x8085L -> "S&Q A"; 0x8086L -> "S&Q S"; 0x8087L -> "S&Q M"
        else -> "SCN"
    }

    /** Encoded as numerator<<16 | denominator; 0 = BULB. */
    private fun shutter(v: Long): String {
        if (v == 0L) return "BULB"
        val n = (v shr 16).toInt()
        val d = (v and 0xFFFF).toInt()
        if (d == 0) return "--"
        return when {
            d == 1 -> "$n\""
            n == 1 -> "1/$d"
            n >= d -> trim(n.toDouble() / d) + "\""
            else -> trim(n.toDouble() / d) + "\""
        }
    }

    /** 0xFFFFFF = auto; the low 24 bits are the ISO; upper bits flag multi-frame NR. */
    private fun iso(v: Long): String {
        val base = v and 0xFFFFFF
        if (base == 0xFFFFFFL) return "AUTO"
        val nr = (v shr 24) != 0L
        return base.toString() + if (nr) " NR" else ""
    }

    /** Signed thousandths of a stop. */
    private fun ev(v: Long): String {
        val s = v.toShort().toInt() / 1000.0
        return when {
            s > 0 -> "+" + "%.1f".format(s)
            s < 0 -> "%.1f".format(s)
            else -> "±0.0"
        }
    }

    private fun whiteBalance(v: Long) = when (v) {
        0x0002L -> "Auto"
        0x0004L -> "Daylight"
        0x8011L -> "Shade"
        0x8010L -> "Cloudy"
        0x0006L -> "Tungsten"
        0x8001L -> "Fluor. Warm"
        0x8002L -> "Fluor. Cool"
        0x8003L -> "Fluor. Day W"
        0x8004L -> "Fluor. Day"
        0x0007L -> "Flash"
        0x8030L -> "Underwater"
        0x8012L -> "K / Filter"
        0x8020L -> "Custom 1"
        0x8021L -> "Custom 2"
        0x8022L -> "Custom 3"
        else -> "0x%X".format(v)
    }
}

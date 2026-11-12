package app.camlink.usb.edit

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Runs the Kotlin auto-edit on images written by D:/camlink-autoedit/work/verify.py
 * (<name>_small.rgb drives the plan, <name>_full.rgb is edited) and writes <name>_kt.rgb,
 * which verify.py compares with the Python reference. Skipped when the folder is absent.
 *
 * .rgb format: int32 width, int32 height (big-endian), then width*height*3 bytes R,G,B.
 */
class AutoEditCoreTest {
    private val dir = File(System.getProperty("autoedit.verify.dir") ?: "D:/camlink-autoedit/verify")

    private fun read(f: File): Triple<IntArray, Int, Int> = DataInputStream(f.inputStream().buffered()).use { s ->
        val w = s.readInt(); val h = s.readInt()
        val b = ByteArray(w * h * 3); s.readFully(b)
        Triple(IntArray(w * h) { i ->
            (0xFF shl 24) or ((b[3 * i].toInt() and 0xFF) shl 16) or ((b[3 * i + 1].toInt() and 0xFF) shl 8) or (b[3 * i + 2].toInt() and 0xFF)
        }, w, h)
    }

    private fun write(f: File, px: IntArray, w: Int, h: Int) = DataOutputStream(f.outputStream().buffered()).use { s ->
        s.writeInt(w); s.writeInt(h)
        val b = ByteArray(w * h * 3)
        for (i in px.indices) { val p = px[i]; b[3 * i] = (p shr 16).toByte(); b[3 * i + 1] = (p shr 8).toByte(); b[3 * i + 2] = p.toByte() }
        s.write(b)
    }

    @Test
    fun matchesPythonReference() {
        assumeTrue(dir.isDirectory)
        val smalls = dir.listFiles { f -> f.name.endsWith("_small.rgb") }.orEmpty().sortedBy { it.name }
        assumeTrue(smalls.isNotEmpty())
        for (s in smalls) {
            val name = s.name.removeSuffix("_small.rgb")
            val (sp, sw, sh) = read(s)
            val (fp, fw, fh) = read(File(dir, "${name}_full.rgb"))
            val t0 = System.nanoTime()
            val plan = AutoEditCore.plan(sp, sw, sh)
            val t1 = System.nanoTime()
            var y = 0
            while (y < fh) {
                val rows = minOf(256, fh - y)
                AutoEditCore.render(fp, y * fw, fw, rows, y, fh, plan)
                y += rows
            }
            val t2 = System.nanoTime()
            write(File(dir, "${name}_kt.rgb"), fp, fw, fh)
            println("%s plan %.0f ms, render %dx%d %.0f ms".format(name, (t1 - t0) / 1e6, fw, fh, (t2 - t1) / 1e6))
        }
    }
}

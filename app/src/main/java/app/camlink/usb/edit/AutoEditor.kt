package app.camlink.usb.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Applies [AutoEditCore] to a saved JPEG and writes an edited copy (the original is never touched). */
object AutoEditor {
    private const val PLAN_LONG_SIDE = 900
    private const val BAND_ROWS = 256

    /** Returns true when [out] holds the edited JPEG. */
    fun edit(context: Context, src: Uri, out: File): Boolean = try {
        val cr = context.contentResolver
        // 1. Plan from a ~900 px copy (same size the model was trained at).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(src)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= PLAN_LONG_SIDE) sample *= 2
        val decoded = cr.openInputStream(src)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("cannot decode")
        val s = PLAN_LONG_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val small = Bitmap.createScaledBitmap(decoded, Math.round(decoded.width * s), Math.round(decoded.height * s), true)
        if (small !== decoded) decoded.recycle()
        val sp = IntArray(small.width * small.height)
        small.getPixels(sp, 0, small.width, 0, 0, small.width, small.height)
        val plan = AutoEditCore.plan(sp, small.width, small.height)
        small.recycle()

        // 2. Render the full-size image band by band.
        val full = cr.openInputStream(src)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: error("cannot decode full image")
        val w = full.width; val h = full.height
        val band = IntArray(w * BAND_ROWS)
        var y = 0
        while (y < h) {
            val rows = minOf(BAND_ROWS, h - y)
            full.getPixels(band, 0, w, 0, y, w, rows)
            AutoEditCore.render(band, 0, w, rows, y, h, plan)
            full.setPixels(band, 0, w, 0, y, w, rows)
            y += rows
        }
        out.parentFile?.mkdirs()
        out.outputStream().use { full.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        full.recycle()

        // 3. Keep the camera's metadata (orientation, exposure, lens, date, GPS…).
        copyExif(context, src, out)
        true
    } catch (e: Throwable) {
        Log.w("CamLinkUSB", "Auto-edit failed, uploading the original", e)
        out.delete()
        false
    }

    private val TAGS = listOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
        ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_SCENE_CAPTURE_TYPE,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun copyExif(context: Context, src: Uri, out: File) = runCatching {
        val a = context.contentResolver.openInputStream(src)!!.use { ExifInterface(it) }
        val b = ExifInterface(out.absolutePath)
        for (t in TAGS) a.getAttribute(t)?.let { b.setAttribute(t, it) }
        b.setAttribute(ExifInterface.TAG_SOFTWARE, "CamLink USB auto-edit")
        b.saveAttributes()
    }
}

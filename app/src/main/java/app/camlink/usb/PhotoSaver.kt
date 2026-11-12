package app.camlink.usb

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

object PhotoSaver {
    private const val FOLDER = "CamLink"

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw", "srf", "sr2" -> "image/x-sony-arw"
        "hif", "heif", "heic" -> "image/heif"
        "tif", "tiff" -> "image/tiff"
        else -> "application/octet-stream"
    }

    /**
     * Writes the camera's file into Pictures/CamLink exactly as received (no re-encoding),
     * so the saved file is the camera's original quality.
     */
    fun save(context: Context, fileName: String, bytes: ByteArray): Uri {
        val mime = mimeFor(fileName)
        val collections = buildList {
            if (mime.startsWith("image/")) add(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
            add(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY))
        }
        var lastError: Exception? = null
        for (collection in collections) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try { resolver.insert(collection, values) } catch (e: Exception) { lastError = e; null } ?: continue
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                lastError = e
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        throw lastError ?: IllegalStateException("Could not save $fileName")
    }
}

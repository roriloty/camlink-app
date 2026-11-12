package app.camlink.usb.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class ItemStatus { QUEUED, EDITING, UPLOADING, DONE, FAILED, DELETING }

/** One uploaded copy: kind "ftp" (ref = "dir|name") or "drive" (ref = file id). */
data class Remote(val kind: String, val ref: String)

data class UploadItem(
    val id: String,
    val name: String,
    val uri: String,
    val time: Long,
    val size: Long = 0,
    val status: ItemStatus = ItemStatus.QUEUED,
    val progress: Float = 0f,
    /** "FTP" / "Drive" while sending. */
    val dest: String? = null,
    val remotes: List<Remote> = emptyList(),
    val error: String? = null,
    /** Bumped when the thumbnail file changes (after auto-edit), so the UI reloads it. */
    val thumbVersion: Int = 0,
) {
    val inQueue get() = status != ItemStatus.DONE
}

/** Thrown inside an upload when the user deleted that photo meanwhile. */
class UploadCancelled : RuntimeException("Deleted by user")

/**
 * Everything queued or uploaded, newest first, with a small thumbnail per photo. Kept in a JSON file
 * so it survives restarts; the full and lite apps each have their own.
 */
object UploadHistory {
    private const val TAG = "CamLinkUSB"
    private const val MAX_ITEMS = 500
    private val _items = MutableStateFlow<List<UploadItem>>(emptyList())
    val items: StateFlow<List<UploadItem>> = _items.asStateFlow()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var app: Context? = null

    fun init(context: Context) {
        if (app != null) return
        synchronized(this) {
            if (app != null) return
            app = context.applicationContext
            _items.value = runCatching { load() }.getOrDefault(emptyList()).map {
                // An app restart interrupts a running upload; WorkManager runs it again.
                if (it.status == ItemStatus.EDITING || it.status == ItemStatus.UPLOADING) it.copy(status = ItemStatus.QUEUED, progress = 0f, dest = null) else it
            }
        }
        // Finish deletes that were interrupted.
        _items.value.filter { it.status == ItemStatus.DELETING }.forEach { scope.launch { purge(it.id) } }
    }

    private val file get() = File(app!!.filesDir, "uploads.json")
    fun thumbFile(context: Context, id: String) = File(context.filesDir, "thumbs/$id.jpg")

    fun find(id: String) = _items.value.firstOrNull { it.id == id }

    // ------------------------------------------------------------------ queue side

    /** Adds a newly queued photo (with its thumbnail) and returns its id. */
    fun add(context: Context, uri: Uri, name: String, size: Long): String {
        init(context)
        val id = UUID.randomUUID().toString()
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()?.let { writeThumb(context, id, it) { context.contentResolver.openInputStream(uri) } }
        _items.update { (listOf(UploadItem(id, name, uri.toString(), System.currentTimeMillis(), size)) + it).take(MAX_ITEMS) }
        save()
        return id
    }

    /** Replaces the thumbnail with one of the edited copy. */
    fun thumbFromEdited(context: Context, id: String, edited: File) {
        if (writeThumb(context, id, edited.inputStream()) { edited.inputStream() })
            change(id) { it.copy(thumbVersion = it.thumbVersion + 1) }
    }

    internal fun change(id: String, persist: Boolean = true, f: (UploadItem) -> UploadItem) {
        _items.update { list -> list.map { if (it.id == id) f(it) else it } }
        if (persist) save()
    }

    internal fun progress(id: String, p: Float) {
        if (id in cancelled) throw UploadCancelled()
        change(id, persist = false) { it.copy(progress = p) }
    }

    fun isCancelled(id: String) = id in cancelled || find(id).let { it == null || it.status == ItemStatus.DELETING }

    // ------------------------------------------------------------------ user actions

    /**
     * Deletes the photo everywhere: stops its upload if queued or running, removes the phone copy,
     * the FTP copies and moves the Drive copies to Drive's trash.
     */
    fun delete(context: Context, id: String) {
        init(context)
        val item = find(id) ?: return
        cancelled += id
        val running = item.status == ItemStatus.EDITING || item.status == ItemStatus.UPLOADING
        change(id) { it.copy(status = ItemStatus.DELETING, error = null) }
        // A running upload notices the flag on its next chunk and calls purge itself.
        if (!running) scope.launch { purge(id) }
    }

    /** Tries a failed upload again. */
    fun retry(context: Context, id: String) {
        val item = find(id) ?: return
        change(id) { it.copy(status = ItemStatus.QUEUED, error = null, progress = 0f) }
        Uploads.enqueue(context, Uri.parse(item.uri), item.name, item.size, existingId = id)
    }

    /** Removes finished uploads from the list (the files stay where they are). */
    fun clearUploaded(context: Context) {
        init(context)
        val gone = _items.value.filter { it.status == ItemStatus.DONE }
        _items.update { list -> list.filter { it.status != ItemStatus.DONE } }
        gone.forEach { thumbFile(context, it.id).delete() }
        save()
    }

    /** Called by the worker (or [delete]) to remove every copy, then the entry itself. */
    internal fun purge(id: String, extra: List<Remote> = emptyList()) {
        val ctx = app ?: return
        val item = find(id) ?: return
        val remotes = (item.remotes + extra).distinct()
        val errors = deleteRemotes(ctx, remotes)
        runCatching { ctx.contentResolver.delete(Uri.parse(item.uri), null, null) }
        if (errors.isNotEmpty()) {
            Log.w(TAG, "Delete of ${item.name} incomplete: $errors")
            // Keep what couldn't be removed so the user can try again.
            val left = remotes.filter { r -> errors.any { it.first == r } }
            change(id) { it.copy(status = ItemStatus.DONE, remotes = left, error = "Couldn't delete: " + errors.first().second) }
            cancelled -= id
            return
        }
        thumbFile(ctx, id).delete()
        _items.update { list -> list.filter { it.id != id } }
        cancelled -= id
        save()
    }

    private fun deleteRemotes(ctx: Context, remotes: List<Remote>): List<Pair<Remote, String>> {
        if (remotes.isEmpty()) return emptyList()
        val s = FtpSettingsStore.load(ctx)
        val errors = ArrayList<Pair<Remote, String>>()
        val ftp = remotes.filter { it.kind == "ftp" }
        if (ftp.isNotEmpty()) runCatching {
            FtpUploader.delete(s, ftp.map { it.ref.substringBefore('|') to it.ref.substringAfter('|') })
        }.onFailure { e -> ftp.forEach { errors += it to (e.message ?: "FTP error") } }
        val drive = remotes.filter { it.kind == "drive" }
        if (drive.isNotEmpty()) {
            try {
                var token = DriveAuth.tokenBlocking(ctx, s.driveAccount)
                for (r in drive) try {
                    try { DriveApi.trash(token, r.ref) } catch (e: DriveApi.Unauthorized) {
                        DriveAuth.invalidate(ctx, token); token = DriveAuth.tokenBlocking(ctx, s.driveAccount); DriveApi.trash(token, r.ref)
                    }
                } catch (e: Exception) { errors += r to (e.message ?: "Drive error") }
            } catch (e: Exception) { drive.forEach { errors += it to (e.message ?: "Drive sign-in") } }
        }
        return errors
    }

    // ------------------------------------------------------------------ thumbnails + storage

    /** ~320 px JPEG, upright. Returns false for files Android can't decode (RAW). */
    private fun writeThumb(context: Context, id: String, first: InputStream, again: () -> InputStream?): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        first.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) false else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 320) sample *= 2
            val bmp = again()?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            val rot = again()?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (bmp == null) false else {
                val upright = if (rot != 0) Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true) else bmp
                val f = thumbFile(context, id); f.parentFile?.mkdirs()
                f.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                true
            }
        }
    } catch (e: Exception) { Log.w(TAG, "thumbnail failed", e); false }

    @Synchronized private fun save() {
        val arr = JSONArray()
        for (it in _items.value) arr.put(JSONObject()
            .put("id", it.id).put("name", it.name).put("uri", it.uri).put("time", it.time).put("size", it.size)
            .put("status", it.status.name).put("error", it.error ?: JSONObject.NULL).put("tv", it.thumbVersion)
            .put("remotes", JSONArray().apply { it.remotes.forEach { r -> put(JSONObject().put("k", r.kind).put("r", r.ref)) } }))
        runCatching {
            val tmp = File(file.path + ".tmp"); tmp.writeText(arr.toString()); tmp.renameTo(file)
        }
    }

    private fun load(): List<UploadItem> {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val rs = o.optJSONArray("remotes") ?: JSONArray()
            UploadItem(
                id = o.getString("id"), name = o.getString("name"), uri = o.getString("uri"), time = o.getLong("time"), size = o.optLong("size"),
                status = runCatching { ItemStatus.valueOf(o.getString("status")) }.getOrDefault(ItemStatus.QUEUED),
                error = if (o.isNull("error")) null else o.optString("error"),
                thumbVersion = o.optInt("tv"),
                remotes = (0 until rs.length()).map { rs.getJSONObject(it) }.map { Remote(it.getString("k"), it.getString("r")) },
            )
        }
    }
}

package app.camlink.usb.upload

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Small upload summary for the status bar. */
data class UploadState(
    val pending: Int = 0,
    /** File being sent, with its destination ("DSC01041.JPG → Drive"). */
    val current: String? = null,
    /** True while the current photo is being auto-edited (before its upload starts). */
    val editing: Boolean = false,
    /** 0..1 for the file being sent. */
    val progress: Float = 0f,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
)

object Uploads {
    private val _state = MutableStateFlow(UploadState())
    val state: StateFlow<UploadState> = _state.asStateFlow()

    /** Queues [uri] for upload if FTP and/or Drive is on and the file type is wanted. Survives app restarts. */
    fun enqueue(context: Context, uri: Uri, name: String, size: Long, existingId: String? = null) {
        val s = FtpSettingsStore.load(context)
        if (!s.anyDestination || !s.wants(name)) return
        val id = existingId ?: UploadHistory.add(context, uri, name, size)
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("uri" to uri.toString(), "name" to name, "size" to size, "id" to id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        // One file at a time, in shooting order.
        WorkManager.getInstance(context).enqueueUniqueWork("ftp-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        _state.update { it.copy(pending = it.pending + 1) }
    }

    internal fun started(name: String) = _state.update { it.copy(current = name, progress = 0f, editing = false) }
    internal fun editing(name: String) = _state.update { it.copy(current = name, progress = 0f, editing = true) }
    internal fun progress(p: Float) = _state.update { it.copy(progress = p) }
    internal fun finished(ok: Boolean, error: String?, final: Boolean) = _state.update {
        it.copy(
            current = null, progress = 0f, editing = false,
            pending = if (ok || final) (it.pending - 1).coerceAtLeast(0) else it.pending,
            uploaded = it.uploaded + if (ok) 1 else 0,
            failed = it.failed + if (!ok && final) 1 else 0,
            lastError = if (ok) it.lastError else error,
        )
    }

    const val TAG = "ftp-upload"
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        val name = inputData.getString("name") ?: "photo"
        val size = inputData.getLong("size", 0L).coerceAtLeast(1L)
        val settings = FtpSettingsStore.load(applicationContext)
        UploadHistory.init(applicationContext)
        val id = inputData.getString("id")
        if (id != null && UploadHistory.isCancelled(id)) {
            Uploads.finished(ok = false, error = null, final = true)
            return@withContext Result.success() // deleted by the user while waiting
        }
        if (!settings.anyDestination) {
            id?.let { UploadHistory.change(it) { i -> i.copy(status = ItemStatus.FAILED, error = "Upload is switched off") } }
            Uploads.finished(ok = false, error = null, final = true)
            return@withContext Result.success() // upload was switched off meanwhile
        }
        // Pieces already delivered by an earlier attempt, so a retry doesn't make duplicates on Drive.
        val done = applicationContext.getSharedPreferences("upload_done", Context.MODE_PRIVATE)
        val doneKey = "$uri|"
        var edited: File? = null
        val sent = ArrayList<Remote>()
        fun mark(f: (UploadItem) -> UploadItem) { id?.let { UploadHistory.change(it, f = f) } }
        fun step(p: Float) { Uploads.progress(p); id?.let { UploadHistory.progress(it, p) } }
        fun begin(label: String, dest: String) {
            if (id != null && UploadHistory.isCancelled(id)) throw UploadCancelled()
            Uploads.started("$label → $dest")
            mark { it.copy(status = ItemStatus.UPLOADING, dest = dest, progress = 0f, error = null) }
        }
        fun record(r: Remote) { sent += r; mark { it.copy(remotes = (it.remotes + r).distinct()) } }
        try {
            val isJpeg = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)
            if (settings.autoEdit && isJpeg) {
                Uploads.editing(name)
                mark { it.copy(status = ItemStatus.EDITING, error = null) }
                val f = File(applicationContext.cacheDir, "autoedit/$name")
                if (app.camlink.usb.edit.AutoEditor.edit(applicationContext, uri, f)) {
                    edited = f
                    id?.let { UploadHistory.thumbFromEdited(applicationContext, it, f) }
                }
            }
            // What goes where: the edited copy and/or the original, each to its folder.
            class Job(val ftpDir: String, val driveSub: String, val remoteName: String, val size: Long, val open: () -> InputStream?)
            val jobs = ArrayList<Job>()
            val file = edited
            val sendOriginal = file == null || settings.uploadOriginalToo
            if (file != null) {
                val sameFolder = sendOriginal && settings.editedSub() == settings.originalSub()
                val remote = if (sameFolder) name.substringBeforeLast('.') + "_edited." + name.substringAfterLast('.', "JPG") else name
                jobs += Job(settings.editedDir(), settings.editedSub(), remote, file.length().coerceAtLeast(1L)) { file.inputStream() }
            }
            if (sendOriginal) {
                jobs += Job(settings.originalDir(), settings.originalSub(), name, size) { applicationContext.contentResolver.openInputStream(uri) }
            }
            val drive = if (settings.driveReady) DriveTarget(applicationContext, settings) else null
            for (job in jobs) {
                if (settings.isComplete) {
                    val key = doneKey + "ftp|" + job.ftpDir + "/" + job.remoteName
                    if (!done.getBoolean(key, false)) {
                        begin(job.remoteName, "FTP")
                        FtpUploader.upload(settings, job.ftpDir, job.remoteName, job.size, { b -> step(b.toFloat() / job.size) }, job.open)
                        done.edit().putBoolean(key, true).apply()
                        record(Remote("ftp", job.ftpDir + "|" + job.remoteName))
                    }
                }
                if (drive != null) {
                    val key = doneKey + "drive|" + job.driveSub + "/" + job.remoteName
                    if (!done.getBoolean(key, false)) {
                        begin(job.remoteName, "Drive")
                        val fileId = drive.upload(job.driveSub, job.remoteName, job.size, job.open, ::step)
                        done.edit().putBoolean(key, true).apply()
                        record(Remote("drive", fileId))
                    }
                }
            }
            if (id != null && UploadHistory.isCancelled(id)) throw UploadCancelled()
            // Only once every version is safely on every destination.
            if (settings.deleteAfterUpload) {
                val removed = runCatching { applicationContext.contentResolver.delete(uri, null, null) }.getOrDefault(0)
                Log.i("CamLinkUSB", "Deleted $name from phone after upload: $removed")
            }
            forget(done, doneKey)
            mark { it.copy(status = ItemStatus.DONE, progress = 1f, dest = null, error = null) }
            Uploads.finished(ok = true, error = null, final = true)
            Result.success()
        } catch (e: Exception) {
            if (id != null && (e is UploadCancelled || UploadHistory.isCancelled(id))) {
                // Deleted while uploading: remove whatever already reached a server, then the photo.
                forget(done, doneKey)
                UploadHistory.purge(id, sent)
                Uploads.finished(ok = false, error = null, final = true)
                return@withContext Result.success()
            }
            Log.w("CamLinkUSB", "Upload of $name failed", e)
            val final = runAttemptCount >= 5
            Uploads.finished(ok = false, error = e.message, final = final)
            mark { it.copy(status = if (final) ItemStatus.FAILED else ItemStatus.QUEUED, dest = null, progress = 0f,
                error = (e.message ?: e.toString()) + if (final) "" else " · retrying") }
            if (final) forget(done, doneKey)
            // A file that keeps failing must not block the rest of the queue: report it and move on.
            if (final) Result.success() else Result.retry()
        } finally {
            edited?.delete()
        }
    }

    private fun forget(prefs: SharedPreferences, prefix: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { e.remove(it) }
        e.apply()
    }
}

/** Uploads into the chosen Drive folder (or a subfolder of it), refreshing the token once if Google rejects it. */
private class DriveTarget(val context: Context, val s: FtpSettings) {
    private var token = DriveAuth.tokenBlocking(context, s.driveAccount)
    private val subIds = HashMap<String, String>()

    private fun <T> call(block: (String) -> T): T = try { block(token) } catch (e: DriveApi.Unauthorized) {
        DriveAuth.invalidate(context, token)
        token = DriveAuth.tokenBlocking(context, s.driveAccount)
        block(token)
    }

    fun upload(sub: String, name: String, size: Long, open: () -> InputStream?, step: (Float) -> Unit): String {
        // Subfolders are found by name in Drive each run, so a reopened or reinstalled app reuses them.
        val parent = if (sub.isEmpty()) s.driveFolderId
            else subIds.getOrPut(sub) { call { DriveApi.ensureFolder(it, s.driveFolderId, sub) } }
        // Compare with what's already in the folder: the same name and size is the same photo, so don't upload it again.
        val existing = call { DriveApi.findFiles(it, parent, name) }
        existing.firstOrNull { it.size == size }?.let { step(1f); return it.id }
        val id = call { t ->
            (open() ?: throw IOException("Photo no longer exists on the phone")).use { input ->
                DriveApi.upload(t, parent, name, DriveApi.mimeFor(name), size, input) { b -> step(b.toFloat() / size) }
            }
        }
        // Same name but different content (e.g. re-edited): replace it, like FTP overwrites. The old one goes to the trash.
        existing.forEach { old -> runCatching { call { DriveApi.trash(it, old.id) } } }
        return id
    }
}

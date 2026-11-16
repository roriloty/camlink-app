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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One upload in progress: what it is sending ("DSC01041.JPG → Drive") and how far it is. */
data class ActiveUpload(val label: String, val progress: Float)

/** Small upload summary for the status bars. Editing and several uploads can run at the same time. */
data class UploadState(
    /** Photos waiting for or in upload (edited or not yet). */
    val pending: Int = 0,
    /** Photo being auto-edited right now, if any. */
    val editingName: String? = null,
    /** Uploads running right now, by photo. */
    val active: Map<String, ActiveUpload> = emptyMap(),
    val uploaded: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val uploading get() = active.size
    /** Average progress (0..1) of the running uploads. */
    val progress get() = if (active.isEmpty()) 0f else active.values.map { it.progress }.average().toFloat()

    /** One line for the Upload only screen. */
    fun describe(): String {
        val parts = ArrayList<String>()
        when (active.size) {
            0 -> {}
            1 -> active.values.first().let { parts += "Uploading ${it.label} ${(it.progress * 100).toInt()}%" }
            else -> parts += "Uploading ${active.size} photos · ${(progress * 100).toInt()}%"
        }
        editingName?.let { parts += "editing $it" }
        val waiting = (pending - active.size - if (editingName != null) 1 else 0).coerceAtLeast(0)
        if (waiting > 0) parts += "$waiting waiting"
        return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

object Uploads {
    /** Uploads that run at the same time (each photo is one job; FTP and Drive of one photo go one after another). */
    const val MAX_PARALLEL = 3

    private val _state = MutableStateFlow(UploadState())
    val state: StateFlow<UploadState> = _state.asStateFlow()
    internal val slots = Semaphore(MAX_PARALLEL)

    /**
     * Queues [uri] for upload (if FTP and/or Drive is on and the file type is wanted) and/or for saving an
     * edited copy on the phone. Photos that need auto-editing go through the edit queue first (one at a time);
     * uploads then run in parallel. Survives app restarts.
     */
    fun enqueue(context: Context, uri: Uri, name: String, size: Long, existingId: String? = null) {
        val s = FtpSettingsStore.load(context)
        val upload = s.anyDestination && s.wants(name)
        val local = s.saveEditedLocally && isJpeg(name)
        if (!upload && !local) return
        val id = if (upload) existingId ?: UploadHistory.add(context, uri, name, size) else null
        if (upload) _state.update { it.copy(pending = it.pending + 1) }
        val needsEdit = isJpeg(name) && ((upload && s.autoEdit) || local)
        if (needsEdit) {
            val req = OneTimeWorkRequestBuilder<EditWorker>()
                .setInputData(workDataOf("uri" to uri.toString(), "name" to name, "size" to size, "id" to id, "upload" to upload))
                .addTag(TAG)
                .build()
            // Edits one at a time, in shooting order (each holds a full-size image in memory). No network needed.
            WorkManager.getInstance(context).enqueueUniqueWork("edit-queue", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        } else {
            enqueueUpload(context, uri, name, size, id!!, edited = null)
        }
    }

    /** One upload job per photo, so several can run at once (limited by [MAX_PARALLEL]). */
    internal fun enqueueUpload(context: Context, uri: Uri, name: String, size: Long, id: String, edited: String?) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf("uri" to uri.toString(), "name" to name, "size" to size, "id" to id,
                "edited" to (edited ?: ""), "stage" to "upload"))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$id", ExistingWorkPolicy.KEEP, req)
    }

    fun isJpeg(name: String) = name.endsWith(".jpg", true) || name.endsWith(".jpeg", true)

    internal fun editingStarted(name: String) = _state.update { it.copy(editingName = name) }
    internal fun editingDone() = _state.update { it.copy(editingName = null) }
    internal fun started(key: String, label: String) = _state.update { it.copy(active = it.active + (key to ActiveUpload(label, 0f))) }
    internal fun progress(key: String, p: Float) = _state.update { st ->
        val a = st.active[key] ?: return@update st
        st.copy(active = st.active + (key to a.copy(progress = p)))
    }
    internal fun ended(key: String) = _state.update { it.copy(active = it.active - key) }
    internal fun finished(ok: Boolean, error: String?, final: Boolean) = _state.update {
        it.copy(
            pending = if (ok || final) (it.pending - 1).coerceAtLeast(0) else it.pending,
            uploaded = it.uploaded + if (ok) 1 else 0,
            failed = it.failed + if (!ok && final) 1 else 0,
            lastError = if (ok) it.lastError else error,
        )
    }

    const val TAG = "ftp-upload"
}

/** Auto-edit step shared by the edit queue (and by upload jobs queued by older versions). */
internal object EditStep {
    fun pendingFile(context: Context, key: String, name: String) =
        File(context.filesDir, "pending/$key." + name.substringAfterLast('.', "jpg"))

    /**
     * Edits [uri] into [out]; saves the edited copy on the phone if wanted and not done yet; refreshes the
     * gallery thumbnail. Returns false if editing failed (the original is then uploaded).
     */
    fun run(context: Context, s: FtpSettings, uri: Uri, name: String, id: String?, forUpload: Boolean, out: File): Boolean {
        val saveLocal = s.saveEditedLocally && Uploads.isJpeg(name)
        Uploads.editingStarted(name)
        id?.let { UploadHistory.change(it) { i -> i.copy(status = ItemStatus.EDITING, error = null) } }
        try {
            out.parentFile?.mkdirs()
            if (!app.camlink.usb.edit.AutoEditor.edit(context, uri, out)) return false
            if (forUpload && s.autoEdit) id?.let { UploadHistory.thumbFromEdited(context, it, out) }
            val done = context.getSharedPreferences("upload_done", Context.MODE_PRIVATE)
            val localKey = "$uri|local"
            if (saveLocal && !done.getBoolean(localKey, false)) {
                val localName = name.substringBeforeLast('.') + "_edited." + name.substringAfterLast('.', "JPG")
                app.camlink.usb.PhotoSaver.saveFile(context, localName, out, "Edited")
                done.edit().putBoolean(localKey, true).apply()
            }
            return true
        } finally {
            Uploads.editingDone()
        }
    }
}

/** Edit queue: auto-edits one photo at a time, saves the edited copy on the phone if wanted, then hands it to an upload job. */
class EditWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        val name = inputData.getString("name") ?: "photo"
        val size = inputData.getLong("size", 0L)
        val id = inputData.getString("id")
        val upload = inputData.getBoolean("upload", false) && id != null
        val s = FtpSettingsStore.load(applicationContext)
        UploadHistory.init(applicationContext)
        if (id != null && UploadHistory.isCancelled(id)) {
            Uploads.finished(ok = false, error = null, final = true)
            return@withContext Result.success() // deleted by the user while waiting
        }
        val out = EditStep.pendingFile(applicationContext, id ?: UUID.randomUUID().toString(), name)
        val ok = runCatching { EditStep.run(applicationContext, s, uri, name, id, upload, out) }
            .onFailure { Log.w("CamLinkUSB", "Edit of $name failed", it) }.getOrDefault(false)
        if (upload) {
            id!!.let { UploadHistory.change(it) { i -> i.copy(status = ItemStatus.QUEUED) } }
            Uploads.enqueueUpload(applicationContext, uri, name, size, id, if (ok) out.path else null)
        } else {
            out.delete()
            applicationContext.getSharedPreferences("upload_done", Context.MODE_PRIVATE).edit().remove("$uri|local").apply()
        }
        Result.success()
    }
}

/** Upload job for one photo: its versions to FTP and/or Drive. Several of these run at the same time. */
class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uri = Uri.parse(inputData.getString("uri") ?: return@withContext Result.failure())
        val name = inputData.getString("name") ?: "photo"
        val size = inputData.getLong("size", 0L).coerceAtLeast(1L)
        val settings = FtpSettingsStore.load(applicationContext)
        UploadHistory.init(applicationContext)
        val id = inputData.getString("id")
        // Jobs queued by 1.13 and older did the edit themselves ("stage" missing).
        val legacy = inputData.getString("stage") == null
        val counted = inputData.getBoolean("upload", true)
        val key = id ?: uri.toString()
        if (id != null && UploadHistory.isCancelled(id)) {
            dropEdited()
            Uploads.finished(ok = false, error = null, final = true)
            return@withContext Result.success() // deleted by the user while waiting
        }
        if (!counted || !settings.anyDestination) {
            if (legacy && settings.saveEditedLocally && Uploads.isJpeg(name)) {
                val tmp = EditStep.pendingFile(applicationContext, key, name)
                runCatching { EditStep.run(applicationContext, settings, uri, name, null, false, tmp) }
                tmp.delete()
            }
            if (counted) {
                id?.let { UploadHistory.change(it) { i -> i.copy(status = ItemStatus.FAILED, error = "Upload is switched off") } }
                Uploads.finished(ok = false, error = null, final = true)
            }
            dropEdited()
            return@withContext Result.success()
        }
        // Pieces already delivered by an earlier attempt, so a retry doesn't make duplicates on Drive.
        val done = applicationContext.getSharedPreferences("upload_done", Context.MODE_PRIVATE)
        val doneKey = "$uri|"
        val sent = ArrayList<Remote>()
        var final = false
        fun mark(f: (UploadItem) -> UploadItem) { id?.let { UploadHistory.change(it, f = f) } }
        fun step(p: Float) { Uploads.progress(key, p); id?.let { UploadHistory.progress(it, p) } }
        fun begin(label: String, dest: String) {
            if (id != null && UploadHistory.isCancelled(id)) throw UploadCancelled()
            Uploads.started(key, "$label → $dest")
            mark { it.copy(status = ItemStatus.UPLOADING, dest = dest, progress = 0f, error = null) }
        }
        fun record(r: Remote) { sent += r; mark { it.copy(remotes = (it.remotes + r).distinct()) } }
        try {
            // The edited copy: made by the edit queue, or (old queued jobs) right here.
            var edited: File? = inputData.getString("edited")?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.exists() }
            if (legacy && edited == null && settings.autoEdit && Uploads.isJpeg(name)) {
                val tmp = EditStep.pendingFile(applicationContext, key, name)
                if (EditStep.run(applicationContext, settings, uri, name, id, true, tmp)) edited = tmp
            }

            // What goes where: the edited copy and/or the original, each to its folder.
            class Job(val ftpDir: String, val driveSub: String, val remoteName: String, val size: Long, val open: () -> InputStream?)
            val jobs = ArrayList<Job>()
            // The edited copy is uploaded only with "Auto-edit before upload" (it may exist just for the phone).
            val file = if (settings.autoEdit) edited else null
            val sendOriginal = file == null || settings.uploadOriginalToo
            if (file != null) {
                val sameFolder = sendOriginal && settings.editedSub() == settings.originalSub()
                val remote = if (sameFolder) name.substringBeforeLast('.') + "_edited." + name.substringAfterLast('.', "JPG") else name
                jobs += Job(settings.editedDir(), settings.editedSub(), remote, file.length().coerceAtLeast(1L)) { file.inputStream() }
            }
            if (sendOriginal) {
                jobs += Job(settings.originalDir(), settings.originalSub(), name, size) { applicationContext.contentResolver.openInputStream(uri) }
            }

            // Wait for a free upload slot ("Waiting" in the gallery meanwhile).
            Uploads.slots.withPermit {
                val drive = if (settings.driveReady) DriveTarget(applicationContext, settings) else null
                for (job in jobs) {
                    if (settings.isComplete) {
                        val k = doneKey + "ftp|" + job.ftpDir + "/" + job.remoteName
                        if (!done.getBoolean(k, false)) {
                            begin(job.remoteName, "FTP")
                            FtpUploader.upload(settings, job.ftpDir, job.remoteName, job.size, { b -> step(b.toFloat() / job.size) }, job.open)
                            done.edit().putBoolean(k, true).apply()
                            record(Remote("ftp", job.ftpDir + "|" + job.remoteName))
                        }
                    }
                    if (drive != null) {
                        val k = doneKey + "drive|" + job.driveSub + "/" + job.remoteName
                        if (!done.getBoolean(k, false)) {
                            begin(job.remoteName, "Drive")
                            val fileId = drive.upload(job.driveSub, job.remoteName, job.size, job.open, ::step)
                            done.edit().putBoolean(k, true).apply()
                            record(Remote("drive", fileId))
                        }
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
            final = true
            mark { it.copy(status = ItemStatus.DONE, progress = 1f, dest = null, error = null) }
            Uploads.finished(ok = true, error = null, final = true)
            Result.success()
        } catch (e: Exception) {
            if (id != null && (e is UploadCancelled || UploadHistory.isCancelled(id))) {
                // Deleted while uploading: remove whatever already reached a server, then the photo.
                forget(done, doneKey)
                final = true
                UploadHistory.purge(id, sent)
                Uploads.finished(ok = false, error = null, final = true)
                return@withContext Result.success()
            }
            Log.w("CamLinkUSB", "Upload of $name failed", e)
            final = runAttemptCount >= 5
            Uploads.finished(ok = false, error = e.message, final = final)
            mark { it.copy(status = if (final) ItemStatus.FAILED else ItemStatus.QUEUED, dest = null, progress = 0f,
                error = (e.message ?: e.toString()) + if (final) "" else " · retrying") }
            if (final) forget(done, doneKey)
            // A photo that keeps failing must not hold anything up: report it and move on.
            if (final) Result.success() else Result.retry()
        } finally {
            Uploads.ended(key)
            // Keep the edited copy for a retry; drop it once this photo is finished either way.
            if (final) dropEdited()
        }
    }

    private fun dropEdited() {
        inputData.getString("edited")?.takeIf { it.isNotEmpty() }?.let { File(it).delete() }
        val id = inputData.getString("id")
        val name = inputData.getString("name") ?: return
        EditStep.pendingFile(applicationContext, id ?: inputData.getString("uri") ?: return, name).delete()
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

    private fun <T> call(block: (String) -> T): T = try { block(token) } catch (e: DriveApi.Unauthorized) {
        DriveAuth.invalidate(context, token)
        token = DriveAuth.tokenBlocking(context, s.driveAccount)
        block(token)
    }

    fun upload(sub: String, name: String, size: Long, open: () -> InputStream?, step: (Float) -> Unit): String {
        // Subfolders are found by name in Drive (so a reopened or reinstalled app reuses them). Parallel uploads
        // share one lookup, so two of them can't both create "Edited".
        val parent = if (sub.isEmpty()) s.driveFolderId else synchronized(folderLock) {
            folderIds.getOrPut(s.driveFolderId + "/" + sub) { call { DriveApi.ensureFolder(it, s.driveFolderId, sub) } }
        }
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

    companion object {
        private val folderLock = Any()
        private val folderIds = HashMap<String, String>()
    }
}

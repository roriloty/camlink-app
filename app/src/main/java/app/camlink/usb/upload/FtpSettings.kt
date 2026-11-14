package app.camlink.usb.upload

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class UploadFilter { ALL, JPEG_ONLY, RAW_ONLY }

/** All upload settings: FTP and Google Drive destinations, plus what gets uploaded and where. */
data class FtpSettings(
    /** FTP destination on. */
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 21,
    val username: String = "",
    val password: String = "",
    /** Remote folder, created if missing (e.g. "/photos/shoot"). Empty = login folder. */
    val directory: String = "",
    val passive: Boolean = true,
    /** Explicit FTPS (AUTH TLS) instead of plain FTP. */
    val tls: Boolean = false,
    val filter: UploadFilter = UploadFilter.ALL,
    /** Edit JPEGs in the user's Lightroom style before uploading (originals on the phone stay untouched). */
    val autoEdit: Boolean = false,
    /** Also auto-edit every JPEG and save the edited copy on the phone (Pictures/CamLink/Edited), upload or not. */
    val saveEditedLocally: Boolean = false,
    /** With auto-edit on: also upload the unedited original. */
    val uploadOriginalToo: Boolean = false,
    /** Put originals into a subfolder of [directory]. */
    val originalSubfolder: Boolean = false,
    val originalFolderName: String = "Original",
    /** Put edited copies into a subfolder of [directory]. */
    val editedSubfolder: Boolean = false,
    val editedFolderName: String = "Edited",
    /** Delete the photo from the phone once every version of it has uploaded successfully. */
    val deleteAfterUpload: Boolean = false,
    /** Google Drive destination. */
    val driveEnabled: Boolean = false,
    val driveAccount: String = "",
    val driveFolderId: String = "",
    /** Human-readable path of the chosen folder, e.g. "Shared drives / Studio / 2026". */
    val driveFolderPath: String = "",
) {
    /** FTP is on and usable. */
    val isComplete get() = enabled && host.isNotBlank()
    val driveReady get() = driveEnabled && driveAccount.isNotBlank() && driveFolderId.isNotBlank()
    val anyDestination get() = isComplete || driveReady

    /** Subfolder names (empty = none) used for Drive, where folders are addressed by id. */
    fun originalSub() = if (originalSubfolder) originalFolderName.trim().trim('/').ifBlank { "Original" } else ""
    fun editedSub() = if (editedSubfolder) editedFolderName.trim().trim('/').ifBlank { "Edited" } else ""

    /** Remote folder for originals / edited copies (main folder, or a subfolder of it). */
    fun originalDir() = if (originalSubfolder) join(directory, originalFolderName.ifBlank { "Original" }) else directory
    fun editedDir() = if (editedSubfolder) join(directory, editedFolderName.ifBlank { "Edited" }) else directory

    private fun join(base: String, sub: String): String {
        val b = base.trim().trimEnd('/'); val s = sub.trim().trim('/')
        return if (b.isEmpty()) s else "$b/$s"
    }

    fun wants(fileName: String): Boolean {
        val jpeg = fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true)
        return when (filter) {
            UploadFilter.ALL -> true
            UploadFilter.JPEG_ONLY -> jpeg
            UploadFilter.RAW_ONLY -> !jpeg
        }
    }
}

/** Persists the FTP settings in the app's private preferences; readable from the UI and the upload worker. */
object FtpSettingsStore {
    private const val PREFS = "ftp_upload"
    private val state = MutableStateFlow<FtpSettings?>(null)

    fun flow(context: Context): StateFlow<FtpSettings?> { load(context); return state.asStateFlow() }

    fun load(context: Context): FtpSettings {
        state.value?.let { return it }
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = FtpSettings(
            enabled = p.getBoolean("enabled", false),
            host = p.getString("host", "") ?: "",
            port = p.getInt("port", 21),
            username = p.getString("username", "") ?: "",
            password = p.getString("password", "") ?: "",
            directory = p.getString("directory", "") ?: "",
            passive = p.getBoolean("passive", true),
            tls = p.getBoolean("tls", false),
            filter = runCatching { UploadFilter.valueOf(p.getString("filter", "ALL")!!) }.getOrDefault(UploadFilter.ALL),
            autoEdit = p.getBoolean("autoEdit", false),
            saveEditedLocally = p.getBoolean("saveEditedLocally", false),
            uploadOriginalToo = p.getBoolean("uploadOriginalToo", false),
            originalSubfolder = p.getBoolean("originalSubfolder", false),
            originalFolderName = p.getString("originalFolderName", "Original") ?: "Original",
            editedSubfolder = p.getBoolean("editedSubfolder", false),
            editedFolderName = p.getString("editedFolderName", "Edited") ?: "Edited",
            deleteAfterUpload = p.getBoolean("deleteAfterUpload", false),
            driveEnabled = p.getBoolean("driveEnabled", false),
            driveAccount = p.getString("driveAccount", "") ?: "",
            // A folder picked with the old full-Drive permission isn't reachable with drive.file: pick again.
            driveFolderId = if (p.getBoolean("driveFileScope", false)) p.getString("driveFolderId", "") ?: "" else "",
            driveFolderPath = if (p.getBoolean("driveFileScope", false)) p.getString("driveFolderPath", "") ?: "" else "",
        )
        state.value = s
        return s
    }

    fun save(context: Context, s: FtpSettings) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putString("host", s.host.trim())
            .putInt("port", s.port)
            .putString("username", s.username)
            .putString("password", s.password)
            .putString("directory", s.directory.trim())
            .putBoolean("passive", s.passive)
            .putBoolean("tls", s.tls)
            .putString("filter", s.filter.name)
            .putBoolean("autoEdit", s.autoEdit)
            .putBoolean("saveEditedLocally", s.saveEditedLocally)
            .putBoolean("uploadOriginalToo", s.uploadOriginalToo)
            .putBoolean("originalSubfolder", s.originalSubfolder)
            .putString("originalFolderName", s.originalFolderName.trim())
            .putBoolean("editedSubfolder", s.editedSubfolder)
            .putString("editedFolderName", s.editedFolderName.trim())
            .putBoolean("deleteAfterUpload", s.deleteAfterUpload)
            .putBoolean("driveEnabled", s.driveEnabled)
            .putString("driveAccount", s.driveAccount)
            .putString("driveFolderId", s.driveFolderId)
            .putString("driveFolderPath", s.driveFolderPath)
            .putBoolean("driveFileScope", true)
            .apply()
        state.value = s.copy(host = s.host.trim(), directory = s.directory.trim())
    }
}

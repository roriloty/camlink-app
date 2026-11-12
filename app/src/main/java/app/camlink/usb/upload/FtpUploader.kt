package app.camlink.usb.upload

import android.content.Context
import android.net.Uri
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import org.apache.commons.net.io.CopyStreamAdapter
import java.io.IOException
import java.io.InputStream

/** Plain FTP / explicit FTPS upload of one file, with byte progress. */
object FtpUploader {

    private fun connect(s: FtpSettings): FTPClient {
        val ftp: FTPClient = if (s.tls) FTPSClient("TLS", false) else FTPClient()
        ftp.connectTimeout = 15_000
        ftp.defaultTimeout = 30_000
        ftp.controlEncoding = "UTF-8"
        ftp.connect(s.host, s.port)
        if (!FTPReply.isPositiveCompletion(ftp.replyCode)) {
            runCatching { ftp.disconnect() }
            throw IOException("Server refused connection (${ftp.replyCode})")
        }
        if (ftp is FTPSClient) { ftp.execPBSZ(0); ftp.execPROT("P") }
        val user = s.username.ifBlank { "anonymous" }
        if (!ftp.login(user, s.password)) {
            val reply = ftp.replyString?.trim()
            runCatching { ftp.disconnect() }
            throw IOException("Login failed${reply?.let { ": $it" } ?: ""}")
        }
        if (s.passive) ftp.enterLocalPassiveMode() else ftp.enterLocalActiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        ftp.bufferSize = 256 * 1024
        return ftp
    }

    /** Changes into [dir], creating each missing level. */
    private fun enterDirectory(ftp: FTPClient, dir: String) {
        if (dir.isBlank()) return
        if (dir.startsWith("/")) ftp.changeWorkingDirectory("/")
        for (part in dir.split('/').filter { it.isNotBlank() }) {
            if (!ftp.changeWorkingDirectory(part)) {
                if (!ftp.makeDirectory(part) || !ftp.changeWorkingDirectory(part))
                    throw IOException("Can't open or create folder \"$part\" (${ftp.replyString?.trim()})")
            }
        }
    }

    /** Connects, logs in and opens the folder; returns a short summary for the "Test" button. */
    fun test(s: FtpSettings): String {
        val ftp = connect(s)
        try {
            enterDirectory(ftp, s.directory)
            return "Connected to ${s.host} · folder ${ftp.printWorkingDirectory()}"
        } finally {
            runCatching { ftp.logout() }; runCatching { ftp.disconnect() }
        }
    }

    /** Deletes files given as (folder, name) pairs; a file that is already gone counts as deleted. */
    fun delete(s: FtpSettings, files: List<Pair<String, String>>) {
        val ftp = connect(s)
        try {
            val home = ftp.printWorkingDirectory() ?: "/"
            for ((dir, name) in files) {
                ftp.changeWorkingDirectory(home)
                if (dir.isNotBlank() && !ftp.changeWorkingDirectory(dir)) continue // folder gone: nothing to delete
                if (!ftp.deleteFile(name) && ftp.listNames(name)?.isNotEmpty() == true)
                    throw IOException("FTP refused to delete $name (${ftp.replyString?.trim()})")
                ftp.deleteFile("$name.part")
            }
        } finally {
            runCatching { ftp.logout() }; runCatching { ftp.disconnect() }
        }
    }

    /** Uploads whatever [open] returns (an edited copy, or the saved original) into [dir] as [name]. */
    fun upload(s: FtpSettings, dir: String, name: String, total: Long, progress: (Long) -> Unit, open: () -> InputStream?) {
        val ftp = connect(s)
        try {
            enterDirectory(ftp, dir)
            ftp.copyStreamListener = object : CopyStreamAdapter() {
                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    progress(totalBytesTransferred)
                }
            }
            val ok = open()?.use { input ->
                // Upload under a temporary name, then rename, so watchers never see a half-written file.
                val tmp = "$name.part"
                ftp.storeFile(tmp, input) && (ftp.rename(tmp, name) || run { ftp.deleteFile(name); ftp.rename(tmp, name) })
            } ?: throw IOException("Photo no longer exists on the phone")
            if (!ok) throw IOException("Upload failed: ${ftp.replyString?.trim()}")
            progress(total)
        } finally {
            runCatching { ftp.logout() }; runCatching { ftp.disconnect() }
        }
    }
}

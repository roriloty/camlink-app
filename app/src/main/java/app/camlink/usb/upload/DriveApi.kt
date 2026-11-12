package app.camlink.usb.upload

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** The few Google Drive v3 REST calls the app needs, over plain HTTPS (no Google API client library). */
object DriveApi {
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    const val FOLDER = "application/vnd.google-apps.folder"

    class Folder(val id: String, val name: String, val canAdd: Boolean)

    /** Thrown for HTTP 401 so callers can refresh the token once. */
    class Unauthorized : IOException("Google rejected the sign-in")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun quote(s: String) = s.replace("\\", "\\\\").replace("'", "\\'")

    private fun open(token: String, url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000; readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun HttpURLConnection.readJson(): JSONObject {
        val code = responseCode
        if (code == 401) { disconnect(); throw Unauthorized() }
        if (code !in 200..299) {
            val err = errorStream?.bufferedReader()?.readText().orEmpty()
            val msg = runCatching { JSONObject(err).getJSONObject("error").getString("message") }.getOrNull()
            disconnect()
            throw IOException("Google Drive: ${msg ?: "HTTP $code"}")
        }
        return inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { disconnect() }
    }

    private fun get(token: String, url: String) = open(token, url).readJson()

    private fun postJson(token: String, url: String, body: JSONObject): JSONObject = open(token, url, "POST").run {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        outputStream.use { it.write(body.toString().toByteArray()) }
        readJson()
    }

    /** E-mail of the signed-in account. */
    fun accountEmail(token: String): String =
        get(token, "$API/about?fields=user(emailAddress)").getJSONObject("user").getString("emailAddress")

    private fun listFiles(token: String, q: String): List<Folder> {
        val out = ArrayList<Folder>()
        var page: String? = null
        do {
            val url = "$API/files?q=${enc(q)}&orderBy=name&pageSize=1000&corpora=allDrives" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                "&fields=${enc("nextPageToken,files(id,name,capabilities/canAddChildren)")}" +
                (page?.let { "&pageToken=${enc(it)}" } ?: "")
            val j = get(token, url)
            val files = j.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += Folder(f.getString("id"), f.getString("name"),
                    f.optJSONObject("capabilities")?.optBoolean("canAddChildren", true) ?: true)
            }
            page = j.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (page != null && out.size < 5000)
        return out
    }

    /** Sub-folders of [parentId] ("root" = My Drive). */
    fun folders(token: String, parentId: String) =
        listFiles(token, "'${quote(parentId)}' in parents and mimeType='$FOLDER' and trashed=false")

    /** Top-level folders other people shared with this account. */
    fun sharedWithMe(token: String) = listFiles(token, "sharedWithMe and mimeType='$FOLDER' and trashed=false")

    /** Shared drives the account is a member of (their id is also their root folder id). */
    fun sharedDrives(token: String): List<Folder> {
        val j = get(token, "$API/drives?pageSize=100&fields=${enc("drives(id,name,capabilities/canAddChildren)")}")
        val d = j.optJSONArray("drives") ?: JSONArray()
        return (0 until d.length()).map { d.getJSONObject(it) }.map {
            Folder(it.getString("id"), it.getString("name"),
                it.optJSONObject("capabilities")?.optBoolean("canAddChildren", true) ?: true)
        }
    }

    fun createFolder(token: String, parentId: String, name: String): String =
        postJson(token, "$API/files?supportsAllDrives=true&fields=id",
            JSONObject().put("name", name).put("mimeType", FOLDER).put("parents", JSONArray().put(parentId)))
            .getString("id")

    /** Id of the folder [name] inside [parentId], creating it if needed. */
    fun ensureFolder(token: String, parentId: String, name: String): String =
        listFiles(token, "'${quote(parentId)}' in parents and name='${quote(name)}' and mimeType='$FOLDER' and trashed=false")
            .firstOrNull()?.id ?: createFolder(token, parentId, name)

    /** Resumable upload of [size] bytes from [input] as [name] into [parentId]. */
    /** Moves a file to Drive's trash (recoverable for 30 days). A file that's already gone counts as done. */
    fun trash(token: String, id: String) {
        val c = open(token, "$API/files/${enc(id)}?supportsAllDrives=true&fields=id", "POST").apply {
            doOutput = true
            setRequestProperty("X-HTTP-Method-Override", "PATCH")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            outputStream.use { it.write("{\"trashed\":true}".toByteArray()) }
        }
        if (c.responseCode == 404) { c.disconnect(); return }
        c.readJson()
    }

    /** Returns the new file's id. */
    fun upload(token: String, parentId: String, name: String, mime: String, size: Long, input: InputStream, progress: (Long) -> Unit): String {
        val start = open(token, "$UPLOAD?uploadType=resumable&supportsAllDrives=true&fields=id", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", mime)
            setRequestProperty("X-Upload-Content-Length", size.toString())
            outputStream.use { it.write(JSONObject().put("name", name).put("parents", JSONArray().put(parentId)).toString().toByteArray()) }
        }
        val location = start.run {
            if (responseCode == 401) { disconnect(); throw Unauthorized() }
            val loc = getHeaderField("Location")
            if (responseCode !in 200..299 || loc == null) readJson() // throws with Google's message
            disconnect(); loc!!
        }
        val put = (URL(location).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"; doOutput = true
            connectTimeout = 20_000; readTimeout = 120_000
            setRequestProperty("Content-Type", mime)
            setFixedLengthStreamingMode(size)
        }
        put.outputStream.use { out ->
            val buf = ByteArray(256 * 1024)
            var sent = 0L
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); sent += n; progress(sent)
            }
        }
        return put.readJson().getString("id")
    }

    fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "arw" -> "image/x-sony-arw"
        "hif", "heif", "heic" -> "image/heif"
        else -> "application/octet-stream"
    }
}

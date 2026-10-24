package com.najimwifi.app.backup

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A versioned, encrypted backup in the invisible Drive appDataFolder. */
data class DriveBackupVersion(
    val id: String,
    val name: String,
    val size: Long,
    val createdTime: String
)

class DriveBackupClient(private val context: Context) {
    companion object {
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        private const val MAX_BYTES = BackupArchiveFormat.MAX_DATABASE_BYTES + 32L * 1024L * 1024L
        private val namePattern = Regex("Najim_[0-9]{8}_[0-9]{6}_[a-f0-9]{8}\\.njbak")
        private val idPattern = Regex("[A-Za-z0-9_-]{5,256}")
    }

    private fun connection(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun response(conn: HttpURLConnection, expected: Set<Int>): JSONObject {
        val code = conn.responseCode
        if (code !in expected) {
            val message = conn.errorStream?.bufferedReader()?.use { it.readText().take(320) }
            throw IOException("Drive HTTP $code: ${message ?: conn.responseMessage}")
        }
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    }

    suspend fun list(token: String): List<DriveBackupVersion> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val all = mutableListOf<DriveBackupVersion>()
            var next: String? = null
            var pages = 0
            do {
                val query = "spaces=appDataFolder&pageSize=100&fields=" +
                    URLEncoder.encode("nextPageToken,files(id,name,size,createdTime,mimeType)", "UTF-8") +
                    (next?.let { "&pageToken=${URLEncoder.encode(it, "UTF-8")}" } ?: "")
                val conn = connection("$API?$query", token, "GET")
                val obj = try { response(conn, setOf(200)) } finally { conn.disconnect() }
                val files = obj.optJSONArray("files")
                if (files != null) for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    val name = file.optString("name")
                    val id = file.optString("id")
                    if (namePattern.matches(name) && idPattern.matches(id)) {
                        all += DriveBackupVersion(id, name, file.optLong("size"), file.optString("createdTime"))
                    }
                }
                next = obj.optString("nextPageToken").takeIf { it.isNotBlank() }
                pages++
                require(pages <= 100) { "قائمة Drive كبيرة بصورة غير متوقعة" }
            } while (next != null)
            all.sortedByDescending { it.createdTime }
        }

    /** Resumable HTTPS upload streams the encrypted archive, not plaintext SQLite. */
    suspend fun upload(token: String, encrypted: File, filename: String): DriveBackupVersion =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(namePattern.matches(filename) && encrypted.isFile)
            require(encrypted.length() in 1..MAX_BYTES)
            val metadata = JSONObject().apply {
                put("name", filename)
                put("parents", org.json.JSONArray().put("appDataFolder"))
                put("mimeType", "application/octet-stream")
            }.toString().toByteArray(Charsets.UTF_8)
            val init = connection("$UPLOAD?uploadType=resumable&fields=id,name,size,createdTime", token, "POST")
            val session: String
            try {
                init.doOutput = true
                init.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                init.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
                init.setRequestProperty("X-Upload-Content-Length", encrypted.length().toString())
                init.setFixedLengthStreamingMode(metadata.size)
                init.outputStream.use { it.write(metadata) }
                require(init.responseCode in 200..201) {
                    "تعذر بدء رفع Drive: HTTP ${init.responseCode}"
                }
                session = requireNotNull(init.getHeaderField("Location")) { "Drive لم يرد بجلسة رفع" }
                val verified = Uri.parse(session)
                require(verified.scheme == "https" && verified.host == "www.googleapis.com" &&
                    verified.path?.startsWith("/upload/drive/v3/files") == true) {
                    "عنوان جلسة رفع Drive غير موثوق"
                }
            } finally { init.disconnect() }
            val send = connection(session, token, "PUT")
            try {
                send.doOutput = true
                send.setRequestProperty("Content-Type", "application/octet-stream")
                send.setFixedLengthStreamingMode(encrypted.length())
                encrypted.inputStream().use { input ->
                    send.outputStream.use { output -> input.copyTo(output, 64 * 1024) }
                }
                val item = response(send, setOf(200, 201))
                val id = item.getString("id")
                require(idPattern.matches(id))
                DriveBackupVersion(id, filename, encrypted.length(), item.optString("createdTime"))
            } finally { send.disconnect() }
        }

    /** Prune only app-owned, successfully uploaded archive versions. Keep >= 2. */
    suspend fun prune(token: String, keep: Int): Int =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(keep in 2..20) { "عدد النسخ المحفوظة غير صحيح" }
            val all = list(token)
            val obsolete = all.drop(keep)
            obsolete.forEach { version ->
                require(idPattern.matches(version.id) && namePattern.matches(version.name))
                val conn = connection("$API/${version.id}", token, "DELETE")
                try {
                    require(conn.responseCode == 204) {
                        "تعذر تنظيف الإصدار ${version.name}: HTTP ${conn.responseCode}"
                    }
                } finally { conn.disconnect() }
            }
            obsolete.size
        }

    /** Limit remote response size, and never trust the remote filename or path. */
    suspend fun download(token: String, backup: DriveBackupVersion): File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            require(idPattern.matches(backup.id) && namePattern.matches(backup.name))
            val dir = File(context.cacheDir, "drive_backups").apply { require(isDirectory || mkdirs()) }
            val temporary = File.createTempFile("download_", ".njbak", dir)
            val conn = connection("$API/${backup.id}?alt=media", token, "GET")
            try {
                require(conn.responseCode == 200) { "Drive HTTP ${conn.responseCode}" }
                var copied = 0L
                conn.inputStream.use { source ->
                    FileOutputStream(temporary).use { out ->
                        val block = ByteArray(64 * 1024)
                        while (true) {
                            val n = source.read(block)
                            if (n < 0) break
                            copied = Math.addExact(copied, n.toLong())
                            require(copied <= MAX_BYTES) { "ملف النسخة أكبر من الحد المسموح" }
                            out.write(block, 0, n)
                        }
                        out.fd.sync()
                    }
                }
                require(copied > 0L)
                temporary
            } catch (e: Exception) {
                temporary.delete()
                throw e
            } finally { conn.disconnect() }
        }
}

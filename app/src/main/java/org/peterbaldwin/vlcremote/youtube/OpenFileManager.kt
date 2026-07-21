package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.vlcremote.model.ErrorLog
import org.peterbaldwin.vlcremote.model.HelperConfig
import org.peterbaldwin.vlcremote.net.MediaServer
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Opens a local video/audio file shared from another app on the remote VLC: the file's bytes are
 * streamed to the companion helper (POST /upload, written to a temp file on the VLC host), then
 * VLC is told to play the returned path. Only usable when the current server has a helper.
 * Progress is surfaced in the Now Playing tab like the YouTube download (see PlaybackFragment).
 */
object OpenFileManager {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var statusText: String? = null

    /** Now Playing progress line ("Uploading… N%"), or null when idle. */
    @JvmStatic
    fun statusText(): String? = statusText

    @JvmStatic
    fun isActive(): Boolean = statusText != null

    /** Streams [uri] to the current server's helper and plays it on VLC. */
    @JvmStatic
    fun open(context: Context, uri: Uri, authority: String) {
        val ctx = context.applicationContext
        val cfg = HelperConfig.resolve(ctx, authority) ?: run {
            ErrorLog.log("Open file: no usable helper for $authority")
            return
        }
        val name = displayName(ctx, uri)
        val size = fileSize(ctx, uri)
        statusText = "Uploading…"
        GlobalScope.launch(Dispatchers.IO) {
            val path = try {
                upload(ctx, uri, cfg, name, size)
            } catch (e: Exception) {
                ErrorLog.log("Open file: upload failed", e); null
            }
            withContext(Dispatchers.Main) {
                if (path == null) {
                    statusText = "Upload failed"
                    handler.postDelayed({ if (statusText == "Upload failed") statusText = null }, 5000)
                    return@withContext
                }
                MediaServer(ctx, authority).status().command.input.playWithMetaTitle(path, name)
                statusText = null
            }
        }
    }

    private fun upload(ctx: Context, uri: Uri, cfg: HelperConfig.Config, name: String, size: Long): String? {
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        val conn = URL("http://${cfg.host}:${cfg.port}/upload?name=" + URLEncoder.encode(name, "UTF-8"))
            .openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            conn.useCaches = false
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            cfg.authHeader?.let { conn.setRequestProperty("Authorization", it) }
            if (size > 0) conn.setFixedLengthStreamingMode(size) else conn.setChunkedStreamingMode(0)
            input.use { ins ->
                conn.outputStream.use { out ->
                    val buf = ByteArray(256 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        statusText = if (size > 0)
                            String.format(Locale.US, "Uploading… %d%%", (sent * 100 / size).toInt())
                        else
                            "Uploading… " + (sent / 1_000_000) + " MB"
                    }
                    out.flush()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }?.trim()
            if (code in 200..299 && !body.isNullOrEmpty() && !body.startsWith("ERROR")) body else null
        } finally {
            conn.disconnect()
        }
    }

    private fun displayName(ctx: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "video"
        var name: String? = null
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) name = c.getString(0)
            }
        }
        return name ?: uri.lastPathSegment ?: "video"
    }

    private fun fileSize(ctx: Context, uri: Uri): Long {
        if (uri.scheme == "file") return runCatching { java.io.File(uri.path!!).length() }.getOrDefault(-1)
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        }
        return size
    }
}

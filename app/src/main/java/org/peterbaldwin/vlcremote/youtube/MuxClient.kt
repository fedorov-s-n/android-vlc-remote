package org.peterbaldwin.vlcremote.youtube

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Talks to the host helper's /mux endpoints (see vlc-helper.py): start a background
 * download+remux job, poll its status, or cancel it. All calls block — use off the main thread.
 */
object MuxClient {

    data class Status(
        val state: String,           // RUNNING | DONE | ERROR | UNKNOWN | (empty on network failure)
        val bytes: Long = 0,
        val total: Long = 0,
        val ms: Long = 0,
        val path: String? = null
    ) {
        val running get() = state == "RUNNING"
        val done get() = state == "DONE"
    }

    /** @return the job id, or null on failure / helper busy. */
    fun start(host: String, port: Int, videoUrl: String, audioUrl: String): String? {
        val v = URLEncoder.encode(videoUrl, "UTF-8")
        val a = URLEncoder.encode(audioUrl, "UTF-8")
        val body = get("http://$host:$port/mux/start?v=$v&a=$a") ?: return null
        val id = body.trim()
        // Errors come back as "ERROR: ..." with a non-2xx code -> get() returns null for those.
        return if (id.isEmpty() || id.startsWith("ERROR")) null else id
    }

    fun status(host: String, port: Int, id: String): Status {
        val body = get("http://$host:$port/mux/status?id=" + URLEncoder.encode(id, "UTF-8"))
            ?: return Status("")
        val line = body.trim()
        val parts = line.split(" ", limit = 5)
        return when (parts.firstOrNull()) {
            "RUNNING", "DONE" -> Status(
                parts[0],
                parts.getOrNull(1)?.toLongOrNull() ?: 0,
                parts.getOrNull(2)?.toLongOrNull() ?: 0,
                parts.getOrNull(3)?.toLongOrNull() ?: 0,
                parts.getOrNull(4)
            )
            "ERROR" -> Status("ERROR")
            else -> Status("UNKNOWN")
        }
    }

    fun cancel(host: String, port: Int, id: String) {
        get("http://$host:$port/mux/cancel?id=" + URLEncoder.encode(id, "UTF-8"))
    }

    private fun get(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 20_000
                useCaches = false
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, "UTF-8")).readText() }
            if (code in 200..299) text else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            conn?.disconnect()
        }
    }
}

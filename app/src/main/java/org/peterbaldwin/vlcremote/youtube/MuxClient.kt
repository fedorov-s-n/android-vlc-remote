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
        val durationSec: Long = 0,
        val path: String? = null
    ) {
        val running get() = state == "RUNNING"
        val done get() = state == "DONE"
    }

    /** Starts a download+mux job. [title]/[artist]/[album] are embedded so Now Playing shows
     *  video / channel / playlist; [durationSec] is stored so status can report it on resume.
     *  @return the muxed file path (used as the job key), or null on failure / helper busy. */
    fun start(
        host: String, port: Int, videoUrl: String, audioUrl: String,
        title: String, artist: String, album: String, durationSec: Long
    ): String? {
        val v = URLEncoder.encode(videoUrl, "UTF-8")
        val a = URLEncoder.encode(audioUrl, "UTF-8")
        val t = URLEncoder.encode(title, "UTF-8")
        val c = URLEncoder.encode(artist, "UTF-8")
        val al = URLEncoder.encode(album, "UTF-8")
        val body = get("http://$host:$port/mux/start?v=$v&a=$a&t=$t&c=$c&al=$al&d=$durationSec") ?: return null
        val path = body.trim()
        // Errors come back as "ERROR: ..." with a non-2xx code -> get() returns null for those.
        return if (path.isEmpty() || path.startsWith("ERROR")) null else path
    }

    /** @param key the file path or basename (mux_<id>.ts) identifying the job. */
    fun status(host: String, port: Int, key: String): Status {
        val body = get("http://$host:$port/mux/status?path=" + URLEncoder.encode(key, "UTF-8"))
            ?: return Status("")
        val parts = body.trim().split(" ", limit = 6)
        return when (parts.firstOrNull()) {
            "RUNNING", "DONE" -> Status(
                parts[0],
                parts.getOrNull(1)?.toLongOrNull() ?: 0,
                parts.getOrNull(2)?.toLongOrNull() ?: 0,
                parts.getOrNull(3)?.toLongOrNull() ?: 0,
                parts.getOrNull(4)?.toLongOrNull() ?: 0,
                parts.getOrNull(5)
            )
            "ERROR" -> Status("ERROR")
            else -> Status("UNKNOWN")
        }
    }

    fun cancel(host: String, port: Int, key: String) {
        get("http://$host:$port/mux/cancel?path=" + URLEncoder.encode(key, "UTF-8"))
    }

    /** Which of [paths] exist on the host (same order). On failure assumes all exist (true). */
    @JvmStatic
    fun existing(host: String, port: Int, paths: List<String>): List<Boolean> {
        if (paths.isEmpty()) return emptyList()
        val query = paths.joinToString("&") { "path=" + URLEncoder.encode(it, "UTF-8") }
        val body = get("http://$host:$port/exists?$query") ?: return List(paths.size) { true }
        val lines = body.trim().split("\n")
        return paths.indices.map { lines.getOrNull(it)?.trim() == "1" }
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
            // Endpoint only (drop the query, which carries long stream URLs).
            org.peterbaldwin.vlcremote.model.ErrorLog.log("Helper request failed: " + urlStr.substringBefore('?'), e)
            null
        } finally {
            conn?.disconnect()
        }
    }
}

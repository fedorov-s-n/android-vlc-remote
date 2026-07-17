package org.peterbaldwin.vlcremote.youtube

import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HttpURLConnection-based transport for NewPipeExtractor (no extra dependency). */
class YouTubeDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 30_000
        conn.requestMethod = request.httpMethod()
        for ((name, values) in request.headers()) {
            for (value in values) {
                conn.addRequestProperty(name, value)
            }
        }
        if (conn.getRequestProperty("User-Agent") == null) {
            conn.setRequestProperty("User-Agent", USER_AGENT)
        }
        val data = request.dataToSend()
        if (data != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(data) }
        }
        try {
            val code = conn.responseCode
            if (code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val message = conn.responseMessage
            val headers = conn.headerFields
            val latestUrl = conn.url.toString()
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return Response(code, message, headers, body, latestUrl)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

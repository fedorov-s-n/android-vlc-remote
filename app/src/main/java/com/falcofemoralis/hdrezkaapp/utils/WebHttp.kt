package com.falcofemoralis.hdrezkaapp.utils

import android.webkit.CookieManager
import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

/**
 * HTTP layer for all hdrezka site calls. Requests run inside the hidden WebView
 * (see [WebViewHttp]) so they pass the mirror's Cloudflare protection with the real
 * browser session. DNS resolution is handled by the system (use system Private DNS
 * if the network blocks/hijacks the mirror).
 *
 * Exposes the small subset of the jsoup [Connection] API that the models use.
 */
object WebHttp {
    fun request(link: String?): WebRequest =
        WebRequest((link ?: "").replace(" ", "").replace("\n", ""))
}

class WebResponse(
    private val url: String,
    private val bodyString: String,
    private val setCookies: Map<String, String>
) {
    fun parse(): Document = Jsoup.parse(bodyString, url)
    fun body(): String = bodyString
    fun cookie(name: String): String? = setCookies[name]
    fun hasCookie(name: String): Boolean = setCookies.containsKey(name)
}

/**
 * Fluent request builder matching the jsoup Connection methods the models use.
 * Everything is executed through [WebViewHttp].
 */
class WebRequest(private val url: String) {
    private val headers = LinkedHashMap<String, String>()
    private val data = LinkedHashMap<String, String>()
    private var method: Connection.Method = Connection.Method.GET
    private var rawBody: String? = null

    // Kept for API compatibility with the jsoup-style call sites; not meaningful for
    // the WebView transport (UA/timeouts/redirects/cookies are the WebView's own).
    fun userAgent(ignored: String?): WebRequest = this
    fun timeout(ignored: Int): WebRequest = this
    fun ignoreContentType(ignored: Boolean): WebRequest = this
    fun ignoreHttpErrors(ignored: Boolean): WebRequest = this
    fun followRedirects(ignored: Boolean): WebRequest = this
    fun cookie(ignored: String, ignoredValue: String): WebRequest = this // sent via the WebView cookie store

    fun header(name: String, value: String?): WebRequest {
        if (value != null) headers[name] = value
        return this
    }

    fun data(values: Map<String, String>): WebRequest {
        data.putAll(values)
        return this
    }

    fun data(key: String, value: String): WebRequest {
        data[key] = value
        return this
    }

    fun requestBody(body: String): WebRequest {
        rawBody = body
        return this
    }

    fun method(m: Connection.Method): WebRequest {
        method = m
        return this
    }

    fun get(): Document {
        method = Connection.Method.GET
        return execute().parse()
    }

    fun post(): Document {
        method = Connection.Method.POST
        return execute().parse()
    }

    fun execute(): WebResponse {
        if (!WebViewHttp.isAvailable()) {
            throw IOException("WebView transport not available for $url")
        }

        val isPost = method == Connection.Method.POST
        val finalUrl = if (!isPost && data.isNotEmpty()) {
            val query = data.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            if (url.contains("?")) "$url&$query" else "$url?$query"
        } else {
            url
        }

        val result = WebViewHttp.execute(
            finalUrl,
            if (isPost) "POST" else "GET",
            headers,
            if (isPost) data else emptyMap(),
            rawBody
        )
        if (result.status !in 200..299) {
            throw HttpStatusException("HTTP error fetching URL", result.status, url)
        }

        val setCookies = LinkedHashMap<String, String>()
        try {
            CookieManager.getInstance().getCookie(finalUrl)?.split(";")?.forEach { part ->
                val idx = part.indexOf('=')
                if (idx > 0) setCookies[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return WebResponse(url, result.body, setCookies)
    }
}

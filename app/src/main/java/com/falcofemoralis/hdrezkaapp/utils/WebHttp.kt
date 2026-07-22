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
        // Cloudflare sometimes serves an HTTP 200 JS-challenge interstitial instead of the real
        // page (warmup not cleared yet); surface it as a retryable block rather than letting the
        // caller parse it as "no results".
        if (isCloudflareChallenge(result.body)) {
            throw HttpStatusException("Cloudflare challenge (not cleared)", 503, url)
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

    // Detect an actual Cloudflare interstitial. Do NOT match "challenge-platform": Cloudflare
    // injects the /cdn-cgi/challenge-platform/ beacon script into normal, successfully-served
    // pages too (JS Detections / Bot Fight Mode), so it produced a false positive on every
    // full-page GET (film cards, list pagination, actor pages) — turning good pages into a
    // retried 503. These two markers appear only on the real challenge/verification pages.
    private fun isCloudflareChallenge(body: String): Boolean =
        body.contains("window._cf_chl_opt") ||
        body.contains("cf-browser-verification")
}

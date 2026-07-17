package com.falcofemoralis.hdrezkaapp.utils

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes hdrezka HTTP requests through a hidden WebView so they pass the mirror's
 * Cloudflare protection: a same-origin `fetch()` executed inside the WebView carries
 * the real browser session (cf_clearance cookie, TLS fingerprint, UA) that a plain
 * OkHttp/jsoup client cannot reproduce.
 *
 * The WebView must first be [attach]ed and have loaded the mirror origin (so fetch is
 * same-origin). Requests are dispatched on the main thread and the calling background
 * thread blocks on a latch until the JS bridge reports the result.
 */
object WebViewHttp {
    class Result(val status: Int, val body: String)

    private val main = Handler(Looper.getMainLooper())
    private val idGen = AtomicInteger(0)
    private val pending = ConcurrentHashMap<Int, Holder>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    var enabled: Boolean = false

    private class Holder {
        val latch = CountDownLatch(1)
        @Volatile var status = -1
        @Volatile var body = ""
        @Volatile var error: String? = null
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    fun attach(webView: WebView) {
        this.webView = webView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.addJavascriptInterface(Bridge(), "AndroidHttpBridge")
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        try {
            cm.setAcceptThirdPartyCookies(webView, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun detach(webView: WebView) {
        if (this.webView === webView) {
            this.webView = null
        }
    }

    fun isAvailable(): Boolean = enabled && webView != null

    fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        formData: Map<String, String>,
        rawBody: String?
    ): Result {
        webView ?: throw IOException("WebView not available")
        val id = idGen.incrementAndGet()
        val holder = Holder()
        pending[id] = holder

        val js = buildJs(id, url, method, headers, formData, rawBody)
        main.post {
            val w = webView
            if (w == null) {
                holder.error = "WebView gone"
                holder.latch.countDown()
            } else {
                w.evaluateJavascript(js, null)
            }
        }

        val completed = holder.latch.await(45, TimeUnit.SECONDS)
        pending.remove(id)
        if (!completed) throw IOException("WebView request timeout: $url")
        holder.error?.let { throw IOException("WebView request failed: $it") }
        return Result(holder.status, holder.body)
    }

    private fun buildJs(
        id: Int,
        url: String,
        method: String,
        headers: Map<String, String>,
        formData: Map<String, String>,
        rawBody: String?
    ): String {
        val opts = JSONObject()
        opts.put("method", method)
        opts.put("credentials", "include")

        val forbidden = setOf(
            "cookie", "user-agent", "host", "content-length",
            "accept-encoding", "connection", "origin", "referer"
        )
        val h = JSONObject()
        for ((k, v) in headers) {
            if (k.lowercase() in forbidden) continue
            h.put(k, v)
        }

        if (method == "POST") {
            val body = rawBody ?: formData.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            opts.put("body", body)
            if (!h.has("Content-Type")) {
                h.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
        }
        opts.put("headers", h)

        val urlJson = JSONObject.quote(url)
        val optsJson = opts.toString()
        return """
            (function(){
              try {
                fetch($urlJson, $optsJson)
                  .then(function(r){ return r.text().then(function(t){ AndroidHttpBridge.onResult($id, r.status, t); }); })
                  .catch(function(e){ AndroidHttpBridge.onError($id, '' + e); });
              } catch (e) { AndroidHttpBridge.onError($id, '' + e); }
            })();
        """.trimIndent()
    }

    private fun deliverResult(id: Int, status: Int, body: String) {
        pending[id]?.let {
            it.status = status
            it.body = body
            it.latch.countDown()
        }
    }

    private fun deliverError(id: Int, message: String) {
        pending[id]?.let {
            it.error = message
            it.latch.countDown()
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun onResult(id: Int, status: Int, body: String) = deliverResult(id, status, body)

        @JavascriptInterface
        fun onError(id: Int, message: String) = deliverError(id, message)
    }
}

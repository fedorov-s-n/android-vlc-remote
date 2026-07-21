package org.peterbaldwin.vlcremote.model

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * In-memory log of errors surfaced to the user. Every error that shows a toast also records an
 * entry here so it can be reviewed on the Errors page in settings (with the full stacktrace).
 *
 * Bounded to [MAX] entries and never persisted — it starts empty on each app launch. Each entry
 * is stamped with the wall-clock time it was recorded.
 */
object ErrorLog {
    private const val MAX = 1000

    data class Entry(val timeMillis: Long, val message: String, val detail: String?, val count: Int)

    private val entries = ArrayList<Entry>()
    private val main = Handler(Looper.getMainLooper())

    /** Records an error. [throwable]'s stacktrace (if any) is kept as the entry detail. Repeated
     *  identical errors (same message + stacktrace) collapse into the last entry with a count, so
     *  a spamming failure (e.g. a per-second status poll while the server is down) can't flood it. */
    @JvmStatic
    @JvmOverloads
    fun log(message: String?, throwable: Throwable? = null) {
        val msg = message?.takeIf { it.isNotBlank() } ?: throwable?.message ?: "Error"
        val detail = throwable?.let { Log.getStackTraceString(it) }
        synchronized(entries) {
            val last = entries.lastOrNull()
            if (last != null && last.message == msg && last.detail == detail) {
                entries[entries.size - 1] = last.copy(timeMillis = System.currentTimeMillis(), count = last.count + 1)
            } else {
                entries.add(Entry(System.currentTimeMillis(), msg, detail, 1))
                while (entries.size > MAX) entries.removeAt(0)
            }
        }
        Log.w("VlcRemote", msg, throwable)
    }

    /** Shows an error toast AND records it. Safe to call from any thread. */
    @JvmStatic
    @JvmOverloads
    fun toast(context: Context?, message: CharSequence?, throwable: Throwable? = null) {
        val text = message?.toString()
        log(text, throwable)
        if (context != null && text != null) {
            val appCtx = context.applicationContext
            main.post { Toast.makeText(appCtx, text, Toast.LENGTH_LONG).show() }
        }
    }

    /** Newest first, for display. */
    @JvmStatic
    fun snapshot(): List<Entry> = synchronized(entries) { entries.reversed() }

    @JvmStatic
    fun clear() = synchronized(entries) { entries.clear() }

    @JvmStatic
    fun count(): Int = synchronized(entries) { entries.size }
}

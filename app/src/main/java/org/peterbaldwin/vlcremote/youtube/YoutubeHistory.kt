package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson

/**
 * Recently opened YouTube items (videos / channels / playlists), shown on the search
 * screen when idle. Independent of the hdrezka history, with its own size setting.
 */
object YoutubeHistory {
    const val KEY_SIZE = "youtube_history_size"
    const val DEFAULT_SIZE = 15
    private const val KEY_RECENT = "youtube_hist_recent"

    private data class Entry(
        val kind: String,
        val title: String,
        val url: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val duration: Long = 0
    )

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun maxRecent(context: Context): Int =
        prefs(context).getString(KEY_SIZE, null)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_SIZE

    private fun entries(context: Context): List<Entry> {
        val json = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            Gson().fromJson(json, Array<Entry>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Recently opened items, newest first, as ready-to-display rows. */
    fun getRecent(context: Context): List<YtItem> = entries(context).mapNotNull {
        val kind = try { YtKind.valueOf(it.kind) } catch (e: Exception) { return@mapNotNull null }
        YtItem(kind, it.title, it.url, it.subtitle, it.thumbnailUrl, it.duration)
    }

    fun addRecent(context: Context, item: YtItem) {
        if (item.url.isBlank()) return
        val list = ArrayList(entries(context))
        list.removeAll { it.url == item.url }
        list.add(0, Entry(item.kind.name, item.title, item.url, item.subtitle, item.thumbnailUrl, item.duration))
        val max = maxRecent(context)
        while (list.size > max) {
            list.removeAt(list.size - 1)
        }
        prefs(context).edit().putString(KEY_RECENT, Gson().toJson(list)).apply()
    }

    @JvmStatic
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_RECENT).apply()
    }
}

package com.falcofemoralis.hdrezkaapp.utils

import android.content.Context
import androidx.preference.PreferenceManager
import com.google.gson.Gson
import java.io.Serializable

/**
 * A recently opened film: enough to render a card and reopen it, plus the dropdown
 * selections (voice / season / episode / quality / subtitle) last used on that page.
 */
data class RecentFilm(
    val link: String,
    val title: String?,
    val poster: String?,
    val voice: String? = null,
    val season: String? = null,
    val episode: String? = null,
    val quality: String? = null,
    val subtitle: String? = null
) : Serializable

/**
 * Remembers the recently opened film pages in the HDrezka tab (shown on the search
 * screen so they can be reopened) together with the dropdown selections made on each.
 */
object HdrezkaHistory {
    private const val KEY_RECENT = "hdrezka_hist_recent"
    private const val MAX_RECENT = 10

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun getRecent(context: Context): List<RecentFilm> {
        val json = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            Gson().fromJson(json, Array<RecentFilm>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The most recently opened film (front of the list), or null if history is empty. */
    fun getMostRecent(context: Context): RecentFilm? = getRecent(context).firstOrNull()

    /** The stored entry for [link], or null if this film isn't in the history. */
    fun getForLink(context: Context, link: String?): RecentFilm? =
        if (link.isNullOrBlank()) null else getRecent(context).firstOrNull { it.link == link }

    /**
     * Records a freshly opened film at the front of the recent list (deduped, capped),
     * preserving any selections already stored for it.
     */
    fun addRecent(context: Context, link: String?, title: String?, poster: String?) {
        if (link.isNullOrBlank()) return
        val list = ArrayList(getRecent(context))
        val existing = list.firstOrNull { it.link == link }
        list.removeAll { it.link == link }
        list.add(0, RecentFilm(link, title, poster, existing?.voice, existing?.season, existing?.episode, existing?.quality, existing?.subtitle))
        while (list.size > MAX_RECENT) {
            list.removeAt(list.size - 1)
        }
        save(context, list)
    }

    /** Updates the dropdown selections stored for the film [link] (if it's in the list). */
    fun updateSelections(context: Context, link: String?, voice: String?, season: String?, episode: String?, quality: String?, subtitle: String?) {
        if (link.isNullOrBlank()) return
        val list = ArrayList(getRecent(context))
        val idx = list.indexOfFirst { it.link == link }
        if (idx < 0) return
        list[idx] = list[idx].copy(voice = voice, season = season, episode = episode, quality = quality, subtitle = subtitle)
        save(context, list)
    }

    private fun save(context: Context, list: List<RecentFilm>) {
        prefs(context).edit().putString(KEY_RECENT, Gson().toJson(list)).apply()
    }
}

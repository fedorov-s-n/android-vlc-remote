package com.falcofemoralis.hdrezkaapp.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.falcofemoralis.hdrezkaapp.models.FilmModel
import com.falcofemoralis.hdrezkaapp.objects.Film
import com.falcofemoralis.hdrezkaapp.objects.Stream
import com.falcofemoralis.hdrezkaapp.objects.Subtitle
import com.falcofemoralis.hdrezkaapp.objects.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.client.android.vlcremote.R
import org.peterbaldwin.vlcremote.model.Hotkeys
import org.peterbaldwin.vlcremote.model.Server
import org.peterbaldwin.vlcremote.net.MediaServer
import org.peterbaldwin.vlcremote.rezka.DownloadPathClient

/**
 * Sends HDrezka streams to the remote VLC and, for series, remembers enough context to
 * step to the next/previous episode (crossing season boundaries) from the VLC controls.
 */
object RezkaPlayback {
    private val handler = Handler(Looper.getMainLooper())

    private var film: Film? = null
    private var voice: Voice? = null
    private var season: String? = null
    private var episode: String? = null
    private var qualityLabel: String? = null
    private var subtitleLang: String? = null
    private var authority: String? = null

    // True from the moment we begin switching episode (start/next/previous) until shortly after the
    // new stream has actually been sent to VLC. Autorun consults this so an app-initiated stop
    // isn't mistaken for the current episode ending on its own and auto-advanced. Cleared by a
    // timer (armed when the new play is issued) rather than by a status, so a lagging "old episode
    // playing" poll can't clear it early.
    @Volatile
    private var switching = false
    private val clearSwitching = Runnable { switching = false }
    private const val SWITCH_GUARD_MS = 6000L

    private fun beginSwitch() {
        switching = true
        handler.removeCallbacks(clearSwitching)
        handler.postDelayed(clearSwitching, SWITCH_GUARD_MS)  // fallback if the play never happens
    }

    /** True while the last thing started via this app was an HDrezka series episode. */
    @JvmStatic
    fun isActiveSeries(): Boolean = voice?.seasons != null && season != null && episode != null

    /** True while a switch is in flight — autorun skips auto-advancing while set. */
    @JvmStatic
    fun isSwitching(): Boolean = switching

    /** Forget the series context (called when something else starts playing). */
    @JvmStatic
    fun clear() {
        film = null
        voice = null
        season = null
        episode = null
        qualityLabel = null
        subtitleLang = null
        authority = null
        handler.removeCallbacks(clearSwitching)
        switching = false
    }

    /** Plays a movie stream; clears any series context. */
    fun playMovie(context: Context, authority: String, film: Film, stream: Stream, subtitle: Subtitle?) {
        clear()
        org.peterbaldwin.vlcremote.youtube.YoutubePlayback.clear()
        org.peterbaldwin.vlcremote.youtube.YtDownloadManager.cancel()
        doPlay(context, authority, stream.url, withQuality(baseTitle(film), stream.quality), subtitle)
    }

    /** Plays a series episode and remembers the context for next/previous navigation. */
    fun playSeries(context: Context, authority: String, film: Film, voice: Voice, season: String, episode: String, stream: Stream, subtitle: Subtitle?) {
        org.peterbaldwin.vlcremote.youtube.YoutubePlayback.clear()
        org.peterbaldwin.vlcremote.youtube.YtDownloadManager.cancel()
        beginSwitch()   // suppress autorun until this episode is playing
        this.film = film
        this.voice = voice
        this.season = season
        this.episode = episode
        this.qualityLabel = stream.quality
        this.subtitleLang = subtitle?.lang
        this.authority = authority
        doPlay(context, authority, stream.url, episodeTitle(film, season, episode, stream.quality), subtitle)
    }

    /** Prefer the original (usually Latin) title to keep the VLC title ASCII-safe. */
    private fun baseTitle(film: Film): String =
        film.origTitle?.takeIf { it.isNotBlank() } ?: film.title ?: ""

    /** e.g. "The Big Bang Theory - S1 E1 1080p" (ASCII separator, digits, quality). */
    private fun episodeTitle(film: Film, season: String, episode: String, quality: String?): String {
        val base = baseTitle(film)
        val t = (if (base.isBlank()) "" else "$base - ") + "S$season E$episode"
        return withQuality(t, quality)
    }

    /** Appends the quality label to the title, e.g. "… 1080p". */
    private fun withQuality(title: String, quality: String?): String =
        if (quality.isNullOrBlank()) title else if (title.isBlank()) quality else "$title $quality"

    /** @return true if an adjacent episode exists and playback of it was started. */
    @JvmStatic
    fun playNext(context: Context): Boolean = navigate(context, +1)

    @JvmStatic
    fun playPrevious(context: Context): Boolean = navigate(context, -1)

    private fun navigate(context: Context, dir: Int): Boolean {
        val v = voice ?: return false
        val seasons = v.seasons ?: return false
        val seasonKeys = seasons.keys.toList()
        val si = seasonKeys.indexOf(season)
        if (si < 0) return false
        val eps = seasons[season] ?: return false
        val ei = eps.indexOf(episode)
        if (ei < 0) return false

        var newSi = si
        var newEi = ei + dir
        if (newEi < 0) {
            // Move to the last episode of the previous season.
            newSi = si - 1
            if (newSi < 0) return false
            newEi = (seasons[seasonKeys[newSi]]?.size ?: 0) - 1
            if (newEi < 0) return false
        } else if (newEi >= eps.size) {
            // Move to the first episode of the next season.
            newSi = si + 1
            if (newSi >= seasonKeys.size) return false
            if ((seasons[seasonKeys[newSi]]?.size ?: 0) == 0) return false
            newEi = 0
        }

        val newSeason = seasonKeys[newSi]
        val newEpisode = seasons[newSeason]?.getOrNull(newEi) ?: return false
        beginSwitch()   // suppress autorun during the stop while the new episode loads
        loadAndPlayEpisode(context, newSeason, newEpisode)
        return true
    }

    private fun loadAndPlayEpisode(context: Context, newSeason: String, newEpisode: String) {
        val v = voice ?: return
        val f = film ?: return
        val auth = authority ?: return
        val filmId = f.filmId ?: return

        GlobalScope.launch {
            try {
                FilmModel.getStreamsByEpisodeId(v, filmId, newSeason, newEpisode)
                withContext(Dispatchers.Main) {
                    val streams = v.streams ?: arrayListOf()
                    val stream = streams.firstOrNull { it.quality == qualityLabel }
                        ?: streams.firstOrNull { it.quality.equals("1080p", true) }
                        ?: streams.lastOrNull()
                    if (stream == null) {
                        return@withContext
                    }
                    val subtitle = subtitleLang?.let { lang -> v.subtitles?.firstOrNull { it.lang == lang } }

                    season = newSeason
                    episode = newEpisode
                    doPlay(context, auth, stream.url, episodeTitle(f, newSeason, newEpisode, stream.quality), subtitle)

                    val subLabel = subtitle?.lang ?: context.getString(R.string.msg_subtitle_off)
                    HdrezkaHistory.updateSelections(context, f.filmLink, v.name, newSeason, newEpisode, stream.quality, subLabel)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun doPlay(context: Context, authority: String, streamUrl: String, title: String?, subtitle: Subtitle?) {
        // Restart the guard countdown from the moment the new stream is actually sent, so the
        // suppression window covers the load + VLC transition regardless of how long the fetch took.
        if (switching) {
            handler.removeCallbacks(clearSwitching)
            handler.postDelayed(clearSwitching, SWITCH_GUARD_MS)
        }
        val server = MediaServer(context, authority)
        // Title rides only in the URL-encoded :meta-title option (not the MRL), so it can't
        // corrupt the stream URL; VLC then shows it in the now-playing for any stream.
        server.status().command.input.playWithMetaTitle(streamUrl, title)
        if (subtitle != null) {
            attachSubtitle(context, server, authority, subtitle)
        }
    }

    /**
     * Downloads the subtitle to the VLC host via the companion server, then attaches it
     * and toggles the subtitle track on (VLC's addsubtitle does not accept remote URLs).
     */
    private fun attachSubtitle(context: Context, server: MediaServer, authority: String, subtitle: Subtitle) {
        val ext = subtitle.url.substringBefore('?').substringAfterLast('.', "vtt")
        val name = subtitle.lang.replace(Regex("[^A-Za-z0-9]"), "_") + "." + ext
        SubtitleAttacher.attach(context, server, authority, subtitle.url, name)
    }
}

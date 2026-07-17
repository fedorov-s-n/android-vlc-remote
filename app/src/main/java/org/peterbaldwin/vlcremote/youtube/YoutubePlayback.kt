package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import com.falcofemoralis.hdrezkaapp.utils.SubtitleAttacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.vlcremote.net.MediaServer

/**
 * Remembers a YouTube playlist queue so the Now Playing next/previous buttons and autoplay
 * can step through it, the same way [com.falcofemoralis.hdrezkaapp.utils.RezkaPlayback] steps
 * through an HDrezka series. Each step resolves the next video's streams and issues a fresh
 * in_play (VLC's own playlist is not used).
 */
object YoutubePlayback {
    private var urls: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var index: Int = -1
    private var authority: String? = null
    private var qualityLabel: String? = null
    private var subtitleLabel: String? = null

    /** True while the last thing started via this app was a video from a YouTube playlist. */
    @JvmStatic
    fun isActive(): Boolean = urls.isNotEmpty() && index in urls.indices

    /** Forget the queue (called when something else starts playing). */
    @JvmStatic
    fun clear() {
        urls = emptyList()
        titles = emptyList()
        index = -1
        authority = null
        qualityLabel = null
        subtitleLabel = null
    }

    /**
     * Records the playlist context after the video fragment has already started the chosen
     * item, so next/previous can continue from [index] reapplying the same quality/subtitle.
     */
    fun start(
        authority: String,
        urls: List<String>,
        titles: List<String>,
        index: Int,
        qualityLabel: String?,
        subtitleLabel: String?
    ) {
        this.authority = authority
        this.urls = urls
        this.titles = titles
        this.index = index
        this.qualityLabel = qualityLabel
        this.subtitleLabel = subtitleLabel
    }

    @JvmStatic
    fun playNext(context: Context): Boolean = navigate(context, +1)

    @JvmStatic
    fun playPrevious(context: Context): Boolean = navigate(context, -1)

    private fun navigate(context: Context, dir: Int): Boolean {
        val newIndex = index + dir
        if (newIndex !in urls.indices) return false
        index = newIndex
        play(context)
        return true
    }

    private fun play(context: Context) {
        val auth = authority ?: return
        val url = urls.getOrNull(index) ?: return
        val title = titles.getOrNull(index)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val v = YoutubeClient.video(url)
                // Reapply the previously chosen quality; otherwise fall back to the highest
                // seekable (muxed) stream, matching the video fragment's default.
                val quality = v.qualities.firstOrNull { it.label == qualityLabel }
                    ?: v.qualities.firstOrNull { !it.isVideoOnly }
                    ?: v.qualities.firstOrNull()
                    ?: return@launch

                val options = ArrayList<String>()
                if (!title.isNullOrEmpty()) options.add(":meta-title=$title")
                if (quality.isVideoOnly) {
                    v.audios.firstOrNull()?.let { options.add(":input-slave=" + it.url) }
                }

                val subtitle = subtitleLabel?.let { lbl -> v.subtitles.firstOrNull { it.label == lbl } }

                withContext(Dispatchers.Main) {
                    val server = MediaServer(context, auth)
                    server.status().command.input.playWithOptions(quality.url, options)
                    if (subtitle != null) {
                        val name = subtitle.label.replace(Regex("[^A-Za-z0-9]"), "_") + ".vtt"
                        SubtitleAttacher.attach(context, server, auth, subtitle.url, name)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

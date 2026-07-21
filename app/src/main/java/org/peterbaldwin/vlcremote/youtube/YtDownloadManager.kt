package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.falcofemoralis.hdrezkaapp.utils.SubtitleAttacher
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.peterbaldwin.vlcremote.model.ErrorLog
import org.peterbaldwin.vlcremote.model.HelperConfig
import org.peterbaldwin.vlcremote.model.Server
import org.peterbaldwin.vlcremote.net.MediaServer
import java.util.Locale

/**
 * Drives one YouTube "best quality" download: asks the host helper to remux the chosen
 * video+audio into a single growing file, starts playing that local file once ~5% is
 * downloaded, and reports progress ("Xs/s") / completion (size + time) for the Now Playing
 * tab. Switching video (or clearing the playlist) cancels the job and deletes the file.
 */
object YtDownloadManager {
    // Start playback once the download runs comfortably ahead of real time: two consecutive
    // polls whose speed (seconds of video fetched per wall-second) is >= RATE_MIN.
    private const val RATE_MIN = 1.1
    private const val RATE_STREAK = 2
    private const val MIN_BUFFER_SEC = 2   // also require at least this many seconds downloaded
    // Give up (instead of polling every second forever with a stale "Downloading…") after this
    // many consecutive polls where the helper is unreachable.
    private const val MAX_POLL_FAILURES = 15
    // If the download never sustains RATE_MIN (link slower than realtime), start playing anyway
    // once buffered and this many RUNNING polls have elapsed, so it isn't stuck forever.
    private const val START_FALLBACK_POLLS = 20
    private const val KEY_RESUME = "yt_dl_resume"

    /** Persisted so the playlist queue survives an app restart (matched by the playing file). */
    private data class ResumeState(
        val file: String,
        val urls: List<String>,
        val titles: List<String>,
        val index: Int,
        val quality: String?,
        val playlist: String?
    )

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var host: String? = null
    private var port: Int = 3900
    private var authHeader: String? = null
    private var jobKey: String? = null
    private var authority: String? = null
    private var title: String? = null
    private var channel: String? = null
    private var albumName: String? = null
    private var durationSec: Long = 0
    private var subtitleUrl: String? = null
    private var subtitleName: String? = null
    private var playStarted = false
    private var fastPolls = 0
    private var pollFailures = 0   // consecutive polls where the helper was unreachable
    private var runningPolls = 0   // consecutive RUNNING polls seen before playback started
    private var downloadedSec = 0L
    private var resuming = false

    // True from the moment we begin switching to a video (start/next/previous) until it actually
    // starts playing. Autorun consults this so an app-initiated stop (which looks like "near the
    // end" to VLC) isn't mistaken for the current video ending on its own and auto-advanced.
    @Volatile
    private var switching = false

    // True while playing a combined stream directly because the helper is off/misconfigured — no
    // download, no seeking. Keeps the manager "active" so Now Playing shows the metadata + duration.
    @Volatile
    private var directPlaying = false

    // Playlist queue so the Now Playing next/previous buttons and autoplay can step through it.
    private var queue: List<String> = emptyList()
    private var queueTitles: List<String> = emptyList()
    private var queueIndex: Int = -1
    private var qualityLabel: String? = null
    private var playlistName: String? = null

    /** Text for the Now Playing tab; null when nothing to show. Persists until [cancel]. */
    @Volatile
    private var statusText: String? = null

    @JvmStatic
    fun statusText(): String? = statusText

    @JvmStatic
    fun isActive(): Boolean = jobKey != null || statusText != null || directPlaying

    /** Full video duration (seconds) of the active download, or 0 — so Now Playing can show the
     *  real total time immediately instead of VLC's still-growing length. */
    @JvmStatic
    fun totalDurationSec(): Long = if (isActive()) durationSec else 0

    /** Seconds of video downloaded so far — for the seekbar's buffered (secondary) region. */
    @JvmStatic
    fun downloadedSec(): Long = if (isActive()) downloadedSec else 0

    // Shown in Now Playing immediately at start, before the file loads (VLC then shows the file's
    // own — identical — metadata). Non-null only for downloads we started this session.
    @JvmStatic
    fun infoTitle(): String? = if (isActive()) title else null

    @JvmStatic
    fun infoArtist(): String? = if (isActive()) channel else null

    @JvmStatic
    fun infoAlbum(): String? = if (isActive()) albumName else null

    /** True while playing from a YouTube playlist — the Now Playing next/prev + autoplay use it. */
    @JvmStatic
    fun hasQueue(): Boolean = isActive() && queue.isNotEmpty()

    /** True while a switch (start/next/previous) is in flight, before the new video plays. Autorun
     *  skips auto-advancing while this holds, so an app-initiated stop isn't read as a natural end. */
    @JvmStatic
    fun isSwitching(): Boolean = switching

    /**
     * Starts a download+play job for a video the fragment already resolved. [queueUrls]/[index]
     * give the surrounding playlist (empty if none) for next/previous; [qualityLabel] is reapplied
     * to the next/previous videos.
     */
    fun start(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        durationSec: Long,
        title: String?,
        channel: String?,
        album: String?,
        authority: String,
        subtitleUrl: String?,
        subtitleName: String?,
        queueUrls: List<String>,
        queueTitles: List<String>,
        queueIndex: Int,
        qualityLabel: String?
    ) {
        cancel()
        this.queue = queueUrls
        this.queueTitles = queueTitles
        this.queueIndex = queueIndex
        this.qualityLabel = qualityLabel
        this.playlistName = album
        if (HelperConfig.isUsable(context, authority)) {
            beginDownload(context, videoUrl, audioUrl, durationSec, title, channel, album, authority, subtitleUrl, subtitleName)
        } else {
            // No helper: play the chosen quality + audio directly (no download, no seeking).
            beginDirect(context, videoUrl, audioUrl, durationSec, title, channel, album, authority)
        }
    }

    /** Steps to the next / previous playlist entry, resolving + downloading it. */
    @JvmStatic
    fun playNext(context: Context): Boolean = navigate(context, +1)

    @JvmStatic
    fun playPrevious(context: Context): Boolean = navigate(context, -1)

    private fun navigate(context: Context, dir: Int): Boolean {
        val newIndex = queueIndex + dir
        if (newIndex !in queue.indices) return false
        val auth = authority ?: return false
        val ctx = appContext ?: context.applicationContext
        switching = true   // set before we stop VLC below, so autorun ignores that stop
        queueIndex = newIndex
        val url = queue[newIndex]
        val label = qualityLabel
        val pl = playlistName
        cancelJob()
        try { MediaServer(ctx, auth).status().command.playback.stop() } catch (e: Exception) { e.printStackTrace() }
        statusText = "Preparing…"
        GlobalScope.launch(Dispatchers.IO) {
            val v = try { YoutubeClient.video(url) } catch (e: Exception) {
                ErrorLog.log("YouTube: failed to load next/previous video", e); null
            }
            withContext(Dispatchers.Main) {
                if (v == null) { statusText = "Download failed"; return@withContext }
                val q = v.qualities.firstOrNull { it.label == label }
                    ?: v.qualities.firstOrNull { it.label.startsWith("1080") }
                    ?: v.qualities.firstOrNull()
                val audio = v.audioUrl
                if (q == null || audio == null) {
                    statusText = "No playable stream"
                    ErrorLog.log("YouTube: no playable stream for queue item")
                    return@withContext
                }
                val titleQ = if (q.label.isNotBlank()) "${v.title} [${q.label}]" else v.title
                if (HelperConfig.isUsable(ctx, auth)) {
                    beginDownload(ctx, q.url, audio, v.durationSec, titleQ, v.uploader, pl, auth, null, null)
                } else {
                    beginDirect(ctx, q.url, audio, v.durationSec, titleQ, v.uploader, pl, auth)
                }
            }
        }
        return true
    }

    private fun beginDownload(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        durationSec: Long,
        title: String?,
        channel: String?,
        album: String?,
        authority: String,
        subtitleUrl: String?,
        subtitleName: String?
    ) {
        cancelJob()
        switching = true   // covers start()-from-list; cleared once play() actually begins
        val ctx = context.applicationContext
        appContext = ctx
        this.authority = authority
        this.title = title
        this.channel = channel
        this.albumName = album
        this.durationSec = durationSec
        this.subtitleUrl = subtitleUrl
        this.subtitleName = subtitleName
        playStarted = false
        fastPolls = 0
        pollFailures = 0
        runningPolls = 0
        downloadedSec = 0L

        // Stop the previous video immediately so it doesn't keep playing/showing until the new
        // download reaches its playback point.
        try {
            MediaServer(ctx, authority).status().command.playback.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val cfg = HelperConfig.resolve(ctx, authority)
        if (cfg == null) {
            statusText = "Download helper disabled in settings"
            return
        }
        host = cfg.host
        port = cfg.port
        authHeader = cfg.authHeader

        statusText = "Preparing download…"
        val h = cfg.host
        val p = cfg.port
        GlobalScope.launch(Dispatchers.IO) {
            val key = MuxClient.start(h, p, videoUrl, audioUrl, title ?: "", channel ?: "", album ?: "", durationSec, authHeader)
            withContext(Dispatchers.Main) {
                if (key == null) {
                    statusText = "Download unavailable (helper/ffmpeg?)"
                    ErrorLog.log("YouTube: helper did not start the download (helper/ffmpeg?)")
                    return@withContext
                }
                jobKey = key
                saveResume(ctx)
                poll()
            }
        }
    }

    /** Helper off/misconfigured: play the chosen video quality directly in VLC with the audio
     *  attached as an input-slave (VLC fetches both) — any quality up to YouTube's AVC, but no
     *  download, no seeking, no subtitles. Now Playing still shows title/channel/playlist +
     *  duration, and the playlist next/previous still step through the queue the same way. */
    private fun beginDirect(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        durationSec: Long,
        title: String?,
        channel: String?,
        album: String?,
        authority: String
    ) {
        cancelJob()
        val ctx = context.applicationContext
        appContext = ctx
        this.authority = authority
        this.title = title
        this.channel = channel
        this.albumName = album
        this.durationSec = durationSec
        this.subtitleUrl = null
        this.subtitleName = null
        playStarted = true
        downloadedSec = 0L
        directPlaying = true
        switching = true
        handler.postDelayed({ switching = false }, 4000)

        try {
            val options = ArrayList<String>()
            if (!title.isNullOrEmpty()) options.add(":meta-title=$title")
            options.add(":input-slave=$audioUrl")
            MediaServer(ctx, authority).status().command.input.playWithOptions(videoUrl, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveResume(ctx: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val key = jobKey
        if (queue.isEmpty() || key == null) {
            prefs.edit().remove(KEY_RESUME).apply()
            return
        }
        val state = ResumeState(java.io.File(key).name, queue, queueTitles, queueIndex, qualityLabel, playlistName)
        prefs.edit().putString(KEY_RESUME, Gson().toJson(state)).apply()
    }

    private fun readResume(ctx: Context): ResumeState? {
        val json = PreferenceManager.getDefaultSharedPreferences(ctx).getString(KEY_RESUME, null) ?: return null
        return try {
            Gson().fromJson(json, ResumeState::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reconnects to a still-running/finished helper job after the app was restarted, given the
     * file VLC is currently playing (basename mux_<id>.ts). Resumes the progress indicator and
     * polling without restarting playback.
     */
    @JvmStatic
    fun resume(context: Context, playingFileName: String, authority: String) {
        if (isActive() || resuming) return
        val ctx = context.applicationContext
        val cfg = HelperConfig.resolve(ctx, authority) ?: return
        val h = cfg.host
        val p = cfg.port
        resuming = true
        GlobalScope.launch(Dispatchers.IO) {
            val st = MuxClient.status(h, p, playingFileName, cfg.authHeader)
            withContext(Dispatchers.Main) {
                resuming = false
                if (isActive()) return@withContext
                if (!st.running && !st.done) return@withContext  // helper doesn't know it
                appContext = ctx
                this@YtDownloadManager.authority = authority
                host = h
                port = p
                authHeader = cfg.authHeader
                jobKey = playingFileName
                durationSec = st.durationSec
                title = null
                subtitleUrl = null
                playStarted = true  // VLC is already playing this file
                pollFailures = 0
                runningPolls = 0

                // Restore the playlist queue (persisted at download start) if it's for this file,
                // so next/previous + autoplay work after an app restart.
                val rs = readResume(ctx)
                if (rs != null && rs.file == java.io.File(playingFileName).name) {
                    queue = rs.urls
                    queueTitles = rs.titles
                    queueIndex = rs.index
                    qualityLabel = rs.quality
                    playlistName = rs.playlist
                }
                if (st.done) {
                    downloadedSec = durationSec
                    statusText = "Downloaded • " + sizeMb(st.total) + " • " + clock(st.ms)
                } else {
                    downloadedSec = if (st.total > 0) (st.bytes.toDouble() / st.total * durationSec).toLong() else 0
                    statusText = "Downloading…"
                    poll()
                }
            }
        }
    }

    private fun poll() {
        val id = jobKey ?: return
        val h = host ?: return
        val p = port
        GlobalScope.launch(Dispatchers.IO) {
            val st = MuxClient.status(h, p, id, authHeader)
            withContext(Dispatchers.Main) {
                if (jobKey != id) return@withContext  // cancelled or switched away
                var reschedule = true
                when {
                    st.running -> {
                        pollFailures = 0
                        val speed = averageSpeed(st.bytes, st.ms, st.total)
                        fastPolls = if (speed >= RATE_MIN) fastPolls + 1 else 0
                        val dlSec = if (st.total > 0) (st.bytes.toDouble() / st.total * durationSec).toLong() else 0
                        downloadedSec = dlSec
                        statusText = String.format(
                            Locale.US, "Downloading… %s/%s • %.1fs/s",
                            hms(dlSec), hms(durationSec), speed
                        )
                        if (!playStarted) {
                            runningPolls++
                            val ready = fastPolls >= RATE_STREAK && dlSec >= MIN_BUFFER_SEC
                            // Slow link that never reaches RATE_MIN: once buffered and we've waited
                            // long enough, start anyway rather than never starting.
                            val fallback = runningPolls >= START_FALLBACK_POLLS && dlSec >= MIN_BUFFER_SEC
                            if (ready || fallback) play(st.path)
                        }
                    }
                    st.done -> {
                        pollFailures = 0
                        play(st.path)
                        downloadedSec = durationSec
                        statusText = "Downloaded • " + sizeMb(st.total) + " • " + clock(st.ms)
                        reschedule = false
                    }
                    else -> {  // ERROR / UNKNOWN / network blip (empty state)
                        if (st.state == "ERROR" || st.state == "UNKNOWN") {
                            statusText = "Download failed"
                            ErrorLog.log("YouTube: helper reported download " + st.state + " for " + id)
                            jobKey = null
                            reschedule = false
                        } else {
                            // Empty state = helper unreachable. Tolerate transient blips, but stop
                            // after MAX_POLL_FAILURES instead of polling forever.
                            pollFailures++
                            if (pollFailures >= MAX_POLL_FAILURES) {
                                statusText = "Download failed (helper unreachable)"
                                ErrorLog.log("YouTube: helper unreachable while polling $id")
                                jobKey = null
                                reschedule = false
                            }
                        }
                    }
                }
                if (reschedule) handler.postDelayed({ poll() }, 1000)
            }
        }
    }

    /** Average speed over the whole download so far, in seconds-of-video per wall-second. */
    private fun averageSpeed(bytes: Long, ms: Long, total: Long): Double {
        if (durationSec <= 0 || total <= 0 || ms <= 0) return 0.0
        val videoSec = bytes.toDouble() / total * durationSec
        return videoSec / (ms / 1000.0)
    }

    private fun play(path: String?) {
        if (playStarted || path == null) return
        val auth = authority ?: return
        val ctx = appContext ?: return
        playStarted = true
        // Clear the switch guard a few seconds after we issue play — not immediately — so a status
        // poll still lagging behind on the old "stopped" state can't slip an autorun in first.
        // (cancelJob's removeCallbacksAndMessages clears this too if we switch again meanwhile.)
        handler.postDelayed({ switching = false }, 4000)
        val server = MediaServer(ctx, auth)
        server.status().command.input.playWithMetaTitle(path, title)
        subtitleUrl?.let { SubtitleAttacher.attach(ctx, server, auth, it, subtitleName ?: "sub.vtt") }
    }

    private fun sizeMb(bytes: Long): String =
        String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)

    /** Video time as M:SS, or H:MM:SS when an hour or more. */
    private fun hms(s: Long): String {
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** Cancels the current helper job (kills the host download, deletes the file) and clears the
     *  per-video state + indicator, but keeps the playlist queue for next/previous. */
    private fun cancelJob() {
        val id = jobKey
        val h = host
        val p = port
        jobKey = null
        playStarted = false
        directPlaying = false
        statusText = null
        title = null
        channel = null
        albumName = null
        handler.removeCallbacksAndMessages(null)
        if (id != null && h != null) {
            GlobalScope.launch(Dispatchers.IO) { MuxClient.cancel(h, p, id, authHeader) }
        }
    }

    /** Cancels the job and forgets everything (called when another source starts / playlist cleared). */
    @JvmStatic
    fun cancel() {
        cancelJob()
        switching = false
        queue = emptyList()
        queueTitles = emptyList()
        queueIndex = -1
        qualityLabel = null
        playlistName = null
        appContext?.let { PreferenceManager.getDefaultSharedPreferences(it).edit().remove(KEY_RESUME).apply() }
    }
}

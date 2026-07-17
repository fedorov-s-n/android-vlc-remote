package org.peterbaldwin.vlcremote.youtube

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.falcofemoralis.hdrezkaapp.utils.SubtitleAttacher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private val handler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var host: String? = null
    private var port: Int = 3900
    private var jobKey: String? = null
    private var authority: String? = null
    private var title: String? = null
    private var durationSec: Long = 0
    private var subtitleUrl: String? = null
    private var subtitleName: String? = null
    private var playStarted = false
    private var fastPolls = 0
    private var prevBytes = 0L
    private var prevMs = 0L
    private var downloadedSec = 0L
    private var resuming = false

    /** Text for the Now Playing tab; null when nothing to show. Persists until [cancel]. */
    @Volatile
    private var statusText: String? = null

    @JvmStatic
    fun statusText(): String? = statusText

    @JvmStatic
    fun isActive(): Boolean = jobKey != null || statusText != null

    /** Full video duration (seconds) of the active download, or 0 — so Now Playing can show the
     *  real total time immediately instead of VLC's still-growing length. */
    @JvmStatic
    fun totalDurationSec(): Long = if (isActive()) durationSec else 0

    /** Seconds of video downloaded so far — for the seekbar's buffered (secondary) region. */
    @JvmStatic
    fun downloadedSec(): Long = if (isActive()) downloadedSec else 0

    /**
     * Starts a download+play job for [ytUrl]. Cancels any previous job first. [subtitleUrl] is
     * attached once playback begins (may be null).
     */
    fun start(
        context: Context,
        ytUrl: String,
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
        cancel()
        val ctx = context.applicationContext
        appContext = ctx
        this.authority = authority
        this.title = title
        this.durationSec = durationSec
        this.subtitleUrl = subtitleUrl
        this.subtitleName = subtitleName
        playStarted = false
        fastPolls = 0
        prevBytes = 0L
        prevMs = 0L
        downloadedSec = 0L

        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean("hdrezka_sub_server_enabled", true)) {
            statusText = "Download helper disabled in settings"
            return
        }
        host = prefs.getString("hdrezka_sub_server_host", null)?.takeIf { it.isNotBlank() }
            ?: Server.fromKey(authority).host
        port = prefs.getString("hdrezka_sub_server_port", null)?.toIntOrNull() ?: 3900

        statusText = "Preparing download…"
        val h = host ?: return
        val p = port
        GlobalScope.launch(Dispatchers.IO) {
            val key = MuxClient.start(h, p, videoUrl, audioUrl, title ?: "", channel ?: "", album ?: "", durationSec)
            withContext(Dispatchers.Main) {
                if (key == null) {
                    statusText = "Download unavailable (helper/ffmpeg?)"
                    return@withContext
                }
                jobKey = key
                poll()
            }
        }
    }

    /**
     * Reconnects to a still-running/finished helper job after the app was restarted, given the
     * file VLC is currently playing (basename mux_<id>.mkv). Resumes the progress indicator and
     * polling without restarting playback.
     */
    @JvmStatic
    fun resume(context: Context, playingFileName: String, authority: String) {
        if (isActive() || resuming) return
        val ctx = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (!prefs.getBoolean("hdrezka_sub_server_enabled", true)) return
        val h = prefs.getString("hdrezka_sub_server_host", null)?.takeIf { it.isNotBlank() }
            ?: Server.fromKey(authority).host
        val p = prefs.getString("hdrezka_sub_server_port", null)?.toIntOrNull() ?: 3900
        resuming = true
        GlobalScope.launch(Dispatchers.IO) {
            val st = MuxClient.status(h, p, playingFileName)
            withContext(Dispatchers.Main) {
                resuming = false
                if (isActive()) return@withContext
                if (!st.running && !st.done) return@withContext  // helper doesn't know it
                appContext = ctx
                this@YtDownloadManager.authority = authority
                host = h
                port = p
                jobKey = playingFileName
                durationSec = st.durationSec
                title = null
                subtitleUrl = null
                playStarted = true  // VLC is already playing this file
                prevBytes = st.bytes
                prevMs = st.ms
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
            val st = MuxClient.status(h, p, id)
            withContext(Dispatchers.Main) {
                if (jobKey != id) return@withContext  // cancelled or switched away
                var reschedule = true
                when {
                    st.running -> {
                        val speed = instantSpeed(st.bytes, st.ms, st.total)
                        prevBytes = st.bytes
                        prevMs = st.ms
                        fastPolls = if (speed >= RATE_MIN) fastPolls + 1 else 0
                        val dlSec = if (st.total > 0) (st.bytes.toDouble() / st.total * durationSec).toLong() else 0
                        downloadedSec = dlSec
                        statusText = String.format(
                            Locale.US, "Downloading… %s/%s • %.1fs/s",
                            hms(dlSec), hms(durationSec), speed
                        )
                        if (!playStarted && fastPolls >= RATE_STREAK) play(st.path)
                    }
                    st.done -> {
                        play(st.path)
                        downloadedSec = durationSec
                        statusText = "Downloaded • " + sizeMb(st.total) + " • " + clock(st.ms)
                        reschedule = false
                    }
                    else -> {  // ERROR / UNKNOWN / network blip
                        if (st.state == "ERROR" || st.state == "UNKNOWN") {
                            statusText = "Download failed"
                            jobKey = null
                            reschedule = false
                        }
                    }
                }
                if (reschedule) handler.postDelayed({ poll() }, 1000)
            }
        }
    }

    /** Instantaneous speed between the last two polls, in seconds-of-video per wall-second. */
    private fun instantSpeed(bytes: Long, ms: Long, total: Long): Double {
        if (durationSec <= 0 || total <= 0) return 0.0
        val dMs = ms - prevMs
        if (dMs <= 0) return 0.0
        val dVideoSec = (bytes - prevBytes).toDouble() / total * durationSec
        return dVideoSec / (dMs / 1000.0)
    }

    private fun play(path: String?) {
        if (playStarted || path == null) return
        val auth = authority ?: return
        val ctx = appContext ?: return
        playStarted = true
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

    /** Cancels the job (kills the host download, deletes the file) and clears the indicator. */
    @JvmStatic
    fun cancel() {
        val id = jobKey
        val h = host
        val p = port
        jobKey = null
        playStarted = false
        statusText = null
        handler.removeCallbacksAndMessages(null)
        if (id != null && h != null) {
            GlobalScope.launch(Dispatchers.IO) { MuxClient.cancel(h, p, id) }
        }
    }
}

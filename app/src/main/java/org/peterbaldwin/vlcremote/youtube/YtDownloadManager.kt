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
    private var jobId: String? = null
    private var authority: String? = null
    private var title: String? = null
    private var durationSec: Long = 0
    private var subtitleUrl: String? = null
    private var subtitleName: String? = null
    private var playStarted = false
    private var fastPolls = 0
    private var prevBytes = 0L
    private var prevMs = 0L

    /** Text for the Now Playing tab; null when nothing to show. Persists until [cancel]. */
    @Volatile
    private var statusText: String? = null

    @JvmStatic
    fun statusText(): String? = statusText

    @JvmStatic
    fun isActive(): Boolean = jobId != null || statusText != null

    /** Full video duration (seconds) of the active download, or 0 — so Now Playing can show the
     *  real total time immediately instead of VLC's still-growing length. */
    @JvmStatic
    fun totalDurationSec(): Long = if (isActive()) durationSec else 0

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
            val id = MuxClient.start(h, p, videoUrl, audioUrl, title ?: "", channel ?: "")
            withContext(Dispatchers.Main) {
                if (id == null) {
                    statusText = "Download unavailable (helper/ffmpeg?)"
                    return@withContext
                }
                jobId = id
                poll()
            }
        }
    }

    private fun poll() {
        val id = jobId ?: return
        val h = host ?: return
        val p = port
        GlobalScope.launch(Dispatchers.IO) {
            val st = MuxClient.status(h, p, id)
            withContext(Dispatchers.Main) {
                if (jobId != id) return@withContext  // cancelled or switched away
                var reschedule = true
                when {
                    st.running -> {
                        val speed = instantSpeed(st.bytes, st.ms, st.total)
                        prevBytes = st.bytes
                        prevMs = st.ms
                        fastPolls = if (speed >= RATE_MIN) fastPolls + 1 else 0
                        val dlSec = if (st.total > 0) (st.bytes.toDouble() / st.total * durationSec).toLong() else 0
                        statusText = String.format(
                            Locale.US, "Downloading… %s/%s • %.1fs/s",
                            hms(dlSec), hms(durationSec), speed
                        )
                        if (!playStarted && fastPolls >= RATE_STREAK) play(st.path)
                    }
                    st.done -> {
                        play(st.path)
                        statusText = "Downloaded • " + sizeMb(st.total) + " • " + clock(st.ms)
                        reschedule = false
                    }
                    else -> {  // ERROR / UNKNOWN / network blip
                        if (st.state == "ERROR" || st.state == "UNKNOWN") {
                            statusText = "Download failed"
                            jobId = null
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
        val id = jobId
        val h = host
        val p = port
        jobId = null
        playStarted = false
        statusText = null
        handler.removeCallbacksAndMessages(null)
        if (id != null && h != null) {
            GlobalScope.launch(Dispatchers.IO) { MuxClient.cancel(h, p, id) }
        }
    }
}

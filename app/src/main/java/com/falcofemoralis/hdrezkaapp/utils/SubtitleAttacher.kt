package com.falcofemoralis.hdrezkaapp.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.preference.PreferenceManager
import org.peterbaldwin.client.android.vlcremote.R
import org.peterbaldwin.vlcremote.model.Hotkeys
import org.peterbaldwin.vlcremote.model.Server
import org.peterbaldwin.vlcremote.net.MediaServer
import org.peterbaldwin.vlcremote.rezka.DownloadPathClient

/**
 * Attaches a remote subtitle to the current VLC item: VLC can't fetch arbitrary subtitle
 * URLs, so the companion download helper (running on the VLC host) fetches it to a local
 * file first, then we addsubtitle that path and toggle the subtitle track on. Shared by
 * the hdrezka and YouTube tabs; uses the "subtitle server" settings.
 */
object SubtitleAttacher {
    private val handler = Handler(Looper.getMainLooper())

    /** @param name temp file name hint for the helper (extension matters, e.g. "English.vtt"). */
    @JvmStatic
    fun attach(context: Context, server: MediaServer, authority: String, subUrl: String, name: String) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean("hdrezka_sub_server_enabled", true)) {
            return
        }
        val host = prefs.getString("hdrezka_sub_server_host", null)?.takeIf { it.isNotBlank() }
            ?: Server.fromKey(authority).host
        val port = prefs.getString("hdrezka_sub_server_port", null)?.toIntOrNull() ?: 3900
        DownloadPathClient.requestTempPath(
            host, port, subUrl, name,
            object : DownloadPathClient.Callback {
                override fun onSuccess(tempPath: String) {
                    server.status().command.input.subtitles(tempPath)
                    handler.postDelayed({
                        server.status().command.key(Hotkeys.SUBTITLE_TRACK)
                    }, 3000)
                }

                override fun onError(e: Exception, serverBody: String?) {
                    Toast.makeText(context, context.getString(R.string.vlc_subtitle_failed), Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

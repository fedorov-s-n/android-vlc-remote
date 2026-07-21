package org.peterbaldwin.vlcremote.model

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Per-server configuration for the companion vlc-helper.py running on the VLC host (subtitle
 * download for the rezka tab, download+mux for seekable YouTube playback).
 *
 * Stored per server (keyed by its host:port) and edited inside the "Edit VLC Server" dialog.
 * Single source of truth for "is the helper usable for this server?": it must be enabled AND have
 * a valid host + port. When [resolve] returns null the callers must skip the helper entirely —
 * rezka/YouTube then play without subtitles, and YouTube plays the direct stream without seeking.
 */
object HelperConfig {
    private const val KEY_ENABLED = "helper_enabled#"
    private const val KEY_HOST = "helper_host#"
    private const val KEY_PORT = "helper_port#"
    const val DEFAULT_PORT = 3900

    data class Config(val host: String, val port: Int)

    /** Stable id for a server (its host:port), so config survives nickname/response-code edits. */
    private fun idFor(serverKeyOrAuthority: String?): String? =
        serverKeyOrAuthority?.let { runCatching { Server.fromKey(it)?.hostAndPort }.getOrNull() }

    /**
     * @param authority the VLC server key (as passed around the app); its host is used when no
     *   explicit helper host is set.
     * @return the resolved host/port, or null if the helper is disabled or misconfigured.
     */
    @JvmStatic
    fun resolve(context: Context, authority: String?): Config? {
        val id = idFor(authority) ?: return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (!prefs.getBoolean(KEY_ENABLED + id, true)) return null
        val host = prefs.getString(KEY_HOST + id, null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: runCatching { Server.fromKey(authority).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        val port = prefs.getString(KEY_PORT + id, null)?.trim()?.toIntOrNull() ?: DEFAULT_PORT
        if (port !in 1..65535) return null
        return Config(host, port)
    }

    @JvmStatic
    fun isUsable(context: Context, authority: String?): Boolean = resolve(context, authority) != null

    // ---- Read/write for the Edit VLC Server dialog (keyed by the server's host:port) ----

    @JvmStatic
    fun isEnabled(context: Context, hostAndPort: String): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED + hostAndPort, true)

    @JvmStatic
    fun getHost(context: Context, hostAndPort: String): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_HOST + hostAndPort, "") ?: ""

    @JvmStatic
    fun getPort(context: Context, hostAndPort: String): String =
        PreferenceManager.getDefaultSharedPreferences(context).getString(KEY_PORT + hostAndPort, "") ?: ""

    /** Persists the helper settings for one server (identified by host:port). */
    @JvmStatic
    fun save(context: Context, hostAndPort: String, enabled: Boolean, host: String, port: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(KEY_ENABLED + hostAndPort, enabled)
            .putString(KEY_HOST + hostAndPort, host.trim())
            .putString(KEY_PORT + hostAndPort, port.trim())
            .apply()
    }

    /** Drops the helper settings for a server that was renamed away / forgotten. */
    @JvmStatic
    fun clear(context: Context, hostAndPort: String) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .remove(KEY_ENABLED + hostAndPort)
            .remove(KEY_HOST + hostAndPort)
            .remove(KEY_PORT + hostAndPort)
            .apply()
    }
}

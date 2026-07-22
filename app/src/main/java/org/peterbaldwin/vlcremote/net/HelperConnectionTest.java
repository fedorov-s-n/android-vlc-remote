package org.peterbaldwin.vlcremote.net;

import android.content.Context;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.peterbaldwin.client.android.vlcremote.R;

/**
 * Reachability + credentials probe for the companion vlc-helper.py, analogous to
 * {@link ServerConnectionTest} for the VLC server. Call {@link #probe} off the main thread
 * (hits GET /ping, which requires auth when the helper is configured with credentials) and turn
 * its result into a one-line message with {@link #describe}.
 */
public final class HelperConnectionTest {

    private HelperConnectionTest() {
    }

    /** Blocking reachability + auth check. Returns the HTTP status code, or -1 on I/O failure. */
    public static int probe(String host, int port, String authHeader) {
        if (host == null || host.trim().isEmpty()) {
            return -1;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host.trim() + ":" + port + "/ping");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setUseCaches(false);
            if (authHeader != null) {
                conn.setRequestProperty("Authorization", authHeader);
            }
            return conn.getResponseCode();
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** One-line, human-readable result for a {@link #probe} status code. */
    public static String describe(Context context, int result) {
        switch (result) {
            case HttpURLConnection.HTTP_OK:
                return context.getString(R.string.helper_ok);
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                return context.getString(R.string.helper_unauthorized);
            default:
                return context.getString(R.string.helper_error);
        }
    }
}

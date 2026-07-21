package org.peterbaldwin.vlcremote.net;

import android.content.Context;
import android.os.AsyncTask;
import android.widget.Toast;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.peterbaldwin.client.android.vlcremote.R;

/**
 * Tests reachability + credentials of the companion vlc-helper.py, analogous to
 * {@link ServerConnectionTest} for the VLC server. Hits GET /ping (which requires auth when
 * the helper is configured with credentials) and toasts the outcome.
 */
public class HelperConnectionTest extends AsyncTask<Void, Void, Integer> {

    private final Context context;
    private final String host;
    private final int port;
    private final String authHeader;

    public HelperConnectionTest(Context context, String host, int port, String authHeader) {
        this.context = context.getApplicationContext();
        this.host = host;
        this.port = port;
        this.authHeader = authHeader;
    }

    @Override
    protected Integer doInBackground(Void... voids) {
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

    @Override
    protected void onPostExecute(Integer result) {
        switch (result) {
            case HttpURLConnection.HTTP_OK:
                Toast.makeText(context, R.string.helper_ok, Toast.LENGTH_SHORT).show();
                break;
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                Toast.makeText(context, R.string.helper_unauthorized, Toast.LENGTH_SHORT).show();
                break;
            default:
                Toast.makeText(context, R.string.helper_error, Toast.LENGTH_SHORT).show();
        }
    }
}

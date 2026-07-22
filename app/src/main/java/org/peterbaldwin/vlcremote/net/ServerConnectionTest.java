/*-
 *  Copyright (C) 2013 Sam Malone
 *  
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.peterbaldwin.vlcremote.net;

import android.content.Context;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import org.apache.http.Header;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.protocol.HTTP;
import org.peterbaldwin.client.android.vlcremote.R;
import org.peterbaldwin.vlcremote.model.Server;

/**
 * Reachability + credentials probe for a VLC server. Call {@link #probe} off the main thread
 * and turn its result into a one-line message with {@link #describe}.
 *
 * @author Sam Malone
 */
public final class ServerConnectionTest {

    private final static String TEST_PATH = "/requests/status.xml";

    private ServerConnectionTest() {
    }

    /** Blocking reachability + auth check. Returns the HTTP status code, or -1 on I/O failure. */
    public static int probe(Server server) {
        if (server == null) {
            return -1;
        }
        try {
            URL url = new URL("http://" + server.getUri().getAuthority() + TEST_PATH);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            // Without a read timeout, a server that accepts the socket but never responds hangs
            // this call forever.
            connection.setReadTimeout(2000);
            try {
                Header auth = BasicScheme.authenticate(new UsernamePasswordCredentials(server.getUser(), server.getPassword()), HTTP.UTF_8, false);
                connection.setRequestProperty(auth.getName(), auth.getValue());
                return connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        } catch (IOException ex) {
            return -1;
        }
    }

    /** One-line, human-readable result for a {@link #probe} status code. */
    public static String describe(Context context, int result) {
        switch (result) {
            case HttpURLConnection.HTTP_UNAUTHORIZED:
                return context.getString(R.string.server_unauthorized);
            case HttpURLConnection.HTTP_FORBIDDEN:
                return context.getString(R.string.server_forbidden);
            case HttpURLConnection.HTTP_OK:
                return context.getString(R.string.server_ok);
            default:
                return context.getString(R.string.server_error);
        }
    }
}

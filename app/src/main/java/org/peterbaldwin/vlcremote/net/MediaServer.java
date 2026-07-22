/*-
 *  Copyright (C) 2011 Peter Baldwin   
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

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.io.IOException;
import java.net.ContentHandler;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.apache.http.Header;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.protocol.HTTP;
import org.peterbaldwin.vlcremote.intent.Intents;
import org.peterbaldwin.vlcremote.model.Directory;
import org.peterbaldwin.vlcremote.model.Playlist;
import org.peterbaldwin.vlcremote.model.Remote;
import org.peterbaldwin.vlcremote.model.Server;
import org.peterbaldwin.vlcremote.model.Status;
import org.peterbaldwin.vlcremote.net.json.JsonStatusContentHandler;
import org.peterbaldwin.vlcremote.net.xml.XmlDirectoryContentHandler;
import org.peterbaldwin.vlcremote.net.xml.XmlPlaylistContentHandler;
import org.peterbaldwin.vlcremote.net.xml.XmlStatusContentHandler;
import org.peterbaldwin.vlcremote.service.StatusService;

public final class MediaServer {

    private static final String TAG = "VLC";

    private final Context mContext;

    private final String mAuthority;

    public MediaServer(Context context, String authority) {
        mContext = context;
        Server s = Server.fromKey(authority + "#200;");
        mAuthority = s.getUri().getEncodedAuthority();
    }

    public MediaServer(Context context, Uri data) {
        mContext = context;
        mAuthority = data.getEncodedAuthority();
    }

    public String getAuthority() {
        return mAuthority;
    }

    public StatusRequest status() {
        return new StatusRequest(mContext, mAuthority);
    }

    public StatusRequest status(Uri uri) {
        return new StatusRequest(mContext, uri);
    }

    public PlaylistRequest playlist(String search) {
        return new PlaylistRequest(mContext, mAuthority, search);
    }

    public BrowseRequest browse(String dir) {
        return new BrowseRequest(mContext, mAuthority, dir);
    }

    public ImageRequest image(Uri uri) {
        return new ImageRequest(mContext, uri);
    }

    public ImageRequest art() {
        return new ImageRequest(mContext, mAuthority);
    }

    static class Request {

        /**
         * Time to wait between network requests when sending multiple commands.
         */
        protected static final int DELAY = 500;

        /**
         * Time to wait (ms) for a request to complete before timing out.
         */
        protected static final int TIMEOUT = 8000;

        private final Context mContext;

        private final Uri mUri;

        protected int mFlags;

        protected boolean mNotifyPlaylist;

        protected long mDelay;

        protected Request(Context context, String authority, String path) {
            mContext = context;
            mUri = Uri.parse("http://" + authority + path);
        }

        protected Request(Context context, Uri uri) {
            mContext = context;
            mUri = uri;
        }

        protected final Intent intent(String encodedQuery) {
            Intent intent = new Intent(Intents.ACTION_STATUS);
            intent.setClass(mContext, StatusService.class);
            Uri data = mUri.buildUpon().encodedQuery(encodedQuery).build();
            intent.setData(data);
            intent.putExtra(Intents.EXTRA_FLAGS, mFlags);
            return intent;
        }

        protected final PendingIntent pending(Intent intent) {
            return PendingIntent.getService(mContext, 0, intent, pendingIntentFlags());
        }

        // API 31+ requires an explicit mutability flag or PendingIntent.get* throws
        // IllegalArgumentException; FLAG_IMMUTABLE exists from API 23 (minSdk is 21).
        private static int pendingIntentFlags() {
            return android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
        }

        protected final void start(Intent intent) {
            if (mDelay == 0L) {
                try {
                    mContext.startService(intent);
                } catch (IllegalStateException e) {
                    // Android 8+ forbids starting this background service from the background
                    // (e.g. the phone-state receiver, or a widget/alarm refresh). Fire the
                    // request's HTTP directly on a worker thread so the command still reaches VLC;
                    // the status broadcast / widget refresh the service would have produced is
                    // skipped here (a foreground poll refreshes the UI next time).
                    fireDirect(intent.getData());
                }
            } else {
                Object service = mContext.getSystemService(Context.ALARM_SERVICE);
                AlarmManager manager = (AlarmManager) service;
                long triggerAtTime = SystemClock.elapsedRealtime() + mDelay;
                int requestCode = (int) triggerAtTime;
                PendingIntent op = PendingIntent.getService(mContext, requestCode, intent, pendingIntentFlags());
                manager.set(AlarmManager.ELAPSED_REALTIME, triggerAtTime, op);
            }
        }

        protected final void execute(String encodedQuery) {
            start(intent(encodedQuery));
        }

        /** Runs the request URL directly off the main thread (fire-and-forget), used as the
         *  fallback when the background service can't be started on Android 8+. */
        private void fireDirect(final Uri data) {
            if (data == null) {
                return;
            }
            new Thread(new Runnable() {
                @Override
                public void run() {
                    HttpURLConnection http = null;
                    try {
                        http = (HttpURLConnection) new URL(data.toString()).openConnection();
                        http.setConnectTimeout(TIMEOUT);
                        http.setReadTimeout(TIMEOUT);
                        String usernamePassword = data.getUserInfo();
                        if (usernamePassword != null) {
                            Header authorization = BasicScheme.authenticate(
                                    new UsernamePasswordCredentials(usernamePassword), HTTP.UTF_8, false);
                            http.setRequestProperty(authorization.getName(), authorization.getValue());
                        }
                        http.getInputStream().close();
                    } catch (Throwable tr) {
                        org.peterbaldwin.vlcremote.model.ErrorLog.log(
                                "VLC background request failed: " + data.getPath(), tr);
                    } finally {
                        if (http != null) {
                            http.disconnect();
                        }
                    }
                }
            }).start();
        }

        @SuppressWarnings("unchecked")
        protected final <T> Remote<T> load(ContentHandler handler) {
            String spec = mUri.toString();
            try {
                T data = (T) read(handler);
                return Remote.data(data);
            } catch (Throwable t) {
                Log.e(TAG, "Unable to load: " + spec, t);
                // Path only (no authority) so the log never carries the server password.
                org.peterbaldwin.vlcremote.model.ErrorLog.log("VLC request failed: " + mUri.getPath(), t);
                return Remote.error(t);
            }
        }

        @SuppressWarnings("unchecked")
        protected final <T> T read(ContentHandler handler) throws IOException {
            String spec = mUri.toString();
            URL url = new URL(spec);
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            http.setConnectTimeout(TIMEOUT);
            // Without a read timeout a server that connects but stalls mid-response hangs the
            // status/playlist/browse request forever (BitmapContentHandler already sets both).
            http.setReadTimeout(TIMEOUT);
            try {
                String usernamePassword = mUri.getUserInfo();
                if (usernamePassword != null) {
                    Header authorization = BasicScheme.authenticate(
                            new UsernamePasswordCredentials(usernamePassword), HTTP.UTF_8, false);
                    http.setRequestProperty(authorization.getName(), authorization.getValue());
                }
                return (T) handler.getContent(http);
            } finally {
                http.disconnect();
            }
        }
    }

    public static final class StatusRequest extends Request {
        
        private final boolean mUseXml = StatusService.USE_XML_STATUS;

        StatusRequest(Context context, String authority) {
            super(context, authority, String.format("/requests/status.%s", StatusService.USE_XML_STATUS ? "xml" : "json"));
        }

        public StatusRequest(Context context, Uri uri) {
            super(context, uri);
        }

        public final CommandInterface command = new CommandInterface();

        /**
         * Loads the server status synchronously.
         * @return Remote Status
         */
        public Remote<Status> load() {
            return load(mUseXml ? new XmlStatusContentHandler() : new JsonStatusContentHandler());
        }

        public Status read() throws IOException {
            return read(mUseXml ? new XmlStatusContentHandler() : new JsonStatusContentHandler());
        }

        /**
         * Loads the server status asynchronously.
         */
        public void get() {
            execute("");
        }

        public PendingIntent pendingGet() {
            return pending(intent(""));
        }

        public StatusRequest programmatic() {
            mFlags |= Intents.FLAG_PROGRAMMATIC;
            return this;
        }

        public StatusRequest onlyIfPlaying() {
            mFlags |= Intents.FLAG_ONLY_IF_PLAYING;
            return this;
        }

        public StatusRequest onlyIfPaused() {
            mFlags |= Intents.FLAG_ONLY_IF_PAUSED;
            return this;
        }

        public StatusRequest setResumeOnIdle() {
            mFlags |= Intents.FLAG_SET_RESUME_ON_IDLE;
            return this;
        }

        public final class CommandInterface {

            public final InputInterface input = new InputInterface();

            public class InputInterface {

                private InputInterface() {
                }

                public void play(String input) {
                    List<String> options = Collections.emptyList();
                    play(input, options);
                }

                public void play(String input, String... options) {
                    play(input, Arrays.asList(options));
                }

                public void play(String input, List<String> options) {
                    // Options are appended to the MRL
                    // for VLC 1.1.10 and earlier
                    for (String option : options) {
                        input += " " + option;
                    }
                    String query = "command=in_play&input=" + Uri.encode(input);

                    // Options are appended as query parameters
                    // for VLC 1.1.11 and later
                    for (String option : options) {
                        query += ("&option=" + Uri.encode(option));
                    }

                    execute(query);
                }

                /**
                 * Plays {@code input} and forces its displayed title via {@code :meta-title}.
                 * The MRL is kept clean (title goes only in a separate, URL-encoded
                 * {@code option} parameter, VLC 1.1.11+) so a title with spaces/special
                 * characters can't corrupt the MRL and break playback.
                 */
                public void playWithMetaTitle(String input, String metaTitle) {
                    String query = "command=in_play&input=" + Uri.encode(input);
                    if (metaTitle != null && !metaTitle.isEmpty()) {
                        query += "&option=" + Uri.encode(":meta-title=" + metaTitle);
                    }
                    execute(query);
                }

                /**
                 * Plays {@code input} with each of {@code options} passed as a URL-encoded
                 * {@code option} parameter (never appended to the MRL), so option values with
                 * spaces/special characters can't corrupt the stream URL.
                 */
                public void playWithOptions(String input, List<String> options) {
                    String query = "command=in_play&input=" + Uri.encode(input);
                    if (options != null) {
                        for (String option : options) {
                            if (option != null && !option.isEmpty()) {
                                query += "&option=" + Uri.encode(option);
                            }
                        }
                    }
                    execute(query);
                }


                public void subtitles(String input) {
                    String query = "command=addsubtitle&val=" + Uri.encode(input);
                    // Log.e("VLC", "subtitles command: " + query);
                    execute(query);
                }

                public void enqueue(String input) {
                    execute("command=in_enqueue&input=" + Uri.encode(input));
                }
            }

            public final PlaybackInterface playback = new PlaybackInterface();

            public class PlaybackInterface {

                private PlaybackInterface() {
                }

                public void play(int id) {
                    execute("command=pl_play&id=" + id);
                }

                public void pause() {
                    execute("command=pl_pause");
                }

                public PendingIntent pendingPause() {
                    return pending(intent("command=pl_pause"));
                }

                public void stop() {
                    execute("command=pl_stop");
                }

                public void next() {
                    execute("command=pl_next");
                }

                public PendingIntent pendingNext() {
                    return pending(intent("command=pl_next"));
                }

                public void previous() {
                    execute("command=pl_previous");
                }
                
                public PendingIntent pendingPrevious() {
                    return pending(intent("command=pl_previous"));
                }

                public void chapter(int chapter) {
                    execute("command=chapter&val=" + chapter);
                }

                /**
                 * 
                 * @param delay delay in milliseconds
                 */
                public void subtitleDelay(float delay) {
                    execute("command=subdelay&val=" + (delay / 1000));
                }

                /**
                 * 
                 * @param delay delay in milliseconds
                 */
                public void audioDelay(float delay) {
                    execute("command=audiodelay&val=" + (delay / 1000));
                }

                public void delete(int id) {
                    mNotifyPlaylist = true;
                    execute("command=pl_delete&id=" + id);
                }

                public void empty() {
                    execute("command=pl_empty");
                }

                public void sort(int sort, int order) {
                    execute("command=pl_sort&id=" + order + "&val=" + sort);
                }

                public void shuffle() {
                    execute("command=pl_random");
                }

                public PlaybackInterface loop() {
                    execute("command=pl_loop");
                    mDelay += DELAY;
                    return this;
                }

                public PlaybackInterface repeat() {
                    execute("command=pl_repeat");
                    mDelay += DELAY;
                    return this;
                }

                public void random() {
                    execute("command=pl_random");
                }

                public void sd(String value) {
                    execute("command=pl_sd&val=" + value);
                }
            }

            public void volume(int value) {
                execute("command=volume&val=" + value);
            }

            public void adjustVolume(int amount) {
                String val = amount < 0 ? Integer.toString(amount) : "+" + amount;
                execute("command=volume&val=" + Uri.encode(val));
            }

            public void volumeDown() {
                execute("command=volume&val=-20");
            }

            public void volumeUp() {
                execute("command=volume&val=%2B20");
            }

            public void seek(String pos) {
                execute("command=seek&val=" + pos);
            }

            public void key(String keycode) {
                // Use hotkey name (without the "key-" part)
                // as the argument to simulate a hotkey press
                execute("command=key&val=" + keycode);
            }

            public void fullscreen() {
                execute("command=fullscreen");
            }

            public void snapshot() {
                execute("command=snapshot");
            }
        }
    }

    public static final class PlaylistRequest extends Request {

        PlaylistRequest(Context context, String authority, String search) {
            super(context, authority, "/requests/playlist.xml?search=" + Uri.encode(search));
        }

        public Remote<Playlist> load() {
            return load(new XmlPlaylistContentHandler());
        }
    }

    public static final class BrowseRequest extends Request {

        BrowseRequest(Context context, String authority, String dir) {
            super(context, authority, "/requests/browse.xml?dir=" + Uri.encode(dir));
        }

        public Remote<Directory> load() {
            return load(new XmlDirectoryContentHandler());
        }
    }

    public static final class ImageRequest extends Request {
        ImageRequest(Context context, Uri uri) {
            super(context, uri);
        }

        ImageRequest(Context context, String authority) {
            super(context, authority, "/art");
        }

        public Bitmap read() throws IOException {
            return read(new BitmapContentHandler());
        }

        public Remote<Bitmap> load() throws IOException {
            return load(new BitmapContentHandler());
        }
    }
}

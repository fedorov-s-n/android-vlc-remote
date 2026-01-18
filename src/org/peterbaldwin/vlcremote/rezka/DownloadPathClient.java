package org.peterbaldwin.vlcremote.rezka;
import android.os.AsyncTask;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

public class DownloadPathClient {

    public interface Callback {
        void onSuccess(String tempPath);
        void onError(Exception e, String serverBody);
    }

    public static void requestTempPath(
            final String serverHost, // например "10.0.2.2" или "192.168.1.50"
            final int serverPort,     // 3900
            final String fileUrl,     // url который сервер скачает
            final String name,        // имя (name=...)
            final Callback callback
    ) {
        new AsyncTask<Void, Void, Result>() {
            @Override
            protected Result doInBackground(Void... voids) {
                HttpURLConnection conn = null;
                String body = null;
                try {
                    String qUrl = URLEncoder.encode(fileUrl, "UTF-8");
                    String qName = URLEncoder.encode(name, "UTF-8");

                    String full = "http://" + serverHost + ":" + serverPort + "/?url=" + qUrl + "&name=" + qName;
                    URL url = new URL(full);

                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(10_000);
                    conn.setReadTimeout(60_000);
                    conn.setUseCaches(false);

                    int code = conn.getResponseCode();
                    InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    body = readAll(is);

                    if (code >= 200 && code < 300) {
                        // Сервер возвращает путь как plain text с \n — уберём пробелы/переводы строк
                        String path = body == null ? "" : body.trim();
                        if (path.isEmpty()) {
                            throw new IOException("Empty response body");
                        }
                        return Result.ok(path);
                    } else {
                        return Result.err(new IOException("HTTP " + code), body);
                    }
                } catch (Exception e) {
                    return Result.err(e, body);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }

            @Override
            protected void onPostExecute(Result r) {
                if (callback == null) return;
                if (r.error == null) callback.onSuccess(r.value);
                else callback.onError(r.error, r.serverBody);
            }
        }.execute();
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return null;
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static class Result {
        final String value;
        final Exception error;
        final String serverBody;

        private Result(String value, Exception error, String serverBody) {
            this.value = value;
            this.error = error;
            this.serverBody = serverBody;
        }

        static Result ok(String v) { return new Result(v, null, null); }
        static Result err(Exception e, String body) { return new Result(null, e, body); }
    }
}

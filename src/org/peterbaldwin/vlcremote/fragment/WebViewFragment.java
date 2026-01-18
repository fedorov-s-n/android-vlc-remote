package org.peterbaldwin.vlcremote.fragment;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Toast;

import org.peterbaldwin.client.android.vlcremote.R;
import org.peterbaldwin.vlcremote.intent.Intents;
import org.peterbaldwin.vlcremote.model.Preferences;
import org.peterbaldwin.vlcremote.model.Server;
import org.peterbaldwin.vlcremote.model.Status;
import org.peterbaldwin.vlcremote.model.Track;
import org.peterbaldwin.vlcremote.net.MediaServer;
import org.peterbaldwin.vlcremote.rezka.DownloadPathClient;
import org.peterbaldwin.vlcremote.rezka.RezkaStreamAddress;
import org.peterbaldwin.vlcremote.rezka.RezkaSubtitle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class WebViewFragment extends Fragment {
    private BroadcastReceiver mStatusReceiver;
    private WebView webView;
    private List<WaitingSubtitles> swl = new CopyOnWriteArrayList<>();

    public WebViewFragment() {
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            ViewGroup container,
            Bundle savedInstanceState
    ) {
        return inflater.inflate(R.layout.web_view_fragment, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        webView = (WebView) view.findViewById(R.id.webView);

        webView.setWebViewClient(new WebViewClient());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);

        if (savedInstanceState == null) {
            webView.loadUrl("https://rezka.ag");
        }

        Button btnWatch = (Button) view.findViewById(R.id.btnWatch);
        btnWatch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (webView != null) {

                    webView.evaluateJavascript("CDNPlayerInfo.streams", new ValueCallback<String>() {
                        @Override
                        public void onReceiveValue(String value) {
                            String input = value !=null && value.length() > 2 ? value.trim().substring(1, value.length() - 1) : null;
                            List<RezkaStreamAddress> streams = RezkaStreamAddress.parse(input);
                            if(streams == null) {
                                Toast.makeText(getActivity(), "Cannot receive streams from that response: " + input, Toast.LENGTH_LONG).show();
                                return;
                            }

                            PopupMenu menu = new PopupMenu(v.getContext(), v);

                            int itemId = 0;
                            for(RezkaStreamAddress stream: streams) {
                                menu.getMenu().add(0, itemId, itemId, stream.getQuality());
                                itemId ++;
                            }

                            menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                                @Override
                                public boolean onMenuItemClick(MenuItem item) {
                                    RezkaStreamAddress stream = streams.get(item.getItemId());

                                    Context context = getActivity();
                                    Preferences preferences = Preferences.get(context);
                                    String authority = preferences.getAuthority();
                                    if (authority != null) {
                                        MediaServer server = new MediaServer(context, authority);
                                        server.status().command.input.play(stream.getMp4());

                                        webView.evaluateJavascript("CDNPlayerInfo.subtitle", new ValueCallback<String>() {
                                            @Override
                                            public void onReceiveValue(String value) {
                                                String input = value !=null && value.length() > 2 ? value.trim().substring(1, value.length() - 1) : null;
                                                List<RezkaSubtitle> subtitles = RezkaSubtitle.parse(input);

                                                String serverHost = Server.fromKey(authority).getHost();
                                                int port = 3900; // so far hard coded

                                                if(subtitles ==null) {
                                                    Toast.makeText(getActivity(), "Cannot receive subtitles from that response: " + input, Toast.LENGTH_LONG).show();
                                                }else if (!subtitles.isEmpty()){
                                                    for (RezkaSubtitle rs: subtitles) {
                                                        String name = rs.getLabel()+"."+rs.getExtension();
                                                        DownloadPathClient.requestTempPath(serverHost, port, rs.getLink(), name, new DownloadPathClient.Callback() {
                                                            @Override
                                                            public void onSuccess(String tempPath) {
                                                                server.status().command.input.subtitles(tempPath);
                                                              }

                                                            @Override
                                                            public void onError(Exception e, String serverBody) {
                                                                Log.e("VLC", "Error: " + e + " body=" + serverBody);
                                                              }
                                                        });
                                                    }
                                                }
                                            }
                                        });
                                    }

                                    return true;
                                }
                            });

                            menu.show();
                        }
                    });

                }
            }
        });
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }

    public boolean handleBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        mStatusReceiver = new StatusReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intents.ACTION_STATUS);
        getActivity().registerReceiver(mStatusReceiver, filter);
    }

    void onTrackChanged(Track track) {
//        Toast.makeText(getActivity(), "Track url: " + track.getTrack(), Toast.LENGTH_SHORT).show();
//        List<WaitingSubtitles> toRemove = new ArrayList<>(1);
//        for (WaitingSubtitles ws: swl) {
//            if(Objects.equals(track.getUrl(), ws.stream.getMp4())) {
//                toRemove.add(ws);
//            }
//        }
//        if (!toRemove.isEmpty()) {
//            Context context = getActivity();
//            Preferences preferences = Preferences.get(context);
//            String authority = preferences.getAuthority();
//            if (authority != null) {
//                MediaServer server = new MediaServer(context, authority);
//                for(WaitingSubtitles ws: toRemove) {
//                    server.status().command.input.play();
//                }
//                swl.removeAll(toRemove);
//            }
//        }
    }

    private class StatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intents.ACTION_STATUS.equals(action)) {
                Status status = (Status) intent.getSerializableExtra(Intents.EXTRA_STATUS);
                onTrackChanged(status.getTrack());
            }
        }
    }

    private static class WaitingSubtitles {
        private final RezkaStreamAddress stream;
        private final List<RezkaSubtitle> subtitle;
        private final long startTime;

        private WaitingSubtitles(RezkaStreamAddress stream, List<RezkaSubtitle> subtitle, long startTime) {
            this.stream = stream;
            this.subtitle = subtitle;
            this.startTime = startTime;
        }
    }
}
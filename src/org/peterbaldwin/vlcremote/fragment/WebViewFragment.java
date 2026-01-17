package org.peterbaldwin.vlcremote.fragment;

import android.os.Bundle;
import android.support.v4.app.Fragment;
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
import org.peterbaldwin.vlcremote.rezka.RezkaStreamAddress;

import java.util.List;
import java.util.Map;

public class WebViewFragment extends Fragment {

    private WebView webView;

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

        // чтобы ссылки открывались внутри WebView, а не в браузере
        webView.setWebViewClient(new WebViewClient());

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setUseWideViewPort(true);        // учитывать meta viewport
        s.setLoadWithOverviewMode(true);   // подгонять под экран
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
                            String input = value.trim().substring(1, value.length() - 2);
                            List<RezkaStreamAddress> streams = RezkaStreamAddress.parse(input);

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
                                    Toast.makeText(getActivity(), "Selected: " + stream.getQuality(), Toast.LENGTH_SHORT).show();
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
}
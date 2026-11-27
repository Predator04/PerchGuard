package com.perchguard.parent;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Child location on a map — WebView pointed at the relay's /map route, which
 * serves a self-contained Leaflet page tiled from OpenStreetMap.
 *
 * Why a WebView and not a native MapView? MapView would pull in Google Play
 * Services + a Maps API key, which doesn't fit a self-hosted off-store app.
 * Leaflet+OSM works on any Android device without external accounts.
 */
public class LocationActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout top = Ui.row(this);
        TextView t = Ui.title(this, "Location");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tLp);
        android.widget.Button back = Ui.button(this, "Back");
        back.setOnClickListener(v -> finish());
        top.addView(t);
        top.addView(back);
        root.addView(top, Ui.lpMatch());

        TextView blurb = Ui.muted(this, "Latest fix from the child device.");
        LinearLayout.LayoutParams bLp = Ui.lpMatch();
        bLp.topMargin = Ui.dp(this, 6);
        bLp.bottomMargin = Ui.dp(this, 8);
        blurb.setLayoutParams(bLp);
        root.addView(blurb);

        WebView web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req,
                                        WebResourceError err) {
                Toast.makeText(LocationActivity.this,
                        "Can't load map (relay unreachable?)",
                        Toast.LENGTH_LONG).show();
            }
        });
        LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        web.setLayoutParams(wLp);
        root.addView(web);

        String url = Prefs.url(this) + "/map?token="
            + Uri.encode(Prefs.token(this));
        web.loadUrl(url);

        setContentView(root);
    }
}

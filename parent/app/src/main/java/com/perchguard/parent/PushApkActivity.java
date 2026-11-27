package com.perchguard.parent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Push APK to child. Two paths:
 *   1. Pick a local APK (ACTION_GET_CONTENT) and POST its body to /api/apk.
 *   2. Paste a URL; POST {"url":"..."} as JSON — relay downloads and forwards.
 */
public class PushApkActivity extends Activity {
    private static final int REQ_PICK = 42;
    private static final MediaType APK = MediaType.get("application/vnd.android.package-archive");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private TextView status;
    private EditText urlField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 14);
        sv.setPadding(p, p, p, p);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = Ui.row(this);
        TextView t = Ui.title(this, "Push APK to child");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tLp);
        Button back = Ui.button(this, "Back");
        back.setOnClickListener(v -> finish());
        header.addView(t);
        header.addView(back);
        root.addView(header, Ui.lpMatch());

        TextView blurb = Ui.muted(this,
            "Upload an APK from this phone to the relay, then the child "
            + "downloads and installs it. Used for the initial PerchGuard child "
            + "install or any other APK.");
        LinearLayout.LayoutParams bLp = Ui.lpMatch();
        bLp.topMargin = Ui.dp(this, 6);
        blurb.setLayoutParams(bLp);
        root.addView(blurb);

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams cLp = Ui.lpMatch();
        cLp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(cLp);

        Button pick = Ui.primaryButton(this, "Pick APK from this phone");
        pick.setOnClickListener(v -> openPicker());
        card.addView(pick);

        TextView or = Ui.muted(this, "— or —");
        LinearLayout.LayoutParams oLp = Ui.lpMatch();
        oLp.topMargin = Ui.dp(this, 10);
        or.setLayoutParams(oLp);
        card.addView(or);

        urlField = Ui.edit(this, "https://example.com/app.apk", null, false);
        LinearLayout.LayoutParams uLp = Ui.lpMatch();
        uLp.topMargin = Ui.dp(this, 6);
        urlField.setLayoutParams(uLp);
        card.addView(urlField);

        Button sendUrl = Ui.button(this, "Send URL to relay");
        LinearLayout.LayoutParams sLp = Ui.lpMatch();
        sLp.topMargin = Ui.dp(this, 8);
        sendUrl.setLayoutParams(sLp);
        sendUrl.setOnClickListener(v -> sendUrlToRelay());
        card.addView(sendUrl);

        root.addView(card);

        status = Ui.muted(this, "");
        LinearLayout.LayoutParams stLp = Ui.lpMatch();
        stLp.topMargin = Ui.dp(this, 12);
        status.setLayoutParams(stLp);
        root.addView(status);

        sv.addView(root);
        setContentView(sv);
    }

    private void openPicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("application/vnd.android.package-archive");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Pick APK"), REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        uploadApk(uri);
    }

    private void uploadApk(Uri uri) {
        status.setText("Reading APK…");
        new Thread(() -> {
            try {
                byte[] bytes = readAll(uri);
                runOnUiThread(() -> status.setText("Uploading " + bytes.length / 1024 + " KB…"));
                postBytesToRelay(bytes);
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
            }
        }).start();
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private void postBytesToRelay(byte[] bytes) {
        String base = Prefs.url(this);
        String tok = Prefs.token(this);
        HttpUrl url = HttpUrl.parse(base + "/api/apk");
        if (url == null) { runOnUiThread(() -> status.setText("Bad relay URL")); return; }
        Request req = new Request.Builder()
            .url(url.newBuilder().addQueryParameter("token", tok).build())
            .post(RequestBody.create(bytes, APK))
            .build();
        OkHttpClient c = new OkHttpClient();
        c.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> status.setText("Upload failed: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try {
                    int code = resp.code();
                    String body = resp.body() != null ? resp.body().string() : "";
                    runOnUiThread(() -> status.setText("Relay: HTTP " + code
                        + " — child will install. " + body));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Response error: " + e.getMessage()));
                } finally { resp.close(); }
            }
        });
    }

    private void sendUrlToRelay() {
        String u = urlField.getText().toString().trim();
        if (u.isEmpty()) { status.setText("Enter a URL first."); return; }
        String base = Prefs.url(this);
        String tok = Prefs.token(this);
        HttpUrl url = HttpUrl.parse(base + "/api/apk");
        if (url == null) { status.setText("Bad relay URL"); return; }
        JSONObject body = new JSONObject();
        try { body.put("url", u); } catch (Exception ignored) {}
        Request req = new Request.Builder()
            .url(url.newBuilder().addQueryParameter("token", tok).build())
            .post(RequestBody.create(body.toString(), JSON))
            .build();
        status.setText("Asking relay to fetch " + u + " …");
        new OkHttpClient().newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> status.setText("Failed: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try {
                    int code = resp.code();
                    String s = resp.body() != null ? resp.body().string() : "";
                    runOnUiThread(() -> status.setText("Relay: HTTP " + code + " " + s));
                } catch (Exception e) {
                    runOnUiThread(() -> status.setText("Response error: " + e.getMessage()));
                } finally { resp.close(); }
            }
        });
    }
}

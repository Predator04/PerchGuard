package com.perchguard.parent;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Self-updater. Queries GitHub releases/latest; if its tag parses to a higher
 * versionCode than installed, downloads the matching asset and launches the
 * system installer via FileProvider. Preserves SharedPreferences (no uninstall).
 */
public final class UpdateChecker {
    private static final String TAG = "PerchGuard.Update";
    private static final String API =
        "https://api.github.com/repos/" + BuildConfig.GH_REPO + "/releases/latest";

    public interface Listener {
        void onNoUpdate(int installed, int latest);
        void onUpdateAvailable(int latest, String tag, String assetUrl, String assetName);
        void onError(String message);
    }

    private final Context ctx;
    private final OkHttpClient http;
    private final Handler main = new Handler(Looper.getMainLooper());

    public UpdateChecker(Context c) {
        this.ctx = c.getApplicationContext();
        this.http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    }

    public void check(Listener cb) {
        Request req = new Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                main.post(() -> cb.onError("network: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try (ResponseBody body = resp.body()) {
                    if (!resp.isSuccessful() || body == null) {
                        main.post(() -> cb.onError("HTTP " + resp.code()));
                        return;
                    }
                    JSONObject o = new JSONObject(body.string());
                    String tag = o.optString("tag_name", "");
                    int latest = parseVersionCode(tag);
                    int installed = BuildConfig.VERSION_CODE;
                    if (latest <= installed) {
                        main.post(() -> cb.onNoUpdate(installed, latest));
                        return;
                    }
                    JSONArray assets = o.optJSONArray("assets");
                    String url = null, name = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.optJSONObject(i);
                            if (a == null) continue;
                            String n = a.optString("name", "");
                            if (n.startsWith(BuildConfig.UPDATE_ASSET_PREFIX)
                                && n.endsWith(".apk")) {
                                url = a.optString("browser_download_url", "");
                                name = n;
                                break;
                            }
                        }
                    }
                    if (url == null || url.isEmpty()) {
                        main.post(() -> cb.onError("no matching APK asset in " + tag));
                        return;
                    }
                    final String fUrl = url, fName = name;
                    final int fLatest = latest;
                    main.post(() -> cb.onUpdateAvailable(fLatest, tag, fUrl, fName));
                } catch (Exception e) {
                    main.post(() -> cb.onError("parse: " + e.getMessage()));
                }
            }
        });
    }

    public void downloadAndInstall(String url, String name, Activity activity) {
        Toast.makeText(activity, "Downloading update…", Toast.LENGTH_SHORT).show();
        Request req = new Request.Builder().url(url).build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, java.io.IOException e) {
                main.post(() -> Toast.makeText(activity,
                    "Update download failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show());
            }
            @Override public void onResponse(Call call, Response resp) {
                try (ResponseBody body = resp.body()) {
                    if (!resp.isSuccessful() || body == null) {
                        main.post(() -> Toast.makeText(activity,
                            "Update HTTP " + resp.code(), Toast.LENGTH_LONG).show());
                        return;
                    }
                    File dir = new File(ctx.getFilesDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File out = new File(dir, name == null ? "update.apk" : name);
                    try (InputStream in = body.byteStream();
                         FileOutputStream fos = new FileOutputStream(out)) {
                        byte[] buf = new byte[32 * 1024];
                        int n;
                        while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    launchInstall(activity, out);
                } catch (Exception e) {
                    Log.w(TAG, "download/install", e);
                    main.post(() -> Toast.makeText(activity,
                        "Install failed: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    public static void launchInstall(Activity activity, File apk) {
        Uri uri = FileProvider.getUriForFile(activity,
            activity.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(activity, "No installer: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private static int parseVersionCode(String tag) {
        if (tag == null) return 0;
        String s = tag.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) end++;
        if (end == 0) return 0;
        try { return Integer.parseInt(s.substring(0, end)); }
        catch (NumberFormatException e) { return 0; }
    }
}

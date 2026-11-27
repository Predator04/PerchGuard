package com.perchguard.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads an APK from a URL and launches the system installer via FileProvider
 * + ACTION_VIEW. Runs off the main thread — pass a Context, not an Activity.
 *
 * Android 14 note: background intents to install are allowed when the caller
 * holds REQUEST_INSTALL_PACKAGES and uses FLAG_ACTIVITY_NEW_TASK; the user will
 * still see the system installer prompt (as expected for sideloads, no root).
 */
public final class InstallHelper {
    private static final String TAG = "PerchGuard.Install";

    public static void downloadAndInstall(Context ctx, String url) {
        OkHttpClient c = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
        Request req = new Request.Builder().url(url)
            .header("Authorization", "Bearer " + Prefs.authToken(ctx))
            .build();
        new Thread(() -> {
            try (Response resp = c.newCall(req).execute();
                 ResponseBody body = resp.body()) {
                if (!resp.isSuccessful() || body == null) {
                    Log.w(TAG, "download HTTP " + resp.code());
                    Events.ackAsync("install_apk", false, "http_" + resp.code());
                    return;
                }
                File dir = new File(ctx.getFilesDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                File apk = new File(dir, "incoming.apk");
                try (InputStream in = body.byteStream();
                     FileOutputStream fos = new FileOutputStream(apk)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
                }
                launchInstall(ctx, apk);
                Events.ackAsync("install_apk", true, null);
            } catch (Exception e) {
                Log.w(TAG, "install", e);
                Events.ackAsync("install_apk", false, e.getMessage());
            }
        }, "pg-install").start();
    }

    private static void launchInstall(Context ctx, File apk) {
        Uri uri = FileProvider.getUriForFile(ctx,
            ctx.getPackageName() + ".updates", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "launch", e);
        }
    }
}

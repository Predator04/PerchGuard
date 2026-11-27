package com.perchguard.app;

import android.app.Application;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Global uncaught-exception reporter: the real crash lands in logcat under
 * "PerchGuardCrash" AND is best-effort POSTed to the relay at /api/crash so a
 * parent can read it from anywhere. Fires on a background thread (crashes
 * can't do heavy work on the main thread) and always chains to the previous
 * default handler so Android's normal die-fast behavior still happens.
 */
public class App extends Application {

    private static final String TAG = "PerchGuardCrash";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev =
            Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                StringWriter sw = new StringWriter();
                throwable.printStackTrace(new PrintWriter(sw));
                String stack = "thread=" + thread.getName() + "\n" + sw.toString();
                Log.e(TAG, stack);
                postToRelay(stack);
            } catch (Throwable t) {
                Log.e(TAG, "reporter failed", t);
            } finally {
                if (prev != null) prev.uncaughtException(thread, throwable);
            }
        });
    }

    private void postToRelay(String stack) {
        final String url = crashUrl();
        if (url == null) return;
        final String token = Prefs.authToken(this);
        // Build JSON manually to avoid pulling in org.json from a crashing process.
        final String body = "{\"stack\":" + jsonQuote(stack)
            + ",\"version\":" + BuildConfig.VERSION_CODE + "}";
        Thread t = new Thread(() -> {
            try {
                OkHttpClient c = new OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .writeTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();
                Request.Builder rb = new Request.Builder().url(url)
                    .post(RequestBody.create(body, JSON));
                if (token != null && !token.isEmpty()) {
                    rb.header("Authorization", "Bearer " + token);
                }
                try (Response resp = c.newCall(rb.build()).execute()) {
                    Log.i(TAG, "crash posted: HTTP " + resp.code());
                }
            } catch (Throwable ignored) {}
        }, "pg-crash-report");
        t.setDaemon(true);
        t.start();
        try { t.join(4000); } catch (InterruptedException ignored) {}
    }

    /** Derive the relay HTTP base URL from the stored WS relay URL. */
    private String crashUrl() {
        String ws = Prefs.relayUrl(this);
        if (ws == null || ws.isEmpty()) return null;
        String http;
        if (ws.startsWith("wss://")) http = "https://" + ws.substring(6);
        else if (ws.startsWith("ws://")) http = "http://" + ws.substring(5);
        else return null;
        int slash = http.indexOf('/', http.indexOf("//") + 2);
        String base = slash > 0 ? http.substring(0, slash) : http;
        return base + "/api/crash";
    }

    private static String jsonQuote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 32);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

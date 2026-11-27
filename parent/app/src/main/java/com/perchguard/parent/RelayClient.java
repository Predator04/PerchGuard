package com.perchguard.parent;

import android.content.Context;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Thin wrapper over OkHttp for parent-side REST + WebSocket to the relay. */
public final class RelayClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final String baseUrl;   // https://host
    private final String wsBaseUrl; // wss://host
    private final String token;

    public RelayClient(Context c) {
        this(Prefs.url(c), Prefs.token(c));
    }

    public RelayClient(String url, String token) {
        this.baseUrl = url;
        this.wsBaseUrl = toWsBase(url);
        this.token = token == null ? "" : token;
        this.client = new OkHttpClient.Builder()
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // long-poll safe
                .connectTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public String baseUrl()  { return baseUrl; }
    public String token()    { return token; }

    public WebSocket openParentView(WebSocketListener listener) {
        HttpUrl url = HttpUrl.parse(wsBaseUrl + "/parent/view");
        if (url == null) throw new IllegalStateException("bad relay url: " + wsBaseUrl);
        HttpUrl withToken = url.newBuilder().addQueryParameter("token", token).build();
        Request req = new Request.Builder().url(withToken).build();
        return client.newWebSocket(req, listener);
    }

    public void getState(Callback cb) {
        HttpUrl url = HttpUrl.parse(baseUrl + "/api/state");
        if (url == null) { cb.onFailure(null, new IOException("bad url")); return; }
        Request req = new Request.Builder()
                .url(url.newBuilder().addQueryParameter("token", token).build())
                .get().build();
        client.newCall(req).enqueue(wrap(cb));
    }

    public void post(String path, JSONObject body, Callback cb) {
        HttpUrl url = HttpUrl.parse(baseUrl + path);
        if (url == null) { if (cb != null) cb.onFailure(null, new IOException("bad url")); return; }
        RequestBody rb = RequestBody.create(body == null ? "{}" : body.toString(), JSON);
        Request req = new Request.Builder()
                .url(url.newBuilder().addQueryParameter("token", token).build())
                .post(rb).build();
        client.newCall(req).enqueue(wrap(cb));
    }

    public void shutdown() {
        try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
        try { client.connectionPool().evictAll(); } catch (Exception ignored) {}
    }

    private static Callback wrap(Callback cb) {
        return cb != null ? cb : new Callback() {
            @Override public void onFailure(Call call, IOException e) {}
            @Override public void onResponse(Call call, Response resp) { resp.close(); }
        };
    }

    private static String toWsBase(String http) {
        if (http == null) return "";
        if (http.startsWith("https://")) return "wss://" + http.substring("https://".length());
        if (http.startsWith("http://"))  return "ws://"  + http.substring("http://".length());
        return http;
    }
}

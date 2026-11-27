package com.perchguard.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String FILE = "perchguard";
    public static final String KEY_RELAY_URL = "relay_url";
    public static final String KEY_POLICY_JSON = "policy_json";
    public static final String KEY_USAGE_DATE = "usage_date";
    public static final String KEY_USAGE_JSON = "usage_json";

    public static final String DEFAULT_RELAY_URL = "wss://cuisine-expectations-represented-cheaper.trycloudflare.com/kid/stream";
    public static final String KEY_AUTH_TOKEN = "auth_token";
    public static final String DEFAULT_AUTH_TOKEN = BuildConfig.AUTH_TOKEN;

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String relayUrl(Context ctx) {
        return get(ctx).getString(KEY_RELAY_URL, DEFAULT_RELAY_URL);
    }

    public static void setRelayUrl(Context ctx, String url) {
        get(ctx).edit().putString(KEY_RELAY_URL, url).apply();
    }

    public static String authToken(Context ctx) {
        return get(ctx).getString(KEY_AUTH_TOKEN, DEFAULT_AUTH_TOKEN);
    }

    public static void setAuthToken(Context ctx, String token) {
        get(ctx).edit().putString(KEY_AUTH_TOKEN, token).apply();
    }

    private Prefs() {}
}

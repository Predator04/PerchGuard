package com.perchguard.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {
    public static final String FILE = "perchguard";
    public static final String KEY_RELAY_URL = "relay_url";
    public static final String KEY_POLICY_JSON = "policy_json";
    public static final String KEY_USAGE_DATE = "usage_date";
    public static final String KEY_USAGE_JSON = "usage_json";

    public static final String DEFAULT_RELAY_URL = "ws://192.168.1.8:8899/kid/stream";

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String relayUrl(Context ctx) {
        return get(ctx).getString(KEY_RELAY_URL, DEFAULT_RELAY_URL);
    }

    public static void setRelayUrl(Context ctx, String url) {
        get(ctx).edit().putString(KEY_RELAY_URL, url).apply();
    }

    private Prefs() {}
}

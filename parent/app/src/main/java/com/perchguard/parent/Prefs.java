package com.perchguard.parent;

import android.content.Context;
import android.content.SharedPreferences;

/** Persisted relay URL + bearer token. Defaults = the deployed Cloudflare tunnel. */
public final class Prefs {
    public static final String DEFAULT_URL =
            "https://cuisine-expectations-represented-cheaper.trycloudflare.com";
    public static final String DEFAULT_TOKEN = BuildConfig.AUTH_TOKEN;

    private static final String FILE = "parent";
    private static final String K_URL = "url";
    private static final String K_TOKEN = "token";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String url(Context c) {
        String v = sp(c).getString(K_URL, DEFAULT_URL);
        return v == null || v.isEmpty() ? DEFAULT_URL : trimTrailingSlash(v);
    }

    public static String token(Context c) {
        String v = sp(c).getString(K_TOKEN, DEFAULT_TOKEN);
        return v == null ? "" : v;
    }

    public static void setUrl(Context c, String v) {
        sp(c).edit().putString(K_URL, trimTrailingSlash(v)).apply();
    }

    public static void setToken(Context c, String v) {
        sp(c).edit().putString(K_TOKEN, v).apply();
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}

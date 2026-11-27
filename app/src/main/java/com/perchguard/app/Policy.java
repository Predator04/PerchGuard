package com.perchguard.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Policy — in-memory snapshot of parent-pushed rules, persisted to SharedPreferences
 * as JSON so a reboot keeps the last known rules until the relay re-pushes them.
 *
 * Shape:
 * {
 *   "blocked": ["com.example"],
 *   "limits":  {"com.example": 30},            // per-app daily minutes
 *   "total_minutes": 180,                       // total screen time per day
 *   "bedtime": {"start":"20:00","end":"07:00","enabled":true}
 * }
 */
public final class Policy {
    private static final String TAG = "PerchGuard.Policy";
    private static volatile Policy INSTANCE;

    public final Set<String> blocked = new HashSet<>();
    public final Map<String, Integer> limits = new HashMap<>();
    public int totalMinutes = 0;
    public String bedtimeStart = "";
    public String bedtimeEnd = "";
    public boolean bedtimeEnabled = false;
    public volatile long suspendedUntil = 0;  // epoch ms; blocking paused until then

    // Self-package is always whitelisted during bedtime so the parent can still reach the app.
    public static final Set<String> BEDTIME_WHITELIST = new HashSet<>();
    static {
        BEDTIME_WHITELIST.add("com.perchguard.app");
        BEDTIME_WHITELIST.add("com.android.dialer");
        BEDTIME_WHITELIST.add("com.google.android.dialer");
        BEDTIME_WHITELIST.add("com.android.emergency");
        BEDTIME_WHITELIST.add("com.android.systemui");
        BEDTIME_WHITELIST.add("android");
        BEDTIME_WHITELIST.add("com.android.settings");
        BEDTIME_WHITELIST.add("com.android.launcher3");
        BEDTIME_WHITELIST.add("com.oneplus.launcher");
        BEDTIME_WHITELIST.add("com.android.permissioncontroller");
        BEDTIME_WHITELIST.add("com.google.android.permissioncontroller");
    }

    public static synchronized Policy get(Context ctx) {
        if (INSTANCE == null) {
            INSTANCE = new Policy();
            INSTANCE.load(ctx);
        }
        return INSTANCE;
    }

    public synchronized boolean isBlocked(String pkg) {
        if (pkg == null) return false;
        if (System.currentTimeMillis() < suspendedUntil) return false;  // parent paused blocking
        if (blocked.contains(pkg)) return true;
        if (bedtimeEnabled && isBedtimeNow() && !BEDTIME_WHITELIST.contains(pkg)) return true;
        return false;
    }

    public synchronized boolean isBedtimeNow() {
        if (!bedtimeEnabled || bedtimeStart.isEmpty() || bedtimeEnd.isEmpty()) return false;
        int now = nowMinutes();
        int s = parseHm(bedtimeStart);
        int e = parseHm(bedtimeEnd);
        if (s < 0 || e < 0) return false;
        if (s == e) return false;
        if (s < e) return now >= s && now < e;        // same-day window
        return now >= s || now < e;                     // wraps midnight
    }

    public synchronized void addBlocked(String pkg) {
        blocked.add(pkg);
    }

    public synchronized void removeBlocked(String pkg) {
        blocked.remove(pkg);
    }

    public synchronized void setLimit(String pkg, int dailyMinutes) {
        if (dailyMinutes <= 0) limits.remove(pkg);
        else limits.put(pkg, dailyMinutes);
    }

    public synchronized void setTotalMinutes(int minutes) {
        totalMinutes = Math.max(0, minutes);
    }

    public synchronized void setBedtime(String start, String end, boolean enabled) {
        bedtimeStart = start != null ? start : "";
        bedtimeEnd = end != null ? end : "";
        bedtimeEnabled = enabled;
    }

    /** Parent escape: pause all blocking for `minutes` (survives reboot + policy pushes). */
    public synchronized void suspendFor(Context ctx, long minutes) {
        suspendedUntil = System.currentTimeMillis() + minutes * 60_000L;
        Prefs.get(ctx).edit().putLong(Prefs.KEY_SUSPEND_UNTIL, suspendedUntil).apply();
    }

    public synchronized void clearSuspension(Context ctx) {
        suspendedUntil = 0;
        Prefs.get(ctx).edit().remove(Prefs.KEY_SUSPEND_UNTIL).apply();
    }

    /** Emergency stop: wipe all rules so nothing can block, then persist. */
    public synchronized void clearAll(Context ctx) {
        blocked.clear();
        limits.clear();
        totalMinutes = 0;
        bedtimeStart = "";
        bedtimeEnd = "";
        bedtimeEnabled = false;
        save(ctx);
    }

    public synchronized void replaceAll(JSONObject root) throws JSONException {
        blocked.clear();
        limits.clear();
        totalMinutes = 0;
        bedtimeStart = "";
        bedtimeEnd = "";
        bedtimeEnabled = false;
        merge(root);
    }

    public synchronized void merge(JSONObject root) throws JSONException {
        if (root.has("blocked")) {
            JSONArray arr = root.getJSONArray("blocked");
            blocked.clear();
            for (int i = 0; i < arr.length(); i++) blocked.add(arr.getString(i));
        }
        if (root.has("limits")) {
            JSONObject lim = root.getJSONObject("limits");
            limits.clear();
            Iterator<String> it = lim.keys();
            while (it.hasNext()) {
                String k = it.next();
                limits.put(k, lim.getInt(k));
            }
        }
        if (root.has("total_minutes")) totalMinutes = root.getInt("total_minutes");
        if (root.has("bedtime")) {
            JSONObject b = root.getJSONObject("bedtime");
            bedtimeStart = b.optString("start", "");
            bedtimeEnd = b.optString("end", "");
            bedtimeEnabled = b.optBoolean("enabled", false);
        }
    }

    public synchronized JSONObject toJson() {
        JSONObject root = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            for (String s : blocked) a.put(s);
            root.put("blocked", a);
            JSONObject lim = new JSONObject();
            for (Map.Entry<String, Integer> e : limits.entrySet()) lim.put(e.getKey(), e.getValue());
            root.put("limits", lim);
            root.put("total_minutes", totalMinutes);
            JSONObject b = new JSONObject();
            b.put("start", bedtimeStart);
            b.put("end", bedtimeEnd);
            b.put("enabled", bedtimeEnabled);
            root.put("bedtime", b);
        } catch (JSONException e) {
            Log.w(TAG, "toJson", e);
        }
        return root;
    }

    public synchronized void save(Context ctx) {
        SharedPreferences sp = Prefs.get(ctx);
        sp.edit().putString(Prefs.KEY_POLICY_JSON, toJson().toString()).apply();
    }

    public synchronized void load(Context ctx) {
        suspendedUntil = Prefs.get(ctx).getLong(Prefs.KEY_SUSPEND_UNTIL, 0);
        String raw = Prefs.get(ctx).getString(Prefs.KEY_POLICY_JSON, "");
        if (raw.isEmpty()) return;
        try {
            replaceAll(new JSONObject(raw));
        } catch (JSONException e) {
            Log.w(TAG, "load", e);
        }
    }

    private static int nowMinutes() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static int parseHm(String hm) {
        try {
            String[] parts = hm.split(":");
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (Exception e) {
            return -1;
        }
    }
}

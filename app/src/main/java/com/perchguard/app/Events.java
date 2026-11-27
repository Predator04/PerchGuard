package com.perchguard.app;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Events — tiny builders for the child→relay JSON protocol.
 * Keeping the shape in one place lets the dashboard / API layer stay in lock-step.
 */
public final class Events {

    public static String hello() {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", "hello");
            o.put("device", Build.MODEL);
            o.put("manufacturer", Build.MANUFACTURER);
            o.put("sdk", Build.VERSION.SDK_INT);
            JSONObject s = new JSONObject();
            s.put("w", 1116);
            s.put("h", 2484);
            o.put("screen", s);
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"hello\"}"; }
    }

    public static String foreground(String pkg) {
        return simple("foreground", "pkg", pkg);
    }

    public static String blockTriggered(String pkg) {
        return simple("block_triggered", "pkg", pkg);
    }

    public static String battery(int level, boolean charging) {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", "battery");
            o.put("level", level);
            o.put("charging", charging);
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"battery\"}"; }
    }

    public static String ack(String cmd, boolean ok, String err) {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", "ack");
            o.put("cmd", cmd);
            o.put("ok", ok);
            if (err != null) o.put("err", err);
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"ack\"}"; }
    }

    public static String usageDaily(String date, JSONObject byPkg, int totalMinutes) {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", "usage_daily");
            o.put("date", date);
            o.put("by_pkg", byPkg);
            o.put("total_minutes", totalMinutes);
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"usage_daily\"}"; }
    }

    private static String simple(String evt, String k, Object v) {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", evt);
            o.put(k, v);
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"" + evt + "\"}"; }
    }

    private Events() {}
}

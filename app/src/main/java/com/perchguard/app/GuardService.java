package com.perchguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * GuardService — background enforcer.
 *
 * - Every TICK_MS it queries UsageStatsManager.queryEvents since the last check and
 *   accumulates per-package foreground milliseconds in today's counter (JSON in prefs).
 * - When a package exceeds its daily limit, it's added to Policy.blocked so the
 *   accessibility service will bounce the child out next time it opens.
 * - If total_minutes is set and today's grand total exceeds it, every non-whitelist
 *   app is treated as blocked via the "total exceeded" branch of Policy.isBlocked.
 * - Emits usage_daily and battery events to the relay through ScreenService.
 * - Schedules bedtime alarms whenever policy changes.
 */
public class GuardService extends Service {
    private static final String TAG = "PerchGuard.Guard";
    private static final String CH = "perchguard-guard";
    public static final String ACTION_BEDTIME_TICK = "bedtime_tick";
    public static final String ACTION_POLICY_CHANGED = "policy_changed";

    private static final long TICK_MS = 10_000L;
    private static final long REPORT_MS = 60_000L;

    private HandlerThread ht;
    private Handler h;
    private UsageStatsManager usm;
    private long lastQueryMs;
    private long lastReportMs;
    private String currentPkg = "";
    private long currentPkgSinceMs;
    private final Map<String, Long> todayMsByPkg = new HashMap<>();
    private long todayTotalMs;
    private String todayDate = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            try { tick(); } catch (Exception e) { Log.w(TAG, "tick", e); }
            h.postDelayed(this, TICK_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(2, buildNotification());
        }
        ht = new HandlerThread("perchguard-guard");
        ht.start();
        h = new Handler(ht.getLooper());
        usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        loadTodayCounters();
        lastQueryMs = startOfTodayMs();
        h.post(ticker);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_POLICY_CHANGED.equals(intent.getAction())) {
            BedtimeAlarmReceiver.schedule(this);
        }
        if (intent != null && ACTION_BEDTIME_TICK.equals(intent.getAction())) {
            ScreenService.sendEvent(bedtimeEvent());
        }
        BedtimeAlarmReceiver.schedule(this);
        return START_STICKY;
    }

    private void tick() {
        rolloverIfNewDay();
        long now = System.currentTimeMillis();
        if (usm != null) {
            UsageEvents events = usm.queryEvents(lastQueryMs, now + 1);
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                int t = ev.getEventType();
                boolean isResume = t == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= 29 && t == UsageEvents.Event.ACTIVITY_RESUMED);
                boolean isPause = t == UsageEvents.Event.MOVE_TO_BACKGROUND
                    || (Build.VERSION.SDK_INT >= 29 && t == UsageEvents.Event.ACTIVITY_PAUSED);
                if (isResume) {
                    closeInterval(ev.getTimeStamp());
                    currentPkg = ev.getPackageName() != null ? ev.getPackageName() : "";
                    currentPkgSinceMs = ev.getTimeStamp();
                } else if (isPause) {
                    closeInterval(ev.getTimeStamp());
                    currentPkg = "";
                    currentPkgSinceMs = 0;
                }
            }
            lastQueryMs = now;
        }
        if (!currentPkg.isEmpty()) {
            long add = now - currentPkgSinceMs;
            if (add > 0) {
                accumulate(currentPkg, add);
                currentPkgSinceMs = now;
            }
        }
        enforceLimits();
        if (now - lastReportMs >= REPORT_MS) {
            lastReportMs = now;
            ScreenService.sendEvent(usageEvent());
            ScreenService.sendEvent(batteryEvent());
            saveTodayCounters();
        }
    }

    private void closeInterval(long endMs) {
        if (currentPkg.isEmpty() || currentPkgSinceMs <= 0) return;
        long add = endMs - currentPkgSinceMs;
        if (add > 0) accumulate(currentPkg, add);
        currentPkgSinceMs = endMs;
    }

    private void accumulate(String pkg, long add) {
        if (pkg.equals("com.perchguard.app")) return;
        Long cur = todayMsByPkg.get(pkg);
        todayMsByPkg.put(pkg, (cur == null ? 0 : cur) + add);
        todayTotalMs += add;
    }

    private void enforceLimits() {
        Policy policy = Policy.get(this);
        // Snapshot under Policy's monitor to avoid ConcurrentModification from
        // parent-driven policy edits racing with this tick.
        Map<String, Integer> snapshot;
        synchronized (policy) {
            snapshot = new HashMap<>(policy.limits);
        }
        boolean changed = false;
        for (Map.Entry<String, Integer> e : snapshot.entrySet()) {
            Long ms = todayMsByPkg.get(e.getKey());
            if (ms == null) continue;
            int minutes = (int) (ms / 60_000L);
            if (minutes >= e.getValue() && !policy.isBlocked(e.getKey())) {
                policy.addBlocked(e.getKey());
                changed = true;
            }
        }
        if (changed) policy.save(this);
    }

    private JSONObject counterJsonMinutes() {
        JSONObject by = new JSONObject();
        try {
            for (Map.Entry<String, Long> e : todayMsByPkg.entrySet()) {
                by.put(e.getKey(), (int) (e.getValue() / 60_000L));
            }
        } catch (JSONException ignored) {}
        return by;
    }

    private String usageEvent() {
        return Events.usageDaily(todayDate, counterJsonMinutes(), (int)(todayTotalMs / 60_000L));
    }

    private String batteryEvent() {
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent i = registerReceiver(null, f);
        int level = -1; boolean charging = false;
        if (i != null) {
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (l >= 0) level = (int) Math.round(l * 100.0 / scale);
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        }
        return Events.battery(level, charging);
    }

    private String bedtimeEvent() {
        try {
            JSONObject o = new JSONObject();
            o.put("evt", "bedtime");
            o.put("active", Policy.get(this).isBedtimeNow());
            return o.toString();
        } catch (JSONException e) { return "{\"evt\":\"bedtime\"}"; }
    }

    private void rolloverIfNewDay() {
        String d = today();
        if (!d.equals(todayDate)) {
            todayDate = d;
            todayMsByPkg.clear();
            todayTotalMs = 0;
            saveTodayCounters();
        }
    }

    private static long startOfTodayMs() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
    }

    private void saveTodayCounters() {
        try {
            JSONObject root = new JSONObject();
            root.put("date", todayDate);
            root.put("total_ms", todayTotalMs);
            JSONObject by = new JSONObject();
            for (Map.Entry<String, Long> e : todayMsByPkg.entrySet()) by.put(e.getKey(), e.getValue());
            root.put("by_pkg", by);
            Prefs.get(this).edit()
                .putString(Prefs.KEY_USAGE_DATE, todayDate)
                .putString(Prefs.KEY_USAGE_JSON, root.toString())
                .apply();
        } catch (JSONException e) { Log.w(TAG, "saveTodayCounters", e); }
    }

    private void loadTodayCounters() {
        todayDate = today();
        String raw = Prefs.get(this).getString(Prefs.KEY_USAGE_JSON, "");
        if (raw.isEmpty()) return;
        try {
            JSONObject root = new JSONObject(raw);
            if (!todayDate.equals(root.optString("date", ""))) return;
            todayTotalMs = root.optLong("total_ms", 0);
            JSONObject by = root.optJSONObject("by_pkg");
            if (by != null) {
                Iterator<String> it = by.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    todayMsByPkg.put(k, by.getLong(k));
                }
            }
        } catch (JSONException e) { Log.w(TAG, "loadTodayCounters", e); }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "PerchGuard Guard",
                NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, CH)
            .setContentTitle("PerchGuard Guard")
            .setContentText("Enforcing app limits")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        if (h != null) h.removeCallbacks(ticker);
        if (ht != null) ht.quitSafely();
        saveTodayCounters();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}

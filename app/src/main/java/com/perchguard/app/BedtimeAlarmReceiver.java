package com.perchguard.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import java.util.Calendar;

/**
 * BedtimeAlarmReceiver — fires at bedtime start and end. The actual block
 * decision happens inside the AccessibilityService on every window change
 * (Policy.isBedtimeNow()), so this receiver just re-schedules itself and
 * nudges the GuardService so a UI event is produced for the parent.
 */
public class BedtimeAlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "PerchGuard.Bedtime";
    public static final String ACTION = "com.perchguard.app.BEDTIME_TICK";
    private static final int REQ = 7001;

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "bedtime tick at " + System.currentTimeMillis());
        Intent i = new Intent(context, GuardService.class);
        i.setAction(GuardService.ACTION_BEDTIME_TICK);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
        else context.startService(i);
        schedule(context);
    }

    public static void schedule(Context ctx) {
        Policy p = Policy.get(ctx);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(ctx);
        am.cancel(pi);
        if (!p.bedtimeEnabled) return;

        long next = nextTransitionMillis(p);
        if (next <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
            }
        } catch (SecurityException e) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi);
        }
    }

    private static PendingIntent pending(Context ctx) {
        Intent i = new Intent(ctx, BedtimeAlarmReceiver.class).setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, REQ, i, flags);
    }

    private static long nextTransitionMillis(Policy p) {
        long now = System.currentTimeMillis();
        long nextStart = nextTime(p.bedtimeStart);
        long nextEnd = nextTime(p.bedtimeEnd);
        long next;
        if (nextStart <= 0 && nextEnd <= 0) return 0;
        if (nextStart <= 0) next = nextEnd;
        else if (nextEnd <= 0) next = nextStart;
        else next = Math.min(nextStart, nextEnd);
        if (next <= now) next = now + 60_000L;
        return next;
    }

    private static long nextTime(String hm) {
        try {
            String[] parts = hm.split(":");
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, h);
            c.set(Calendar.MINUTE, m);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis())
                c.add(Calendar.DAY_OF_YEAR, 1);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }
}

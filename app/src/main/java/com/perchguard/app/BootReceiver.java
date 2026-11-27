package com.perchguard.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    private static final String CHAN_ID = "perchguard.resume";
    private static final int NOTIF_ID = 42;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Policy.get(context);

        // Foreground limits + location work without any consent token, so
        // bring GuardService back up right away.
        Intent svc = new Intent(context, GuardService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc);
        else context.startService(svc);

        // Re-arm bedtime alarms (lost across reboot).
        BedtimeAlarmReceiver.schedule(context);

        // MediaProjection consent doesn't survive a reboot, so ScreenService
        // can't auto-resume. Post a tap-through notification so whoever's
        // holding the phone can start the live view again.
        postResumeNotification(context);
    }

    private static void postResumeNotification(Context ctx) {
        NotificationManager nm =
            (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel chan = new NotificationChannel(
                CHAN_ID, "Resume after reboot", NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("Shown once after the phone restarts so the "
                + "live view can be re-granted.");
            nm.createNotificationChannel(chan);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHAN_ID);
        else b = new Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW);

        Notification n = b
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("PerchGuard")
            .setContentText("Tap to resume live view")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build();
        try {
            nm.notify(NOTIF_ID, n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS may be revoked; best-effort only.
        }
    }
}

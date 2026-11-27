package com.perchguard.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * LocationReporter — thin wrapper that drives {@link LocationManager} and ships
 * each fix to the relay as a {@code type:location} event. Owned by
 * {@link GuardService} so it rides on the enforcement foreground service
 * (which is now typed {@code dataSync|location}).
 *
 * Why not FusedLocationProvider? Avoids the Google Play Services dependency —
 * PerchGuard is a self-hosted, off-store app and we want this to work on any
 * Android 7+ device without the Play store.
 */
final class LocationReporter {
    private static final String TAG = "PerchGuard.Loc";
    /** 60s minTime per spec; one fix a minute is plenty for a "where are they" map. */
    private static final long MIN_TIME_MS = 60_000L;
    /** ~20m min displacement so a kid carrying a phone around a classroom
     *  doesn't blow battery sending constant fixes. */
    private static final float MIN_DISTANCE_M = 20f;

    private final Context ctx;
    private final LocationManager lm;
    private final Handler handler;
    private boolean running = false;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location loc) {
            if (loc == null) return;
            ScreenService.sendEvent(Events.location(
                loc.getLatitude(), loc.getLongitude(),
                loc.hasAccuracy() ? loc.getAccuracy() : 0f,
                loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis()));
        }
        @Override public void onProviderDisabled(String provider) {}
        @Override public void onProviderEnabled(String provider) {}
        @Override public void onStatusChanged(String p, int s, Bundle extras) {}
    };

    LocationReporter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.lm = (LocationManager) this.ctx.getSystemService(Context.LOCATION_SERVICE);
        this.handler = new Handler(Looper.getMainLooper());
    }

    /** Safe to call repeatedly — will no-op if permission is missing or if
     *  providers aren't enabled. Rechecks on every call so the moment the
     *  user grants location in the wizard, the next tick picks it up. */
    @SuppressLint("MissingPermission")
    void startIfPermitted() {
        if (lm == null) return;
        if (!hasPermission()) return;
        if (running) return;
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS, MIN_DISTANCE_M, listener, handler.getLooper());
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS, MIN_DISTANCE_M, listener, handler.getLooper());
            }
            // Fire one immediate best-effort fix so the dashboard sees a pin
            // right after permission is granted (not after a full minute).
            Location last = null;
            try { last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER); }
            catch (Exception ignored) {}
            if (last == null) {
                try { last = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); }
                catch (Exception ignored) {}
            }
            if (last != null) listener.onLocationChanged(last);
            running = true;
            Log.i(TAG, "location updates started");
        } catch (SecurityException e) {
            Log.w(TAG, "location permission revoked mid-flight", e);
            running = false;
        } catch (Exception e) {
            Log.w(TAG, "startLocationUpdates", e);
        }
    }

    void stop() {
        if (lm == null) return;
        try { lm.removeUpdates(listener); } catch (Exception ignored) {}
        running = false;
    }

    private boolean hasPermission() {
        int fine = ContextCompat.checkSelfPermission(ctx,
            Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = ContextCompat.checkSelfPermission(ctx,
            Manifest.permission.ACCESS_COARSE_LOCATION);
        return fine == PackageManager.PERMISSION_GRANTED
            || coarse == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressWarnings("unused")
    private static boolean isApi31Plus() {
        return Build.VERSION.SDK_INT >= 31;
    }
}

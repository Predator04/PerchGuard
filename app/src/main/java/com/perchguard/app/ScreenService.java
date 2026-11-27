package com.perchguard.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * ScreenService — owns the single WebSocket to the relay.
 *
 *   outbound: JPEG binary frames (live view) + text JSON events (hello/foreground/usage/...)
 *   inbound:  text JSON commands (tap/swipe/type/key/policy/lock_now/screenshot/...)
 *
 * All other components speak through static helpers here (sendEvent / requestScreenshot)
 * so the socket stays a single-writer resource.
 */
public class ScreenService extends Service {
    private static final String TAG = "PerchGuard";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static final int CAP_W = 540;
    private static final int CAP_H = 1200;
    // Adaptive frame intervals — see pickFrameIntervalNs(). We default to the
    // "watching, battery OK" cadence so the first frame after start is quick.
    private static final long FRAME_INTERVAL_WATCH_NS    = 100_000_000L; //  ~10 fps
    private static final long FRAME_INTERVAL_CHARGING_NS =  66_000_000L; //  ~15 fps
    private static final long FRAME_INTERVAL_LOW_NS      = 333_000_000L; //   ~3 fps
    private static final int  JPEG_QUALITY = 55;

    private static volatile ScreenService instance;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    // Cached consent so we can tear capture down on stream_off and spin it back
    // up on stream_on without re-prompting the user.
    private int cachedResultCode = Activity.RESULT_CANCELED;
    private Intent cachedResultData;
    private HandlerThread handlerThread;
    private Handler handler;
    private OkHttpClient client;
    private volatile WebSocket webSocket;
    private volatile boolean streaming = false;
    // Parent-driven: true when at least one dashboard/parent app is watching.
    private volatile boolean watcherActive = false;
    private volatile boolean sendOneFrame = false;
    private volatile boolean connecting = false;
    private volatile long frameIntervalNs = FRAME_INTERVAL_WATCH_NS;
    private long lastSentNs = 0;
    private String relayUrl;
    private int deviceW = 1080, deviceH = 2400, deviceDpi = 240;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Android 14 (API 34+) requires foregroundServiceType on startForeground() for
        // mediaProjection services, otherwise MediaProjection.createVirtualDisplay throws.
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1, buildNotification());
        }
        handlerThread = new HandlerThread("perchguard-capture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
        relayUrl = Prefs.relayUrl(this);
        readDeviceMetrics();
    }

    private void readDeviceMetrics() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if (dm.widthPixels > 0) deviceW = dm.widthPixels;
            if (dm.heightPixels > 0) deviceH = dm.heightPixels;
            if (dm.densityDpi > 0) deviceDpi = dm.densityDpi;
        } catch (Exception e) {
            Log.w(TAG, "readDeviceMetrics", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode == Activity.RESULT_OK && data != null) {
                // Cache so we can rebuild the pipeline on stream_on without a
                // fresh consent prompt. Capture starts in the "idle" state;
                // the relay's stream_on command (sent when a parent is
                // watching) brings up the VirtualDisplay.
                cachedResultCode = resultCode;
                cachedResultData = data;
                if (mediaProjection == null) {
                    initProjection(resultCode, data);
                }
            }
        }
        // Pick up any relay URL change from Settings.
        String latest = Prefs.relayUrl(this);
        if (relayUrl == null || !relayUrl.equals(latest)) {
            relayUrl = latest;
            if (webSocket != null) {
                try { webSocket.close(1000, "relay url changed"); } catch (Exception ignored) {}
                webSocket = null;
                streaming = false;
            }
        }
        connect();

        // Also make sure the enforcement service is up.
        Intent guard = new Intent(this, GuardService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(guard);
        else startService(guard);

        return START_STICKY;
    }

    /** One-time: build MediaProjection from the user's consent. We keep this
     *  alive for the life of the service but only wire up a VirtualDisplay
     *  when a parent is actively watching. */
    private void initProjection(int resultCode, Intent data) {
        MediaProjectionManager mpm =
            (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "media projection null");
            return;
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.i(TAG, "projection revoked by user/system");
                stopSelf();
            }
        };
        mediaProjection.registerCallback(projectionCallback, handler);
    }

    /** Create the VirtualDisplay + ImageReader on demand. Idempotent. */
    private synchronized void beginCapture() {
        if (mediaProjection == null || virtualDisplay != null) return;
        imageReader = ImageReader.newInstance(CAP_W, CAP_H, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PerchGuard", CAP_W, CAP_H, 240,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, handler);
        imageReader.setOnImageAvailableListener(this::onImage, handler);
        updateFrameInterval();
        Log.i(TAG, "capture started " + CAP_W + "x" + CAP_H
            + " @ interval " + (frameIntervalNs / 1_000_000) + "ms");
    }

    /** Release the VirtualDisplay + ImageReader. MediaProjection stays alive
     *  so we can resume without re-prompting. */
    private synchronized void endCapture() {
        if (imageReader != null) {
            try { imageReader.setOnImageAvailableListener(null, null); }
            catch (Exception ignored) {}
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        lastSentNs = 0;
        Log.i(TAG, "capture paused (no parent watching)");
    }

    private void updateFrameInterval() {
        frameIntervalNs = pickFrameIntervalNs();
    }

    private long pickFrameIntervalNs() {
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent i = registerReceiver(null, f);
            if (i == null) return FRAME_INTERVAL_WATCH_NS;
            int l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int pct = (l >= 0) ? (int) Math.round(l * 100.0 / scale) : 100;
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL
                || i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            if (pct >= 0 && pct < 20 && !charging) return FRAME_INTERVAL_LOW_NS;
            if (charging) return FRAME_INTERVAL_CHARGING_NS;
            return FRAME_INTERVAL_WATCH_NS;
        } catch (Exception e) {
            return FRAME_INTERVAL_WATCH_NS;
        }
    }

    private void onImage(ImageReader reader) {
        long now = System.nanoTime();
        boolean forced = sendOneFrame;
        long interval = frameIntervalNs;
        if (!forced && now - lastSentNs < interval) {
            Image dropped = reader.acquireLatestImage();
            if (dropped != null) dropped.close();
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || webSocket == null) return;
            if (!streaming && !forced) return;
            // If no one is watching, skip encoding entirely except for the
            // one-shot screenshot path.
            if (!watcherActive && !forced) return;
            byte[] jpeg = toJpeg(image);
            if (jpeg != null) {
                lastSentNs = now;
                boolean ok = webSocket.send(ByteString.of(jpeg));
                if (forced) sendOneFrame = false;
                if (!ok) streaming = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "capture error", e);
        } finally {
            if (image != null) image.close();
        }
    }

    private byte[] toJpeg(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * CAP_W;
        int bmpW = CAP_W + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(bmpW, CAP_H, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = (bmpW == CAP_W) ? padded
            : Bitmap.createBitmap(padded, 0, 0, CAP_W, CAP_H);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        if (cropped != padded) cropped.recycle();
        padded.recycle();
        return baos.toByteArray();
    }

    private synchronized void connect() {
        if (webSocket != null || connecting) return;
        if (relayUrl == null || relayUrl.isEmpty()) return;
        connecting = true;
        Request req = new Request.Builder().url(relayUrl)
            .header("Authorization", "Bearer " + Prefs.authToken(this))
            .build();
        try {
            webSocket = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket ws, Response response) {
                    connecting = false;
                    streaming = true;
                    Log.i(TAG, "relay connected " + relayUrl);
                    ws.send(Events.hello(deviceW, deviceH));
                }
                @Override public void onMessage(WebSocket ws, String text) {
                    handleCommand(text);
                }
                @Override public void onClosed(WebSocket ws, int code, String reason) {
                    connecting = false;
                    streaming = false;
                    webSocket = null;
                    handler.postDelayed(ScreenService.this::connect, 3000);
                }
                @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                    connecting = false;
                    streaming = false;
                    webSocket = null;
                    Log.w(TAG, "relay failure: " + t.getMessage());
                    handler.postDelayed(ScreenService.this::connect, 3000);
                }
            });
        } catch (Exception e) {
            connecting = false;
            Log.w(TAG, "connect", e);
        }
    }

    // ---------- command dispatch ----------

    private void handleCommand(String text) {
        try {
            JSONObject o = new JSONObject(text);
            String cmd = o.optString("cmd", "");
            AccessibilityControlService ax = AccessibilityControlService.getInstance();
            switch (cmd) {
                case "tap": {
                    if (ax == null) { ack(cmd, false, "accessibility_off"); break; }
                    ack(cmd, ax.tap(o.getInt("x"), o.getInt("y")), null);
                    break;
                }
                case "swipe": {
                    if (ax == null) { ack(cmd, false, "accessibility_off"); break; }
                    ack(cmd, ax.swipe(
                        o.getInt("x1"), o.getInt("y1"),
                        o.getInt("x2"), o.getInt("y2"),
                        o.optInt("dur", 200)), null);
                    break;
                }
                case "type": {
                    if (ax == null) { ack(cmd, false, "accessibility_off"); break; }
                    ack(cmd, ax.typeText(o.getString("text")), null);
                    break;
                }
                case "key": {
                    if (ax == null) { ack(cmd, false, "accessibility_off"); break; }
                    ack(cmd, ax.globalKey(o.getString("code")), null);
                    break;
                }
                case "lock_now": {
                    if (ax == null) { ack(cmd, false, "accessibility_off"); break; }
                    ack(cmd, ax.globalKey("lock"), null);
                    break;
                }
                case "screenshot": {
                    sendOneFrame = true;
                    // If capture is paused for battery, briefly spin it up so
                    // the next frame actually exists.
                    handler.post(this::beginCapture);
                    ack(cmd, true, null);
                    break;
                }
                case "stream_on": {
                    watcherActive = true;
                    updateFrameInterval();
                    handler.post(this::beginCapture);
                    ack(cmd, true, null);
                    break;
                }
                case "stream_off": {
                    watcherActive = false;
                    handler.post(this::endCapture);
                    ack(cmd, true, null);
                    break;
                }
                case "block": {
                    String pkg = o.getString("package");
                    boolean on = o.optBoolean("block", true);
                    Policy p = Policy.get(this);
                    if (on) p.addBlocked(pkg); else p.removeBlocked(pkg);
                    p.save(this);
                    ack(cmd, true, null);
                    break;
                }
                case "set_limits": {
                    Policy p = Policy.get(this);
                    if (o.has("packages")) {
                        JSONObject pkgs = o.getJSONObject("packages");
                        p.limits.clear();
                        java.util.Iterator<String> it = pkgs.keys();
                        while (it.hasNext()) {
                            String k = it.next();
                            JSONObject v = pkgs.getJSONObject(k);
                            p.setLimit(k, v.getInt("daily_minutes"));
                        }
                    }
                    if (o.has("total_minutes")) p.setTotalMinutes(o.getInt("total_minutes"));
                    p.save(this);
                    ack(cmd, true, null);
                    break;
                }
                case "bedtime": {
                    Policy p = Policy.get(this);
                    p.setBedtime(
                        o.optString("start", ""),
                        o.optString("end", ""),
                        o.optBoolean("enabled", true));
                    p.save(this);
                    nudgeGuard();
                    ack(cmd, true, null);
                    break;
                }
                case "install_apk": {
                    String url = o.optString("url", "");
                    if (url.isEmpty()) { ack(cmd, false, "missing_url"); break; }
                    InstallHelper.downloadAndInstall(getApplicationContext(), url);
                    // InstallHelper fires its own ack once download+launch resolves.
                    break;
                }
                case "update": {
                    final Context appCtx = getApplicationContext();
                    new UpdateChecker(appCtx).check(new UpdateChecker.Listener() {
                        @Override public void onNoUpdate(int installed, int latest) {
                            ack("update", true, "up_to_date_" + installed);
                        }
                        @Override public void onUpdateAvailable(int latest, String tag,
                                                                 String assetUrl, String assetName) {
                            ack("update", true, "installing_" + tag);
                            new UpdateChecker(appCtx)
                                .downloadAndInstall(assetUrl, assetName, appCtx);
                        }
                        @Override public void onError(String message) {
                            ack("update", false, message);
                        }
                    });
                    break;
                }
                case "policy": {
                    Policy p = Policy.get(this);
                    p.replaceAll(o);
                    p.save(this);
                    nudgeGuard();
                    ack(cmd, true, null);
                    break;
                }
                default:
                    ack(cmd, false, "unknown_cmd");
            }
        } catch (JSONException e) {
            Log.w(TAG, "bad command: " + text, e);
        } catch (Exception e) {
            Log.w(TAG, "command handler error", e);
        }
    }

    private void ack(String cmd, boolean ok, String err) {
        sendEvent(Events.ack(cmd, ok, err));
    }

    private void nudgeGuard() {
        Intent i = new Intent(this, GuardService.class);
        i.setAction(GuardService.ACTION_POLICY_CHANGED);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    // ---------- static API for other services ----------

    public static boolean sendEvent(String json) {
        ScreenService svc = instance;
        if (svc == null) return false;
        WebSocket ws = svc.webSocket;
        if (ws == null) return false;
        try { return ws.send(json); } catch (Exception e) { return false; }
    }

    public static void requestScreenshot() {
        ScreenService svc = instance;
        if (svc != null) svc.sendOneFrame = true;
    }

    /** Called by GuardService every ~minute so the frame interval tracks the
     *  current battery state without extra wakeups of our own. */
    public static void refreshAdaptiveFps() {
        ScreenService svc = instance;
        if (svc != null) svc.updateFrameInterval();
    }

    public static boolean isRunning() {
        return instance != null;
    }

    private Notification buildNotification() {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                "perchguard", "PerchGuard", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Keeps PerchGuard running in the background.");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
            | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);
        return new NotificationCompat.Builder(this, "perchguard")
            .setContentTitle("PerchGuard is watching")
            .setContentText("Live view and limits are active.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        streaming = false;
        if (instance == this) instance = null;
        if (imageReader != null) imageReader.setOnImageAvailableListener(null, null);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) {
            if (projectionCallback != null) mediaProjection.unregisterCallback(projectionCallback);
            mediaProjection.stop();
        }
        if (webSocket != null) {
            webSocket.close(1000, "bye");
            webSocket = null;
        }
        if (handlerThread != null) handlerThread.quitSafely();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

package com.perchguard.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

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
    private static final long FRAME_INTERVAL_NS = 250_000_000L; // ~4 fps awareness stream

    private static volatile ScreenService instance;

    private MediaProjection mediaProjection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler handler;
    private OkHttpClient client;
    private WebSocket webSocket;
    private volatile boolean streaming = false;
    private volatile boolean sendOneFrame = false;
    private long lastSentNs = 0;
    private String relayUrl;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        startForeground(1, buildNotification());
        handlerThread = new HandlerThread("perchguard-capture");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
        client = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
        relayUrl = Prefs.relayUrl(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && virtualDisplay == null) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultCode == Activity.RESULT_OK && data != null) {
                startCapture(resultCode, data);
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

    private void startCapture(int resultCode, Intent data) {
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

        imageReader = ImageReader.newInstance(CAP_W, CAP_H, PixelFormat.RGBA_8888, 2);
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "PerchGuard", CAP_W, CAP_H, 240,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(), null, handler);
        imageReader.setOnImageAvailableListener(this::onImage, handler);
        Log.i(TAG, "capture started " + CAP_W + "x" + CAP_H);
    }

    private void onImage(ImageReader reader) {
        long now = System.nanoTime();
        boolean forced = sendOneFrame;
        if (!forced && now - lastSentNs < FRAME_INTERVAL_NS) {
            Image dropped = reader.acquireLatestImage();
            if (dropped != null) dropped.close();
            return;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null || webSocket == null) return;
            if (!streaming && !forced) return;
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
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, CAP_W, CAP_H);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        cropped.compress(Bitmap.CompressFormat.JPEG, 60, baos);
        cropped.recycle();
        padded.recycle();
        return baos.toByteArray();
    }

    private void connect() {
        if (webSocket != null) return;
        if (relayUrl == null || relayUrl.isEmpty()) return;
        Request req = new Request.Builder().url(relayUrl).build();
        webSocket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                streaming = true;
                Log.i(TAG, "relay connected " + relayUrl);
                ws.send(Events.hello());
            }
            @Override public void onMessage(WebSocket ws, String text) {
                handleCommand(text);
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                streaming = false;
                webSocket = null;
                handler.postDelayed(ScreenService.this::connect, 3000);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                streaming = false;
                webSocket = null;
                Log.w(TAG, "relay failure: " + t.getMessage());
                handler.postDelayed(ScreenService.this::connect, 3000);
            }
        });
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
        if (svc == null || svc.webSocket == null) return false;
        try { return svc.webSocket.send(json); } catch (Exception e) { return false; }
    }

    public static void requestScreenshot() {
        ScreenService svc = instance;
        if (svc != null) svc.sendOneFrame = true;
    }

    private Notification buildNotification() {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                "perchguard", "PerchGuard", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        return new NotificationCompat.Builder(this, "perchguard")
            .setContentTitle("PerchGuard")
            .setContentText("Screen monitoring active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
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

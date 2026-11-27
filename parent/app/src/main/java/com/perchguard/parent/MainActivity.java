package com.perchguard.parent;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class MainActivity extends Activity {

    private RelayClient relay;

    // UI
    private TextView statusPill;
    private ImageView liveImage;
    private TextView hintBelow;

    // WS + reconnect
    private WebSocket ws;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int reconnectAttempt = 0;
    private boolean isForeground = false;
    private boolean wsConnected = false;

    // Child state
    private volatile int nativeW = 1116;
    private volatile int nativeH = 2484;
    private String foregroundPkg = null;
    private int batteryLevel = -1;
    private boolean charging = false;
    private boolean kidConnected = false;
    private long lastSeenTs = 0;

    // Decode pipeline
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private Bitmap reuseBitmap;
    private volatile long lastRenderMs = 0;
    private static final long RENDER_INTERVAL_MS = 100; // ~10fps cap
    private volatile boolean decodeBusy = false;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                             WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(buildUi());

        decodeThread = new HandlerThread("decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        relay = new RelayClient(this);
        startTicker();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad = Ui.dp(this, 10);
        root.setPadding(pad, pad, pad, pad);

        // Top bar
        LinearLayout top = Ui.row(this);
        statusPill = new TextView(this);
        statusPill.setTextColor(Ui.TEXT);
        statusPill.setBackground(Ui.roundedFill(Ui.SURFACE, Ui.STROKE, 20, this));
        int px = Ui.dp(this, 12), py = Ui.dp(this, 6);
        statusPill.setPadding(px, py, px, py);
        statusPill.setText("Connecting…");

        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);

        android.widget.Button mapBtn = Ui.button(this, "Map");
        android.widget.Button limitsBtn = Ui.button(this, "Limits");
        android.widget.Button settingsBtn = Ui.button(this, "Settings");
        mapBtn.setOnClickListener(v -> startActivity(new Intent(this, LocationActivity.class)));
        limitsBtn.setOnClickListener(v -> startActivity(new Intent(this, LimitsActivity.class)));
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));

        top.addView(statusPill);
        top.addView(spacer, spLp);
        top.addView(mapBtn);
        LinearLayout.LayoutParams limitsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        limitsLp.leftMargin = Ui.dp(this, 8);
        limitsBtn.setLayoutParams(limitsLp);
        top.addView(limitsBtn);
        LinearLayout.LayoutParams margin = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        margin.leftMargin = Ui.dp(this, 8);
        settingsBtn.setLayoutParams(margin);
        top.addView(settingsBtn);

        root.addView(top, Ui.lpMatch());

        // Live image area (weight 1)
        liveImage = new ImageView(this);
        liveImage.setBackground(Ui.roundedFill(Ui.SURFACE, Ui.STROKE, 10, this));
        liveImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        liveImage.setAdjustViewBounds(true);
        liveImage.setClipToOutline(true);
        LinearLayout.LayoutParams imgLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        imgLp.topMargin = Ui.dp(this, 10);
        imgLp.bottomMargin = Ui.dp(this, 10);
        liveImage.setLayoutParams(imgLp);
        attachTouch(liveImage);
        root.addView(liveImage);

        // Control row: Home/Back/Recents/Notif/Lock/Shot
        LinearLayout ctrl = Ui.row(this);
        ctrl.addView(keyBtn("Home",    "home"));
        ctrl.addView(keyBtn("Back",    "back"));
        ctrl.addView(keyBtn("Recents", "recents"));
        ctrl.addView(keyBtn("Notif",   "notifications"));

        android.widget.Button lockBtn = Ui.button(this, "Lock");
        lockBtn.setOnClickListener(v -> doPost("/api/action/lock", new JSONObject(), "Locked"));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lockLp.leftMargin = Ui.dp(this, 6);
        lockBtn.setLayoutParams(lockLp);
        ctrl.addView(lockBtn);

        android.widget.Button shotBtn = Ui.button(this, "Shot");
        shotBtn.setOnClickListener(v -> doPost("/api/action/screenshot", new JSONObject(), null));
        LinearLayout.LayoutParams shotLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shotLp.leftMargin = Ui.dp(this, 6);
        shotBtn.setLayoutParams(shotLp);
        ctrl.addView(shotBtn);

        root.addView(ctrl, Ui.lpMatch());

        // Type text row
        LinearLayout typeRow = Ui.row(this);
        LinearLayout.LayoutParams typeRowLp = Ui.lpMatch();
        typeRowLp.topMargin = Ui.dp(this, 8);
        typeRow.setLayoutParams(typeRowLp);

        EditText typeField = Ui.edit(this, "Type text to send…", null, false);
        typeField.setImeOptions(EditorInfo.IME_ACTION_SEND);
        LinearLayout.LayoutParams tfLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        typeField.setLayoutParams(tfLp);

        android.widget.Button sendBtn = Ui.primaryButton(this, "Send");
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sendLp.leftMargin = Ui.dp(this, 8);
        sendBtn.setLayoutParams(sendLp);

        Runnable sendText = () -> {
            String s = typeField.getText().toString();
            if (s.isEmpty()) return;
            try {
                doPost("/api/action/type", new JSONObject().put("text", s), null);
                typeField.setText("");
            } catch (Exception ignored) {}
        };
        sendBtn.setOnClickListener(v -> sendText.run());
        typeField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendText.run(); return true; }
            return false;
        });

        typeRow.addView(typeField);
        typeRow.addView(sendBtn);
        root.addView(typeRow);

        // Update row: check-this-app + force-update-child, prominent.
        LinearLayout updRow = Ui.row(this);
        LinearLayout.LayoutParams updRowLp = Ui.lpMatch();
        updRowLp.topMargin = Ui.dp(this, 8);
        updRow.setLayoutParams(updRowLp);

        android.widget.Button checkUpdBtn = Ui.primaryButton(this, "Check for updates");
        checkUpdBtn.setOnClickListener(v ->
            startActivity(new Intent(this, UpdateActivity.class)));
        LinearLayout.LayoutParams cuLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        checkUpdBtn.setLayoutParams(cuLp);
        updRow.addView(checkUpdBtn);

        android.widget.Button forceUpdBtn = Ui.button(this, "Force update child");
        forceUpdBtn.setOnClickListener(v -> doPost("/api/action/update",
                new JSONObject(), "Update sent to child"));
        LinearLayout.LayoutParams fuLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fuLp.leftMargin = Ui.dp(this, 8);
        forceUpdBtn.setLayoutParams(fuLp);
        updRow.addView(forceUpdBtn);

        root.addView(updRow);

        hintBelow = Ui.muted(this, "Tap image = tap child · Drag = swipe");
        LinearLayout.LayoutParams hintLp = Ui.lpMatch();
        hintLp.topMargin = Ui.dp(this, 6);
        hintBelow.setLayoutParams(hintLp);
        root.addView(hintBelow);

        return root;
    }

    private android.widget.Button keyBtn(String label, String code) {
        android.widget.Button b = Ui.button(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(this, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            try {
                doPost("/api/action/key", new JSONObject().put("code", code), null);
            } catch (Exception ignored) {}
        });
        return b;
    }

    // ---- Touch: tap / swipe on the live image ----

    private void attachTouch(View target) {
        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        target.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            long downT;
            boolean moved;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX(); downY = e.getY();
                        downT = SystemClock.uptimeMillis();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!moved && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                            moved = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP: {
                        long dt = SystemClock.uptimeMillis() - downT;
                        PointF from = viewToNative(v, downX, downY);
                        PointF to   = viewToNative(v, e.getX(), e.getY());
                        if (from == null || to == null) return true;
                        if (!moved && dt < 300) {
                            sendTap((int) from.x, (int) from.y);
                        } else {
                            int dur = Math.max(80, Math.min(2000, (int) dt));
                            sendSwipe((int) from.x, (int) from.y, (int) to.x, (int) to.y, dur);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });
    }

    private PointF viewToNative(View v, float vx, float vy) {
        float viewW = v.getWidth(), viewH = v.getHeight();
        if (viewW <= 0 || viewH <= 0 || nativeW <= 0 || nativeH <= 0) return null;
        float scale = Math.min(viewW / (float) nativeW, viewH / (float) nativeH);
        float imgW = nativeW * scale, imgH = nativeH * scale;
        float offX = (viewW - imgW) / 2f, offY = (viewH - imgH) / 2f;
        float nx = (vx - offX) / scale;
        float ny = (vy - offY) / scale;
        if (nx < 0) nx = 0; else if (nx > nativeW) nx = nativeW;
        if (ny < 0) ny = 0; else if (ny > nativeH) ny = nativeH;
        return new PointF(nx, ny);
    }

    private void sendTap(int x, int y) {
        try {
            doPost("/api/action/tap", new JSONObject().put("x", x).put("y", y), null);
        } catch (Exception ignored) {}
    }

    private void sendSwipe(int x1, int y1, int x2, int y2, int dur) {
        try {
            JSONObject b = new JSONObject();
            b.put("x1", x1); b.put("y1", y1);
            b.put("x2", x2); b.put("y2", y2);
            b.put("dur", dur);
            doPost("/api/action/swipe", b, null);
        } catch (Exception ignored) {}
    }

    private void doPost(String path, JSONObject body, final String toastOnOk) {
        relay.post(path, body, new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> toast("Network: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                try {
                    boolean ok = resp.isSuccessful();
                    int code = resp.code();
                    if (!ok) {
                        main.post(() -> toast("HTTP " + code));
                    } else if (toastOnOk != null) {
                        main.post(() -> toast(toastOnOk));
                    }
                } finally { resp.close(); }
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---- WebSocket lifecycle ----

    @Override protected void onStart() {
        super.onStart();
        isForeground = true;
        // Rebuild RelayClient in case settings changed while paused.
        relay = new RelayClient(this);
        reconnectAttempt = 0;
        openWs();
    }

    @Override protected void onStop() {
        super.onStop();
        isForeground = false;
        closeWs();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        if (decodeHandler != null) decodeHandler.removeCallbacksAndMessages(null);
        if (decodeThread != null) decodeThread.quitSafely();
        if (relay != null) relay.shutdown();
        if (reuseBitmap != null && !reuseBitmap.isRecycled()) reuseBitmap.recycle();
        reuseBitmap = null;
    }

    private void openWs() {
        closeWs();
        try {
            ws = relay.openParentView(new ParentWsListener());
        } catch (Exception e) {
            scheduleReconnect();
        }
    }

    private void closeWs() {
        WebSocket w = ws;
        ws = null;
        if (w != null) {
            try { w.cancel(); } catch (Exception ignored) {}
        }
        wsConnected = false;
    }

    private void scheduleReconnect() {
        if (!isForeground) return;
        reconnectAttempt = Math.min(reconnectAttempt + 1, 6);
        long delay = (long) Math.min(15_000, 1000L * (1L << (reconnectAttempt - 1)));
        main.postDelayed(() -> { if (isForeground) openWs(); }, delay);
    }

    private final class ParentWsListener extends WebSocketListener {
        @Override public void onOpen(WebSocket webSocket, Response response) {
            reconnectAttempt = 0;
            wsConnected = true;
            main.post(() -> updateStatusPill());
        }

        @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastRenderMs < RENDER_INTERVAL_MS) return;
            if (decodeBusy) return;
            lastRenderMs = now;
            final byte[] copy = bytes.toByteArray();
            decodeBusy = true;
            decodeHandler.post(() -> decodeAndShow(copy));
        }

        @Override public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject o = new JSONObject(text);
                String type = o.optString("type");
                if ("state".equals(type)) {
                    applyState(o.optJSONObject("state"));
                } else if ("event".equals(type)) {
                    applyEvent(o.optJSONObject("event"));
                }
            } catch (Exception ignored) {}
        }

        @Override public void onClosing(WebSocket webSocket, int code, String reason) {
            try { webSocket.close(1000, null); } catch (Exception ignored) {}
        }

        @Override public void onClosed(WebSocket webSocket, int code, String reason) {
            wsConnected = false;
            main.post(() -> { updateStatusPill(); scheduleReconnect(); });
        }

        @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            wsConnected = false;
            main.post(() -> { updateStatusPill(); scheduleReconnect(); });
        }
    }

    private void decodeAndShow(byte[] data) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inMutable = true;
        if (reuseBitmap != null && !reuseBitmap.isRecycled()) {
            opts.inBitmap = reuseBitmap;
        }
        Bitmap bmp = null;
        try {
            bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (IllegalArgumentException retry) {
            opts.inBitmap = null;
            try { bmp = BitmapFactory.decodeByteArray(data, 0, data.length, opts); }
            catch (Exception ignored) {}
        } catch (Exception ignored) {}
        if (bmp == null) { decodeBusy = false; return; }
        reuseBitmap = bmp;
        // Also update native dims from the first decoded frame if we don't yet have a hello.
        if (bmp.getWidth() > 0 && bmp.getHeight() > 0) {
            nativeW = bmp.getWidth();
            nativeH = bmp.getHeight();
        }
        final Bitmap toShow = bmp;
        main.post(() -> {
            liveImage.setImageBitmap(toShow);
            decodeBusy = false;
        });
    }

    private void applyState(JSONObject state) {
        if (state == null) return;
        kidConnected = state.optBoolean("kid_connected", false);
        lastSeenTs = (long) (state.optDouble("last_seen_ts", 0) * 1000);

        JSONObject device = state.optJSONObject("device");
        if (device != null) {
            int w = device.optInt("w", 0);
            int h = device.optInt("h", 0);
            if (w > 0 && h > 0) { nativeW = w; nativeH = h; }
        }
        JSONObject batt = state.optJSONObject("battery");
        if (batt != null) {
            batteryLevel = batt.optInt("level", -1);
            charging = batt.optBoolean("charging", false);
        }
        foregroundPkg = state.optString("foreground", null);
        main.post(this::updateStatusPill);
    }

    private void applyEvent(JSONObject evt) {
        if (evt == null) return;
        String kind = evt.optString("evt");
        lastSeenTs = System.currentTimeMillis();
        switch (kind) {
            case "hello": {
                int w = evt.optInt("w", 0);
                int h = evt.optInt("h", 0);
                if (w > 0 && h > 0) { nativeW = w; nativeH = h; }
                kidConnected = true;
                break;
            }
            case "foreground":
                foregroundPkg = evt.optString("pkg", foregroundPkg);
                break;
            case "battery":
                batteryLevel = evt.optInt("level", batteryLevel);
                charging = evt.optBoolean("charging", charging);
                break;
        }
        main.post(this::updateStatusPill);
    }

    private void startTicker() {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                updateStatusPill();
                main.postDelayed(this, 5000);
            }
        }, 5000);
    }

    private void updateStatusPill() {
        StringBuilder sb = new StringBuilder();
        boolean onlineDot = kidConnected && wsConnected;
        sb.append(onlineDot ? "● " : "○ ");
        sb.append(onlineDot ? "Online" : (wsConnected ? "Child offline" : "Reconnecting…"));
        if (lastSeenTs > 0) {
            long ago = Math.max(0, System.currentTimeMillis() - lastSeenTs) / 1000;
            sb.append(" · ").append(formatAgo(ago));
        }
        if (batteryLevel >= 0) {
            sb.append(" · ").append(batteryLevel).append("%");
            if (charging) sb.append("⚡");
        }
        statusPill.setText(sb.toString());
        statusPill.setTextColor(onlineDot ? Ui.OK : (wsConnected ? Ui.WARN : Ui.MUTED));
    }

    private static String formatAgo(long secs) {
        if (secs < 60) return secs + "s ago";
        if (secs < 3600) return (secs / 60) + "m ago";
        return (secs / 3600) + "h ago";
    }
}

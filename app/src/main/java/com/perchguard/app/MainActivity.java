package com.perchguard.app;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * MainActivity — the "set it up for me" wizard.
 *
 * Four numbered rows, each showing live grant status and a one-tap button to
 * fix it. The Start button at the bottom lights up only when every required
 * step is done; if tapped before that, it walks the user through what's left.
 */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIF = 1002;
    private static final int REQ_LOCATION = 1003;
    private static final int REQ_LOCATION_BG = 1004;
    private static final int REQ_DEVICE_ADMIN = 1005;

    private static final int COL_BG = Color.parseColor("#080A10");
    private static final int COL_BG_2 = Color.parseColor("#0F1320");
    private static final int COL_CARD = Color.parseColor("#1F2330");
    private static final int COL_CARD_2 = Color.parseColor("#14171F");
    private static final int COL_CARD_EDGE = 0x26FFFFFF;
    private static final int COL_TEXT = Color.parseColor("#E9ECF4");
    private static final int COL_MUTED = Color.parseColor("#8E95A6");
    private static final int COL_OK = Color.parseColor("#7EE0A6");
    private static final int COL_PENDING = Color.parseColor("#F6B86B");
    private static final int COL_ACCENT = Color.parseColor("#8AB4F8");
    private static final int COL_ACCENT_DIM = Color.parseColor("#2B3D61");
    private static final int COL_INNER_HI = 0x1AFFFFFF;

    private MediaProjectionManager mpm;
    private StepRow rowCapture, rowAccessibility, rowUsage, rowRelay, rowLocation, rowAdmin;
    private Button startBtn;
    private EditText relayField;
    private TextView relayStatus;
    private View relaySection;
    private TextView advancedToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        GradientDrawable bgGrad = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{COL_BG_2, COL_BG});
        scroll.setBackground(bgGrad);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, dp(36), pad, dp(36));

        TextView title = new TextView(this);
        title.setText("PerchGuard");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(COL_TEXT);

        TextView sub = new TextView(this);
        sub.setText("A few taps to finish setting this up for you.");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        sub.setTextColor(COL_MUTED);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(6);
        subLp.bottomMargin = dp(24);
        sub.setLayoutParams(subLp);

        rowAccessibility = new StepRow(1, "Let PerchGuard tap and type",
            "Needed so a parent can help remotely and enforce app blocks.",
            "Open Settings",
            v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        rowUsage = new StepRow(2, "Let PerchGuard see app usage",
            "Needed to apply daily time limits and total screen time.",
            "Open Settings",
            v -> startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        rowCapture = new StepRow(3, "Let PerchGuard see the screen",
            "A phone prompt will appear each time monitoring starts.",
            "Grant when you tap Start",
            v -> requestCapture());

        rowLocation = new StepRow(4, "Let PerchGuard see this device's location",
            "So a parent can find the phone on a map if it's lost or the child "
                + "takes it somewhere unexpected. Only your relay sees it.",
            "Allow",
            v -> requestLocationPermission());

        rowRelay = new StepRow(5, "Point to your home relay",
            "Enter the address the setup script printed (ws:// or wss://).",
            "Edit below",
            v -> relayField.requestFocus());

        rowAdmin = new StepRow(6, "Lock this app on",
            "So the app can't be removed without the parent's PIN. "
                + "Recommended, not required.",
            "Turn on",
            v -> requestDeviceAdmin());

        root.addView(title);
        root.addView(sub);
        root.addView(rowAccessibility.view);
        root.addView(rowUsage.view);
        root.addView(rowCapture.view);
        root.addView(rowLocation.view);
        root.addView(rowRelay.view);
        root.addView(rowAdmin.view);

        root.addView(buildRelaySection());
        root.addView(buildPinSection());

        startBtn = new Button(this);
        startBtn.setText("Start monitoring");
        startBtn.setAllCaps(false);
        startBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        startBtn.setTextColor(Color.WHITE);
        GradientDrawable startBg = new GradientDrawable();
        startBg.setColor(COL_ACCENT);
        startBg.setCornerRadius(dp(14));
        startBtn.setBackground(startBg);
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        startLp.topMargin = dp(16);
        startBtn.setLayoutParams(startLp);
        startBtn.setOnClickListener(v -> handleStart());
        root.addView(startBtn);

        TextView stopLink = new TextView(this);
        stopLink.setText("Stop monitoring");
        stopLink.setTextColor(COL_MUTED);
        stopLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        stopLink.setGravity(Gravity.CENTER);
        stopLink.setPadding(dp(16), dp(20), dp(16), dp(8));
        stopLink.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenService.class));
            stopService(new Intent(this, GuardService.class));
            Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });
        root.addView(stopLink);

        Button emergencyBtn = new Button(this);
        emergencyBtn.setText("Emergency stop — unlock everything");
        emergencyBtn.setAllCaps(false);
        emergencyBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emergencyBtn.setTextColor(Color.WHITE);
        GradientDrawable ebg = new GradientDrawable();
        ebg.setColor(Color.parseColor("#7A2E2E"));
        ebg.setCornerRadius(dp(14));
        emergencyBtn.setBackground(ebg);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        elp.topMargin = dp(12);
        emergencyBtn.setLayoutParams(elp);
        emergencyBtn.setOnClickListener(v -> emergencyStop());
        root.addView(emergencyBtn);

        Button updateBtn = new Button(this);
        updateBtn.setText("Check for updates");
        updateBtn.setAllCaps(false);
        updateBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        updateBtn.setTextColor(Color.WHITE);
        GradientDrawable updateBg = new GradientDrawable();
        updateBg.setColor(COL_ACCENT_DIM);
        updateBg.setCornerRadius(dp(14));
        updateBtn.setBackground(updateBg);
        LinearLayout.LayoutParams updateLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        updateLp.topMargin = dp(12);
        updateBtn.setLayoutParams(updateLp);
        updateBtn.setOnClickListener(v -> checkForUpdateBtn(updateBtn));
        root.addView(updateBtn);

        TextView versionLine = new TextView(this);
        versionLine.setText("PerchGuard child v" + BuildConfig.VERSION_NAME
            + " (code " + BuildConfig.VERSION_CODE + ")");
        versionLine.setTextColor(COL_MUTED);
        versionLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        versionLine.setGravity(Gravity.CENTER);
        versionLine.setPadding(dp(16), dp(2), dp(16), dp(20));
        root.addView(versionLine);

        scroll.addView(root);
        return scroll;
    }

    private View buildRelaySection() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        wrap.setLayoutParams(lp);

        advancedToggle = new TextView(this);
        advancedToggle.setText("▸ Relay URL");
        advancedToggle.setTextColor(COL_MUTED);
        advancedToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        advancedToggle.setPadding(dp(4), dp(12), dp(4), dp(12));
        wrap.addView(advancedToggle);

        relaySection = buildRelayBody();
        relaySection.setVisibility(View.GONE);
        wrap.addView(relaySection);

        advancedToggle.setOnClickListener(v -> {
            boolean showing = relaySection.getVisibility() == View.VISIBLE;
            relaySection.setVisibility(showing ? View.GONE : View.VISIBLE);
            advancedToggle.setText((showing ? "▸" : "▾") + " Relay URL");
        });
        return wrap;
    }

    private View buildRelayBody() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glassCard(14));
        if (Build.VERSION.SDK_INT >= 21) box.setElevation(dp(2));
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText("Address the setup script printed, for example "
            + "ws://192.168.1.50:8899/kid/stream");
        help.setTextColor(COL_MUTED);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        help.setPadding(0, 0, 0, dp(8));
        box.addView(help);

        relayField = new EditText(this);
        relayField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        relayField.setText(Prefs.relayUrl(this));
        relayField.setTextColor(COL_TEXT);
        relayField.setHintTextColor(COL_MUTED);
        relayField.setHint("ws://host:8899/kid/stream");
        relayField.setSingleLine(true);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.parseColor("#11151a"));
        fieldBg.setCornerRadius(dp(8));
        fieldBg.setStroke(dp(1), COL_CARD_EDGE);
        relayField.setBackground(fieldBg);
        relayField.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(relayField);

        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(COL_ACCENT_DIM);
        sbg.setCornerRadius(dp(10));
        save.setBackground(sbg);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        sl.topMargin = dp(10);
        save.setLayoutParams(sl);
        save.setOnClickListener(v -> saveRelayUrl());
        box.addView(save);

        relayStatus = new TextView(this);
        relayStatus.setTextColor(COL_MUTED);
        relayStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        relayStatus.setPadding(0, dp(8), 0, 0);
        box.addView(relayStatus);

        return box;
    }

    private void saveRelayUrl() {
        String url = relayField.getText().toString().trim();
        if (!isValidRelay(url)) {
            relayStatus.setTextColor(COL_PENDING);
            relayStatus.setText("Address must start with ws:// or wss://");
            return;
        }
        Prefs.setRelayUrl(this, url);
        relayStatus.setTextColor(COL_OK);
        relayStatus.setText("Saved.");
        if (ScreenService.isRunning()) {
            Intent kick = new Intent(this, ScreenService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(kick);
            else startService(kick);
        }
        refreshStatus();
    }

    private void emergencyStop() {
        Policy.get(this).clearAll(this);
        Policy.get(this).suspendFor(this, 30);
        stopService(new Intent(this, ScreenService.class));
        stopService(new Intent(this, GuardService.class));
        Toast.makeText(this, "All rules cleared — blocking paused 30 min.", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private View buildPinSection() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        wrap.setLayoutParams(lp);

        final TextView toggle = new TextView(this);
        toggle.setText("▸ Parent unlock PIN");
        toggle.setTextColor(COL_MUTED);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toggle.setPadding(dp(4), dp(12), dp(4), dp(12));
        wrap.addView(toggle);

        final View body = buildPinBody();
        body.setVisibility(View.GONE);
        wrap.addView(body);

        toggle.setOnClickListener(v -> {
            boolean showing = body.getVisibility() == View.VISIBLE;
            body.setVisibility(showing ? View.GONE : View.VISIBLE);
            toggle.setText((showing ? "▸" : "▾") + " Parent unlock PIN");
        });
        return wrap;
    }

    private View buildPinBody() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(glassCard(14));
        if (Build.VERSION.SDK_INT >= 21) box.setElevation(dp(2));
        int pad = dp(14);
        box.setPadding(pad, pad, pad, pad);

        TextView help = new TextView(this);
        help.setText("Used on the block screen to pause blocking. Default is 1234 — change it.");
        help.setTextColor(COL_MUTED);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        help.setPadding(0, 0, 0, dp(8));
        box.addView(help);

        final EditText pinField = new EditText(this);
        pinField.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinField.setText(Prefs.parentPin(this));
        pinField.setTextColor(COL_TEXT);
        pinField.setHintTextColor(COL_MUTED);
        pinField.setHint("4-digit PIN");
        pinField.setSingleLine(true);
        GradientDrawable fieldBg = new GradientDrawable();
        fieldBg.setColor(Color.parseColor("#11151a"));
        fieldBg.setCornerRadius(dp(8));
        fieldBg.setStroke(dp(1), COL_CARD_EDGE);
        pinField.setBackground(fieldBg);
        pinField.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.addView(pinField);

        Button save = new Button(this);
        save.setText("Save PIN");
        save.setAllCaps(false);
        save.setTextColor(Color.WHITE);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(COL_ACCENT_DIM);
        sbg.setCornerRadius(dp(10));
        save.setBackground(sbg);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(42));
        sl.topMargin = dp(10);
        save.setLayoutParams(sl);
        save.setOnClickListener(v -> {
            String pin = pinField.getText().toString().trim();
            if (pin.isEmpty()) {
                Toast.makeText(MainActivity.this, "Enter a PIN.", Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.setParentPin(MainActivity.this, pin);
            Toast.makeText(MainActivity.this, "PIN saved.", Toast.LENGTH_SHORT).show();
        });
        box.addView(save);

        return box;
    }

    private static boolean isValidRelay(String url) {
        return url != null && (url.startsWith("ws://") || url.startsWith("wss://"))
            && url.length() > 6;
    }

    // ---------- status detection ----------

    private boolean isAccessibilityEnabled() {
        try {
            AccessibilityManager am =
                (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) return false;
            List<AccessibilityServiceInfo> list =
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            String want = getPackageName() + "/.AccessibilityControlService";
            String wantAlt = getPackageName() + "/" + getPackageName() + ".AccessibilityControlService";
            for (AccessibilityServiceInfo info : list) {
                String id = info.getId();
                if (id == null) continue;
                if (id.equalsIgnoreCase(want) || id.equalsIgnoreCase(wantAlt)
                    || id.toLowerCase().contains("perchguard")) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isUsageAccessGranted() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = ops.unsafeCheckOpNoThrow("android:get_usage_stats",
                    Process.myUid(), getPackageName());
            } else {
                mode = ops.checkOpNoThrow("android:get_usage_stats",
                    Process.myUid(), getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCaptureRunning() {
        return ScreenService.isRunning();
    }

    private boolean isRelayValid() {
        return isValidRelay(Prefs.relayUrl(this));
    }

    private boolean isLocationGranted() {
        int fine = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
        int coarse = checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION);
        return fine == android.content.pm.PackageManager.PERMISSION_GRANTED
            || coarse == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private boolean isDeviceAdminActive() {
        try {
            DevicePolicyManager dpm =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm == null) return false;
            return dpm.isAdminActive(new ComponentName(this, AdminReceiver.class));
        } catch (Exception e) {
            return false;
        }
    }

    private void requestDeviceAdmin() {
        if (isDeviceAdminActive()) {
            Toast.makeText(this, "Already on.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
            new ComponentName(this, AdminReceiver.class));
        i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "So the app can't be removed without the parent's PIN.");
        try {
            startActivityForResult(i, REQ_DEVICE_ADMIN);
        } catch (Exception e) {
            Toast.makeText(this, "This device blocked the admin prompt.",
                Toast.LENGTH_LONG).show();
        }
    }

    private void requestLocationPermission() {
        if (!isLocationGranted()) {
            requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29
            && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this,
                "For background tracking, choose \"Allow all the time\" on the next screen.",
                Toast.LENGTH_LONG).show();
            requestPermissions(new String[]{
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            }, REQ_LOCATION_BG);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] perms, int[] results) {
        super.onRequestPermissionsResult(requestCode, perms, results);
        if (requestCode == REQ_LOCATION) {
            if (isLocationGranted() && Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }, REQ_LOCATION_BG);
            }
        }
        refreshStatus();
    }

    private void refreshStatus() {
        boolean ax = isAccessibilityEnabled();
        boolean usage = isUsageAccessGranted();
        boolean capture = isCaptureRunning();
        boolean relay = isRelayValid();
        boolean location = isLocationGranted();
        boolean admin = isDeviceAdminActive();

        rowAccessibility.setGranted(ax);
        rowUsage.setGranted(usage);
        rowCapture.setGranted(capture);
        rowLocation.setGranted(location);
        rowRelay.setGranted(relay);
        rowAdmin.setGranted(admin);

        // Location is strongly recommended but not strictly required to start.
        boolean allReady = ax && usage && relay;
        boolean fullyLive = allReady && capture;

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        if (fullyLive) {
            bg.setColor(COL_OK);
            startBtn.setText("Monitoring active — tap to restart");
        } else if (allReady) {
            bg.setColor(COL_ACCENT);
            startBtn.setText("Start monitoring");
        } else {
            bg.setColor(COL_ACCENT_DIM);
            startBtn.setText("Finish setup to start");
        }
        startBtn.setBackground(bg);
    }

    // ---------- start flow ----------

    private void handleStart() {
        if (!isRelayValid()) {
            if (relaySection.getVisibility() != View.VISIBLE) {
                relaySection.setVisibility(View.VISIBLE);
                advancedToggle.setText("▾ Relay URL");
            }
            relayField.requestFocus();
            Toast.makeText(this, "Add the relay address first (step 4).",
                Toast.LENGTH_LONG).show();
            return;
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this,
                "Turn PerchGuard on in Accessibility, then come back.",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!isUsageAccessGranted()) {
            Toast.makeText(this,
                "Allow PerchGuard in Usage access, then come back.",
                Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            return;
        }
        requestCapture();
    }

    private void requestCapture() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this,
                "This device blocked the screen-share prompt.",
                Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this,
                "Screen share not granted. Tap Start again to retry.",
                Toast.LENGTH_LONG).show();
            return;
        }
        Intent svc = new Intent(this, ScreenService.class);
        svc.putExtra(ScreenService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(ScreenService.EXTRA_RESULT_DATA, data);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
        else startService(svc);
        Toast.makeText(this, "Monitoring started.", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    // ---------- step row ----------

    private final class StepRow {
        final View view;
        final TextView status;
        final Button action;
        final String actionLabelDefault;

        StepRow(int number, String title, String desc, String actionLabel,
                View.OnClickListener onClick) {
            this.actionLabelDefault = actionLabel;

            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(glassCard(16));
            if (Build.VERSION.SDK_INT >= 21) card.setElevation(dp(2));
            int pad = dp(14);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(12);
            card.setLayoutParams(lp);

            status = new TextView(MainActivity.this);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            status.setGravity(Gravity.CENTER);
            status.setWidth(dp(40));
            card.addView(status);

            LinearLayout mid = new LinearLayout(MainActivity.this);
            mid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams midLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            midLp.leftMargin = dp(10);
            midLp.rightMargin = dp(10);
            mid.setLayoutParams(midLp);
            TextView t = new TextView(MainActivity.this);
            t.setText(number + ". " + title);
            t.setTextColor(COL_TEXT);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            TextView d = new TextView(MainActivity.this);
            d.setText(desc);
            d.setTextColor(COL_MUTED);
            d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            mid.addView(t);
            mid.addView(d);
            card.addView(mid);

            action = new Button(MainActivity.this);
            action.setText(actionLabel);
            action.setAllCaps(false);
            action.setTextColor(Color.WHITE);
            action.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            GradientDrawable abg = new GradientDrawable();
            abg.setColor(COL_ACCENT_DIM);
            abg.setCornerRadius(dp(10));
            action.setBackground(abg);
            action.setPadding(dp(12), 0, dp(12), 0);
            action.setMinWidth(dp(100));
            action.setMinimumWidth(dp(100));
            action.setOnClickListener(onClick);
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            action.setLayoutParams(ap);
            card.addView(action);

            this.view = card;
            setGranted(false);
        }

        void setGranted(boolean ok) {
            if (ok) {
                status.setText("✓");
                status.setTextColor(COL_OK);
                action.setText("Done");
                action.setEnabled(true);
                GradientDrawable abg = new GradientDrawable();
                abg.setColor(Color.parseColor("#1f3a28"));
                abg.setCornerRadius(dp(10));
                action.setBackground(abg);
            } else {
                status.setText("○");
                status.setTextColor(COL_PENDING);
                action.setText(actionLabelDefault);
                action.setEnabled(true);
                GradientDrawable abg = new GradientDrawable();
                abg.setColor(COL_ACCENT);
                abg.setCornerRadius(dp(10));
                action.setBackground(abg);
            }
        }
    }

    private int dp(int d) {
        return (int) Math.round(d * getResources().getDisplayMetrics().density);
    }

    private android.graphics.drawable.Drawable glassCard(int radiusDp) {
        GradientDrawable base = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{COL_CARD, COL_CARD_2});
        base.setCornerRadius(dp(radiusDp));
        base.setStroke(dp(1), COL_CARD_EDGE);

        GradientDrawable hi = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{COL_INNER_HI, 0x00FFFFFF});
        hi.setCornerRadius(dp(radiusDp));

        android.graphics.drawable.LayerDrawable l =
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{base, hi});
        int inset = dp(1);
        int bottomInset = (int) (dp(radiusDp) * 0.75f);
        l.setLayerInset(1, inset, inset, inset, bottomInset);
        return l;
    }

    private void checkForUpdateBtn(Button btn) {
        btn.setText("Checking…");
        btn.setEnabled(false);
        new UpdateChecker(this).check(new UpdateChecker.Listener() {
            @Override public void onNoUpdate(int installed, int latest) {
                btn.setText("Up to date (v" + BuildConfig.VERSION_NAME + ")");
                btn.setEnabled(true);
            }
            @Override public void onUpdateAvailable(int latest, String tag,
                                                     String assetUrl, String assetName) {
                btn.setText("Install update " + tag);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(COL_ACCENT);
                bg.setCornerRadius(dp(14));
                btn.setBackground(bg);
                btn.setEnabled(true);
                btn.setOnClickListener(v ->
                    new UpdateChecker(MainActivity.this)
                        .downloadAndInstall(assetUrl, assetName, MainActivity.this));
            }
            @Override public void onError(String message) {
                btn.setText("Update check failed — tap to retry");
                btn.setEnabled(true);
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }
}

package com.perchguard.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * AccessibilityControlService — the hands and eyes.
 *
 * - Watches TYPE_WINDOW_STATE_CHANGED to enforce app blocks and bedtime.
 * - Dispatches gestures (tap/swipe) in the device's native screen coordinates.
 * - Types into the focused input, sends global actions (home/back/recents/lock).
 */
public class AccessibilityControlService extends AccessibilityService {
    private static final String TAG = "PerchGuard.AX";
    private static volatile AccessibilityControlService instance;

    public static AccessibilityControlService getInstance() {
        return instance;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "accessibility service connected");
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String pkgStr = pkg.toString();
        if ("com.perchguard.app".equals(pkgStr)) return;

        // Notify the relay of the foreground change — the dashboard shows this.
        ScreenService.sendEvent(Events.foreground(pkgStr));

        Policy policy = Policy.get(this);
        if (policy.isBlocked(pkgStr)) {
            Log.i(TAG, "blocking " + pkgStr);
            ScreenService.sendEvent(Events.blockTriggered(pkgStr));
            try {
                Intent i = new Intent(this, BlockActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                i.putExtra(BlockActivity.EXTRA_PKG, pkgStr);
                i.putExtra(BlockActivity.EXTRA_REASON,
                    policy.bedtimeEnabled && policy.isBedtimeNow() ? "bedtime" : "blocked");
                startActivity(i);
            } catch (Exception e) {
                Log.w(TAG, "launch block activity failed", e);
            }
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    // ---------- gesture + input dispatch ----------

    public boolean tap(int x, int y) {
        return swipe(x, y, x, y, 30);
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int durMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
            new GestureDescription.StrokeDescription(p, 0, Math.max(1, durMs));
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        return dispatchGesture(gesture, null, null);
    }

    public boolean typeText(String text) {
        if (text == null) return false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (focused == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    public boolean globalKey(String code) {
        if (code == null) return false;
        switch (code.toLowerCase()) {
            case "home":           return performGlobalAction(GLOBAL_ACTION_HOME);
            case "back":           return performGlobalAction(GLOBAL_ACTION_BACK);
            case "recents":        return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case "notifications":  return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case "quick_settings": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case "power_dialog":   return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            case "lock":
                if (Build.VERSION.SDK_INT >= 28) return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                return false;
            default: return false;
        }
    }
}

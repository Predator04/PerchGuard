package com.perchguard.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_NOTIF = 1002;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER_HORIZONTAL);
        ll.setPadding(60, 80, 60, 80);
        ll.setBackgroundColor(Color.parseColor("#101418"));

        TextView title = new TextView(this);
        title.setText("PerchGuard");
        title.setTextSize(30);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("Live view + remote control + limits");
        sub.setTextSize(14);
        sub.setTextColor(Color.GRAY);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 16, 0, 32);

        TextView relayLabel = new TextView(this);
        relayLabel.setText("Relay URL");
        relayLabel.setTextColor(Color.WHITE);
        relayLabel.setPadding(0, 16, 0, 4);

        EditText relayField = new EditText(this);
        relayField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        relayField.setText(Prefs.relayUrl(this));
        relayField.setTextColor(Color.WHITE);
        relayField.setHintTextColor(Color.GRAY);
        relayField.setBackgroundColor(Color.parseColor("#1a1f26"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 16;
        relayField.setLayoutParams(lp);
        relayField.setPadding(24, 24, 24, 24);

        Button saveUrl = new Button(this);
        saveUrl.setText("Save relay URL");
        saveUrl.setOnClickListener(v -> {
            Prefs.setRelayUrl(this, relayField.getText().toString().trim());
            Toast.makeText(this, "Saved — restart monitoring to reconnect", Toast.LENGTH_SHORT).show();
        });

        Button start = new Button(this);
        start.setText("Start monitoring");
        start.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        });

        Button usage = new Button(this);
        usage.setText("Grant usage access");
        usage.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)));

        Button accessibility = new Button(this);
        accessibility.setText("Enable PerchGuard accessibility");
        accessibility.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        Button overlay = new Button(this);
        overlay.setText("Allow overlay (block screen)");
        overlay.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)));

        Button guard = new Button(this);
        guard.setText("Start guard service");
        guard.setOnClickListener(v -> {
            Intent svc = new Intent(this, GuardService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
            Toast.makeText(this, "Guard started", Toast.LENGTH_SHORT).show();
        });

        Button stop = new Button(this);
        stop.setText("Stop monitoring");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, ScreenService.class));
            stopService(new Intent(this, GuardService.class));
        });

        ll.addView(title);
        ll.addView(sub);
        ll.addView(relayLabel);
        ll.addView(relayField);
        ll.addView(saveUrl);
        ll.addView(start);
        ll.addView(usage);
        ll.addView(accessibility);
        ll.addView(overlay);
        ll.addView(guard);
        ll.addView(stop);
        setContentView(ll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK) {
            Intent svc = new Intent(this, ScreenService.class);
            svc.putExtra(ScreenService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ScreenService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
        }
    }
}

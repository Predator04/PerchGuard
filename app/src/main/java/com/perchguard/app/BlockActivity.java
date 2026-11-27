package com.perchguard.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * BlockActivity — fullscreen "you can't use this right now" screen.
 * Launched by the accessibility service when the child opens a blocked app or
 * tries to use any app during bedtime.
 */
public class BlockActivity extends Activity {
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_REASON = "reason";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        String reason = getIntent().getStringExtra(EXTRA_REASON);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setGravity(Gravity.CENTER);
        ll.setBackgroundColor(Color.parseColor("#0b0d10"));
        ll.setPadding(80, 80, 80, 80);

        TextView title = new TextView(this);
        title.setText("bedtime".equals(reason) ? "It's bedtime" : "App blocked");
        title.setTextSize(42);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);

        TextView sub = new TextView(this);
        sub.setText("bedtime".equals(reason)
            ? "This app is unavailable until morning."
            : (pkg != null ? pkg : "") + " is blocked.");
        sub.setTextSize(18);
        sub.setTextColor(Color.parseColor("#b8bec7"));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 32, 0, 0);

        ll.addView(title);
        ll.addView(sub);
        setContentView(ll);
    }

    @Override
    public void onBackPressed() { /* trap back */ }
}

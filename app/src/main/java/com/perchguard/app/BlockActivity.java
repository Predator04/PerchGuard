package com.perchguard.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * BlockActivity — fullscreen "you can't use this right now" screen.
 * Launched by the accessibility service when the child opens a blocked app or
 * tries to use any app during bedtime. A "Parent" button lets an adult enter
 * their PIN to pause blocking for 30 minutes.
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

        Button parentBtn = new Button(this);
        parentBtn.setText("Parent");
        parentBtn.setAllCaps(false);
        parentBtn.setTextSize(16);
        parentBtn.setTextColor(Color.WHITE);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(Color.parseColor("#2B3D61"));
        pbg.setCornerRadius(12 * getResources().getDisplayMetrics().density);
        parentBtn.setBackground(pbg);
        parentBtn.setPadding(40, 18, 40, 18);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = 64;
        parentBtn.setLayoutParams(plp);
        parentBtn.setOnClickListener(v -> showParentPin());

        ll.addView(title);
        ll.addView(sub);
        ll.addView(parentBtn);
        setContentView(ll);
    }

    private void showParentPin() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Parent PIN");
        new AlertDialog.Builder(this)
            .setTitle("Parent unlock")
            .setMessage("Enter your PIN to pause blocking for 30 minutes.")
            .setView(input)
            .setPositiveButton("Unlock", (dialog, which) -> {
                String pin = input.getText().toString();
                if (pin.equals(Prefs.parentPin(this))) {
                    Policy.get(this).suspendFor(this, 30);
                    Toast.makeText(this, "Blocking paused for 30 minutes", Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    Toast.makeText(this, "Wrong PIN", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onBackPressed() { /* trap back */ }
}

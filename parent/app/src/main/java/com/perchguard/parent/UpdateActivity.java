package com.perchguard.parent;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Standalone updater screen: check, offer install. Launched from Settings. */
public class UpdateActivity extends Activity {

    private TextView status;
    private Button checkBtn;
    private Button installBtn;

    private String pendingUrl;
    private String pendingName;
    private int pendingLatest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 14);
        sv.setPadding(p, p, p, p);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = Ui.row(this);
        TextView title = Ui.title(this, "Update PerchGuard");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tLp);
        Button back = Ui.button(this, "Back");
        back.setOnClickListener(v -> finish());
        header.addView(title);
        header.addView(back);
        root.addView(header, Ui.lpMatch());

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams cLp = Ui.lpMatch();
        cLp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(cLp);

        TextView installed = Ui.muted(this,
            "Installed: v" + BuildConfig.VERSION_NAME
            + " (code " + BuildConfig.VERSION_CODE + ")");
        card.addView(installed);

        status = new TextView(this);
        status.setTextColor(Ui.TEXT);
        status.setText("Tap Check to look for a newer release on GitHub.");
        LinearLayout.LayoutParams sLp = Ui.lpMatch();
        sLp.topMargin = Ui.dp(this, 8);
        status.setLayoutParams(sLp);
        card.addView(status);

        root.addView(card);

        checkBtn = Ui.primaryButton(this, "Check for update");
        LinearLayout.LayoutParams bLp = Ui.lpMatch();
        bLp.topMargin = Ui.dp(this, 14);
        checkBtn.setLayoutParams(bLp);
        checkBtn.setOnClickListener(v -> doCheck());
        root.addView(checkBtn);

        installBtn = Ui.primaryButton(this, "Download + install");
        LinearLayout.LayoutParams iLp = Ui.lpMatch();
        iLp.topMargin = Ui.dp(this, 10);
        installBtn.setLayoutParams(iLp);
        installBtn.setEnabled(false);
        installBtn.setAlpha(0.5f);
        installBtn.setOnClickListener(v -> doInstall());
        root.addView(installBtn);

        Button sysSettings = Ui.button(this, "Allow unknown apps…");
        LinearLayout.LayoutParams ssLp = Ui.lpMatch();
        ssLp.topMargin = Ui.dp(this, 10);
        sysSettings.setLayoutParams(ssLp);
        sysSettings.setOnClickListener(v -> openUnknownSources());
        root.addView(sysSettings);

        sv.addView(root);
        setContentView(sv);
    }

    private void doCheck() {
        checkBtn.setEnabled(false);
        status.setText("Checking GitHub releases…");
        new UpdateChecker(this).check(new UpdateChecker.Listener() {
            @Override public void onNoUpdate(int installed, int latest) {
                status.setText("You're up to date (installed code " + installed
                    + ", latest " + latest + ").");
                checkBtn.setEnabled(true);
            }
            @Override public void onUpdateAvailable(int latest, String tag,
                                                    String assetUrl, String assetName) {
                pendingUrl = assetUrl;
                pendingName = assetName;
                pendingLatest = latest;
                status.setText("Update available: " + tag + " (" + assetName + ")");
                installBtn.setEnabled(true);
                installBtn.setAlpha(1f);
                checkBtn.setEnabled(true);
            }
            @Override public void onError(String message) {
                status.setText("Check failed: " + message);
                checkBtn.setEnabled(true);
            }
        });
    }

    private void doInstall() {
        if (pendingUrl == null) return;
        new UpdateChecker(this).downloadAndInstall(pendingUrl, pendingName, this);
    }

    private void openUnknownSources() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Install unknown apps",
                Toast.LENGTH_LONG).show();
        }
    }
}

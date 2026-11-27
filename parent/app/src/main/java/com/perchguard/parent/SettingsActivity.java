package com.perchguard.parent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {

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
        TextView t = Ui.title(this, "Settings");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tLp);
        Button back = Ui.button(this, "Back");
        back.setOnClickListener(v -> finish());
        header.addView(t);
        header.addView(back);
        root.addView(header, Ui.lpMatch());

        TextView blurb = Ui.muted(this, "Relay URL + bearer token. Defaults are pre-filled.");
        LinearLayout.LayoutParams bLp = Ui.lpMatch();
        bLp.topMargin = Ui.dp(this, 6);
        blurb.setLayoutParams(bLp);
        root.addView(blurb);

        LinearLayout card = Ui.card(this);
        LinearLayout.LayoutParams cLp = Ui.lpMatch();
        cLp.topMargin = Ui.dp(this, 12);
        card.setLayoutParams(cLp);

        card.addView(Ui.muted(this, "Relay URL"));
        EditText urlField = Ui.edit(this, "https://…", Prefs.url(this), false);
        LinearLayout.LayoutParams uLp = Ui.lpMatch();
        uLp.topMargin = Ui.dp(this, 4);
        urlField.setLayoutParams(uLp);
        card.addView(urlField);

        TextView gap = Ui.muted(this, "Token");
        LinearLayout.LayoutParams gLp = Ui.lpMatch();
        gLp.topMargin = Ui.dp(this, 10);
        gap.setLayoutParams(gLp);
        card.addView(gap);

        EditText tokenField = Ui.edit(this, "bearer…", Prefs.token(this), false);
        LinearLayout.LayoutParams tkLp = Ui.lpMatch();
        tkLp.topMargin = Ui.dp(this, 4);
        tokenField.setLayoutParams(tkLp);
        card.addView(tokenField);

        root.addView(card);

        Button save = Ui.primaryButton(this, "Save");
        LinearLayout.LayoutParams sLp = Ui.lpMatch();
        sLp.topMargin = Ui.dp(this, 14);
        save.setLayoutParams(sLp);
        save.setOnClickListener(v -> {
            Prefs.setUrl(this, urlField.getText().toString().trim());
            Prefs.setToken(this, tokenField.getText().toString().trim());
            Toast.makeText(this, "Saved. Reconnecting on return.",
                           Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        Button reset = Ui.button(this, "Reset to defaults");
        LinearLayout.LayoutParams rLp = Ui.lpMatch();
        rLp.topMargin = Ui.dp(this, 10);
        reset.setLayoutParams(rLp);
        reset.setOnClickListener(v -> {
            urlField.setText(Prefs.DEFAULT_URL);
            tokenField.setText(Prefs.DEFAULT_TOKEN);
        });
        root.addView(reset);

        LinearLayout tools = Ui.card(this);
        LinearLayout.LayoutParams toLp = Ui.lpMatch();
        toLp.topMargin = Ui.dp(this, 18);
        tools.setLayoutParams(toLp);

        tools.addView(Ui.label(this, "Install + update"));
        tools.addView(Ui.muted(this,
            "Update this parent app, or push an APK to the child device."));

        Button update = Ui.button(this, "Check for updates");
        LinearLayout.LayoutParams upLp = Ui.lpMatch();
        upLp.topMargin = Ui.dp(this, 10);
        update.setLayoutParams(upLp);
        update.setOnClickListener(v ->
            startActivity(new Intent(this, UpdateActivity.class)));
        tools.addView(update);

        Button push = Ui.button(this, "Push APK to child");
        LinearLayout.LayoutParams pshLp = Ui.lpMatch();
        pshLp.topMargin = Ui.dp(this, 8);
        push.setLayoutParams(pshLp);
        push.setOnClickListener(v ->
            startActivity(new Intent(this, PushApkActivity.class)));
        tools.addView(push);

        root.addView(tools);

        TextView ver = Ui.muted(this,
            "PerchGuard parent v" + BuildConfig.VERSION_NAME
            + " (code " + BuildConfig.VERSION_CODE + ")");
        LinearLayout.LayoutParams vLp = Ui.lpMatch();
        vLp.topMargin = Ui.dp(this, 14);
        vLp.bottomMargin = Ui.dp(this, 20);
        ver.setLayoutParams(vLp);
        root.addView(ver);

        sv.addView(root);
        setContentView(sv);
    }
}

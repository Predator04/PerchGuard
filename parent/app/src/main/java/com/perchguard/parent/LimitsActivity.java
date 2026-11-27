package com.perchguard.parent;

import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Second screen — blocklist, per-app + total minutes, bedtime. Writes via /api/policy. */
public class LimitsActivity extends Activity {

    private RelayClient relay;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout appsList;
    private EditText totalMinutesField;
    private EditText bedStartField;
    private EditText bedEndField;
    private CheckBox bedEnabled;
    private EditText addPkgField;
    private TextView statusLine;

    private final Map<String, Row> rows = new HashMap<>();

    private static final class Row {
        final CheckBox block;
        final EditText minutes;
        Row(CheckBox b, EditText m) { block = b; minutes = m; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        relay = new RelayClient(this);
        setContentView(buildUi());
        refresh();
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        int p = Ui.dp(this, 12);
        sv.setPadding(p, p, p, p);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Header
        LinearLayout header = Ui.row(this);
        TextView t = Ui.title(this, "Limits & schedule");
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        t.setLayoutParams(tLp);
        Button back = Ui.button(this, "Back");
        back.setOnClickListener(v -> finish());
        header.addView(t);
        header.addView(back);
        root.addView(header, Ui.lpMatch());

        statusLine = Ui.muted(this, "Loading current policy…");
        LinearLayout.LayoutParams slLp = Ui.lpMatch();
        slLp.topMargin = Ui.dp(this, 4);
        statusLine.setLayoutParams(slLp);
        root.addView(statusLine);

        // Bedtime card
        LinearLayout bedCard = Ui.card(this);
        LinearLayout.LayoutParams bedCardLp = Ui.lpMatch();
        bedCardLp.topMargin = Ui.dp(this, 10);
        bedCard.setLayoutParams(bedCardLp);
        bedCard.addView(Ui.title(this, "Bedtime"));

        bedEnabled = Ui.check(this, "Enabled", false);
        LinearLayout.LayoutParams beLp = Ui.lpMatch();
        beLp.topMargin = Ui.dp(this, 6);
        bedEnabled.setLayoutParams(beLp);
        bedCard.addView(bedEnabled);

        LinearLayout bedRow = Ui.row(this);
        LinearLayout.LayoutParams bedRowLp = Ui.lpMatch();
        bedRowLp.topMargin = Ui.dp(this, 6);
        bedRow.setLayoutParams(bedRowLp);

        bedStartField = Ui.edit(this, "Start HH:MM", "20:00", false);
        bedEndField   = Ui.edit(this, "End HH:MM", "07:00", false);
        LinearLayout.LayoutParams bsLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        LinearLayout.LayoutParams beLp2 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        beLp2.leftMargin = Ui.dp(this, 8);
        bedStartField.setLayoutParams(bsLp);
        bedEndField.setLayoutParams(beLp2);

        bedRow.addView(bedStartField);
        bedRow.addView(bedEndField);
        bedCard.addView(bedRow);

        root.addView(bedCard);

        // Total minutes card
        LinearLayout totalCard = Ui.card(this);
        LinearLayout.LayoutParams totalLp = Ui.lpMatch();
        totalLp.topMargin = Ui.dp(this, 10);
        totalCard.setLayoutParams(totalLp);
        totalCard.addView(Ui.title(this, "Daily total"));
        totalCard.addView(Ui.muted(this, "0 = no limit"));
        totalMinutesField = Ui.edit(this, "Minutes per day", "0", true);
        LinearLayout.LayoutParams tmLp = Ui.lpMatch();
        tmLp.topMargin = Ui.dp(this, 6);
        totalMinutesField.setLayoutParams(tmLp);
        totalCard.addView(totalMinutesField);
        root.addView(totalCard);

        // Per-app card
        LinearLayout appCard = Ui.card(this);
        LinearLayout.LayoutParams appLp = Ui.lpMatch();
        appLp.topMargin = Ui.dp(this, 10);
        appCard.setLayoutParams(appLp);
        appCard.addView(Ui.title(this, "Apps"));
        appCard.addView(Ui.muted(this, "Block or set per-app minutes. 0 = no limit."));

        appsList = new LinearLayout(this);
        appsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams alLp = Ui.lpMatch();
        alLp.topMargin = Ui.dp(this, 6);
        appsList.setLayoutParams(alLp);
        appCard.addView(appsList);

        LinearLayout addRow = Ui.row(this);
        LinearLayout.LayoutParams addRowLp = Ui.lpMatch();
        addRowLp.topMargin = Ui.dp(this, 10);
        addRow.setLayoutParams(addRowLp);

        addPkgField = Ui.edit(this, "com.example.app", null, false);
        LinearLayout.LayoutParams apfLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addPkgField.setLayoutParams(apfLp);

        Button addBtn = Ui.button(this, "Add");
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = Ui.dp(this, 8);
        addBtn.setLayoutParams(addLp);
        addBtn.setOnClickListener(v -> {
            String pkg = addPkgField.getText().toString().trim();
            if (!pkg.isEmpty() && !rows.containsKey(pkg)) {
                addAppRow(pkg, false, 0);
                addPkgField.setText("");
            }
        });
        addRow.addView(addPkgField);
        addRow.addView(addBtn);
        appCard.addView(addRow);

        root.addView(appCard);

        // Save button
        Button save = Ui.primaryButton(this, "Save policy");
        LinearLayout.LayoutParams saveLp = Ui.lpMatch();
        saveLp.topMargin = Ui.dp(this, 14);
        saveLp.bottomMargin = Ui.dp(this, 8);
        save.setLayoutParams(saveLp);
        save.setOnClickListener(v -> savePolicy());
        root.addView(save);

        Button refreshBtn = Ui.button(this, "Refresh");
        LinearLayout.LayoutParams refLp = Ui.lpMatch();
        refLp.bottomMargin = Ui.dp(this, 20);
        refreshBtn.setLayoutParams(refLp);
        refreshBtn.setOnClickListener(v -> refresh());
        root.addView(refreshBtn);

        sv.addView(root);
        return sv;
    }

    private void addAppRow(String pkg, boolean blocked, int minutes) {
        LinearLayout row = Ui.row(this);
        LinearLayout.LayoutParams rowLp = Ui.lpMatch();
        rowLp.topMargin = Ui.dp(this, 6);
        row.setLayoutParams(rowLp);

        TextView name = Ui.label(this, pkg);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        name.setLayoutParams(nameLp);

        CheckBox block = Ui.check(this, "Block", blocked);

        EditText mins = Ui.edit(this, "min", String.valueOf(minutes), true);
        LinearLayout.LayoutParams minsLp = new LinearLayout.LayoutParams(
                Ui.dp(this, 70), ViewGroup.LayoutParams.WRAP_CONTENT);
        minsLp.leftMargin = Ui.dp(this, 8);
        mins.setLayoutParams(minsLp);

        row.addView(name);
        row.addView(block);
        row.addView(mins);

        appsList.addView(row);
        rows.put(pkg, new Row(block, mins));
    }

    private void refresh() {
        statusLine.setText("Loading…");
        relay.getState(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                main.post(() -> statusLine.setText("Load failed: " + e.getMessage()));
            }
            @Override public void onResponse(Call call, Response resp) {
                String body = null;
                try {
                    if (!resp.isSuccessful()) {
                        final int code = resp.code();
                        main.post(() -> statusLine.setText("HTTP " + code));
                        return;
                    }
                    ResponseBody rb = resp.body();
                    if (rb != null) body = rb.string();
                } catch (Exception e) {
                    main.post(() -> statusLine.setText("Parse failed"));
                    return;
                } finally { resp.close(); }
                final String bodyF = body;
                main.post(() -> applyState(bodyF));
            }
        });
    }

    private void applyState(String bodyJson) {
        if (bodyJson == null) return;
        try {
            JSONObject state = new JSONObject(bodyJson);
            JSONObject policy = state.optJSONObject("policy");
            JSONObject usage = state.optJSONObject("usage");

            LinkedHashSet<String> knownPkgs = new LinkedHashSet<>();
            List<String> blocked = new ArrayList<>();
            Map<String, Integer> limits = new HashMap<>();
            int totalMins = 0;
            JSONObject bedtime = null;

            if (policy != null) {
                JSONArray bl = policy.optJSONArray("blocked");
                if (bl != null) {
                    for (int i = 0; i < bl.length(); i++) {
                        String p = bl.optString(i, null);
                        if (p != null) { blocked.add(p); knownPkgs.add(p); }
                    }
                }
                JSONObject lim = policy.optJSONObject("limits");
                if (lim != null) {
                    for (java.util.Iterator<String> it = lim.keys(); it.hasNext(); ) {
                        String k = it.next();
                        limits.put(k, lim.optInt(k, 0));
                        knownPkgs.add(k);
                    }
                }
                totalMins = policy.optInt("total_minutes", 0);
                bedtime = policy.optJSONObject("bedtime");
            }
            if (usage != null) {
                JSONObject byPkg = usage.optJSONObject("by_pkg");
                if (byPkg != null) {
                    for (java.util.Iterator<String> it = byPkg.keys(); it.hasNext(); ) {
                        knownPkgs.add(it.next());
                    }
                }
            }

            // Preserve any pkgs the user added locally that aren't in state yet.
            knownPkgs.addAll(new ArrayList<>(rows.keySet()));

            // Rebuild rows.
            appsList.removeAllViews();
            rows.clear();
            List<String> sorted = new ArrayList<>(knownPkgs);
            Collections.sort(sorted);
            for (String p : sorted) {
                Integer m = limits.get(p);
                addAppRow(p, blocked.contains(p), m == null ? 0 : m);
            }

            totalMinutesField.setText(String.valueOf(totalMins));
            if (bedtime != null) {
                bedStartField.setText(bedtime.optString("start", "20:00"));
                bedEndField.setText(bedtime.optString("end", "07:00"));
                bedEnabled.setChecked(bedtime.optBoolean("enabled", false));
            }
            statusLine.setText("Loaded " + sorted.size() + " app(s).");
        } catch (Exception e) {
            statusLine.setText("Parse error: " + e.getMessage());
        }
    }

    private void savePolicy() {
        try {
            JSONArray blocked = new JSONArray();
            JSONObject limits = new JSONObject();
            for (Map.Entry<String, Row> e : rows.entrySet()) {
                if (e.getValue().block.isChecked()) blocked.put(e.getKey());
                int m = parseInt(e.getValue().minutes.getText().toString(), 0);
                if (m > 0) limits.put(e.getKey(), m);
            }
            JSONObject bedtime = new JSONObject();
            bedtime.put("start", bedStartField.getText().toString().trim());
            bedtime.put("end", bedEndField.getText().toString().trim());
            bedtime.put("enabled", bedEnabled.isChecked());

            JSONObject body = new JSONObject();
            body.put("blocked", blocked);
            body.put("limits", limits);
            body.put("total_minutes", parseInt(totalMinutesField.getText().toString(), 0));
            body.put("bedtime", bedtime);

            statusLine.setText("Saving…");
            relay.post("/api/policy", body, new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    main.post(() -> statusLine.setText("Save failed: " + e.getMessage()));
                }
                @Override public void onResponse(Call call, Response resp) {
                    final boolean ok = resp.isSuccessful();
                    final int code = resp.code();
                    resp.close();
                    main.post(() -> {
                        if (ok) {
                            statusLine.setText("Saved.");
                            Toast.makeText(LimitsActivity.this, "Policy saved",
                                           Toast.LENGTH_SHORT).show();
                        } else {
                            statusLine.setText("Save HTTP " + code);
                        }
                    });
                }
            });
        } catch (Exception e) {
            statusLine.setText("Bad input: " + e.getMessage());
        }
    }

    private static int parseInt(String s, int def) {
        if (s == null) return def;
        s = s.trim();
        if (s.isEmpty()) return def;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return def; }
    }
}

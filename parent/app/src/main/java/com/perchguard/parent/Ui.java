package com.perchguard.parent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Dark "glass" UI primitives — gradient fills, hairline inner highlight, subtle elevation. */
public final class Ui {
    public static final int BG        = 0xFF080A10;
    public static final int BG_2      = 0xFF0F1320;
    public static final int SURFACE   = 0xFF171A24;
    public static final int SURFACE_2 = 0xFF1E2230;
    public static final int STROKE    = 0x26FFFFFF;
    public static final int INNER_HI  = 0x1AFFFFFF;
    public static final int TEXT      = 0xFFE9ECF4;
    public static final int MUTED     = 0xFF8E95A6;
    public static final int ACCENT    = 0xFF8AB4F8;
    public static final int ACCENT_2  = 0xFF5B8DEF;
    public static final int WARN      = 0xFFF6B86B;
    public static final int OK        = 0xFF7EE0A6;
    public static final int BAD       = 0xFFFF7B7B;

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics());
    }

    public static GradientDrawable roundedFill(int fill, int stroke, int radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(dp(c, 1), stroke);
        return g;
    }

    /** Vertical gradient from topFill → bottomFill with hairline inner highlight on top. */
    public static Drawable glass(Context c, int topFill, int bottomFill,
                                  int stroke, int radiusDp) {
        GradientDrawable base = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{topFill, bottomFill});
        base.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) base.setStroke(dp(c, 1), stroke);

        GradientDrawable highlight = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{INNER_HI, 0x00FFFFFF});
        highlight.setCornerRadius(dp(c, radiusDp));

        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, highlight});
        int inset = dp(c, 1);
        int bottomInset = (int) (dp(c, radiusDp) * 0.75f);
        layers.setLayerInset(1, inset, inset, inset, bottomInset);
        return layers;
    }

    /** Wrap a drawable in a dark ripple so taps feel responsive. */
    public static Drawable rippled(Drawable d) {
        if (Build.VERSION.SDK_INT >= 21) {
            return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), d, null);
        }
        return d;
    }

    public static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setBackground(rippled(glass(c, 0xFF262B3B, 0xFF1A1E2A, STROKE, 12)));
        int p = dp(c, 10);
        b.setPadding(p + dp(c,4), p, p + dp(c,4), p);
        b.setStateListAnimator(null);
        return b;
    }

    public static Button primaryButton(Context c, String label) {
        Button b = button(c, label);
        b.setBackground(rippled(glass(c, ACCENT, ACCENT_2, 0, 12)));
        b.setTextColor(0xFF0A1428);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        return b;
    }

    public static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        return t;
    }

    public static TextView muted(Context c, String text) {
        TextView t = label(c, text);
        t.setTextColor(MUTED);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        return t;
    }

    public static TextView title(Context c, String text) {
        TextView t = label(c, text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static EditText edit(Context c, String hint, String value, boolean numeric) {
        EditText e = new EditText(c);
        e.setBackground(glass(c, 0xFF11141C, 0xFF191D28, STROKE, 12));
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setHint(hint);
        if (value != null) e.setText(value);
        if (numeric) {
            e.setInputType(InputType.TYPE_CLASS_NUMBER);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setSingleLine(true);
        }
        return e;
    }

    public static CheckBox check(Context c, String label, boolean checked) {
        CheckBox cb = new CheckBox(c);
        cb.setText(label);
        cb.setTextColor(TEXT);
        cb.setChecked(checked);
        return cb;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(glass(c, 0xFF1F2330, 0xFF14171F, STROKE, 16));
        if (Build.VERSION.SDK_INT >= 21) l.setElevation(dp(c, 2));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    /**
     * A collapsible-header card. Tapping the header shows/hides the body.
     * The body is the LinearLayout returned — append children to it.
     */
    public static LinearLayout collapsible(Context c, String title) {
        LinearLayout wrap = card(c);
        TextView head = new TextView(c);
        head.setText("▸ " + title);
        head.setTextColor(TEXT);
        head.setTypeface(head.getTypeface(), android.graphics.Typeface.BOLD);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        int hp = dp(c, 4);
        head.setPadding(hp, hp, hp, hp);
        wrap.addView(head);

        LinearLayout body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        int bp = dp(c, 6);
        body.setPadding(0, bp, 0, 0);
        wrap.addView(body);

        head.setOnClickListener(v -> {
            boolean showing = body.getVisibility() == View.VISIBLE;
            body.setVisibility(showing ? View.GONE : View.VISIBLE);
            head.setText((showing ? "▸ " : "▾ ") + title);
        });
        return body;
    }

    public static LinearLayout.LayoutParams lpMatch() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams lpWeight(int w, int h, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.weight = weight;
        return lp;
    }

    public static void setMargins(View v, Context c, int l, int t, int r, int b) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
            v.setLayoutParams(m);
        }
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
}

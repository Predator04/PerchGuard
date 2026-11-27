package com.perchguard.parent;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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

/** Programmatic UI helpers — small dark/glassy palette, rounded buttons, consistent paddings. */
public final class Ui {
    public static final int BG        = 0xFF0E0F13;
    public static final int SURFACE   = 0xFF1A1D24;
    public static final int SURFACE_2 = 0xFF242833;
    public static final int STROKE    = 0x33FFFFFF;
    public static final int TEXT      = 0xFFE6E8EC;
    public static final int MUTED     = 0xFF9AA0AE;
    public static final int ACCENT    = 0xFF8AB4F8;
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

    public static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setBackground(roundedFill(SURFACE_2, STROKE, 12, c));
        int p = dp(c, 10);
        b.setPadding(p + dp(c,4), p, p + dp(c,4), p);
        b.setStateListAnimator(null);
        return b;
    }

    public static Button primaryButton(Context c, String label) {
        Button b = button(c, label);
        b.setBackground(roundedFill(ACCENT, 0, 12, c));
        b.setTextColor(0xFF0B1220);
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
        e.setBackground(roundedFill(SURFACE_2, STROKE, 10, c));
        int p = dp(c, 10);
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
        l.setBackground(roundedFill(SURFACE, STROKE, 14, c));
        int p = dp(c, 12);
        l.setPadding(p, p, p, p);
        return l;
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

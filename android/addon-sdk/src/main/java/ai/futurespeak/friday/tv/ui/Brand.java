package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import ai.futurespeak.friday.tv.sdk.R;

/** Friday's brand tokens (from Agent Friday desktop's brand guide) for native views, at 10-foot sizes. */
public final class Brand {
    private Brand() {}

    public static final int CYAN = 0xFF00D4FF;
    public static final int VIOLET = 0xFF7B61FF;
    public static final int MAGENTA = 0xFFFF00FF;
    public static final int OK = 0xFF00FF80;
    public static final int WARN = 0xFFF59E0B;      // amber = "needs you", and only that
    public static final int DENY = 0xFFFF0080;
    public static final int ERROR = 0xFFEF4444;
    public static final int NEUTRAL = 0xFF7A8699;
    public static final int SURFACE = 0xFF0A0E1A;
    public static final int GLASS = 0xD90A0E1A;
    public static final int GLASS_EDGE = 0x14FFFFFF;
    public static final int TEXT = 0xDBFFFFFF;
    public static final int DIM = 0x8FFFFFFF;
    public static final int FAINT = 0x4DFFFFFF;

    private static Typeface display, body, mono;

    public static Typeface display(Context c) {
        if (display == null) display = Typeface.create(c.getResources().getFont(R.font.orbitron), 900, false);
        return display;
    }
    public static Typeface body(Context c) {
        if (body == null) body = c.getResources().getFont(R.font.inter);
        return body;
    }
    private static Typeface boldCache;
    public static Typeface bodyBold(Context c) {
        if (boldCache == null) boldCache = Typeface.create(body(c), 700, false);
        return boldCache;
    }
    public static Typeface mono(Context c) {
        if (mono == null) mono = c.getResources().getFont(R.font.jetbrains_mono);
        return mono;
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    public static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        // The brand faces are variable fonts: set the weight on the axis itself.
        if (tf == display) t.setFontVariationSettings("'wght' 900");
        else if (tf == boldCache) t.setFontVariationSettings("'wght' 700");
        t.setIncludeFontPadding(false);
        return t;
    }

    /** "AGENT FRIDAY™": Orbitron 900, cyan, soft glow, the ™ small and raised. */
    public static TextView wordmark(Context c, float sp) {
        SpannableString s = new SpannableString("AGENT FRIDAY™");
        int tm = s.length() - 1;
        s.setSpan(new SuperscriptSpan(), tm, tm + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(0.45f), tm, tm + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = text(c, "", sp, CYAN, display(c));
        t.setText(s);
        t.setLetterSpacing(0.06f);
        t.setShadowLayer(dp(c, 10), 0, 0, 0x9900D4FF);
        return t;
    }

    public static GradientDrawable glass(Context c, float radiusDp) { return Look.glass(c, radiusDp); }

    public static GradientDrawable pill(Context c, int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, 999));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1.5f)), stroke);
        return g;
    }

    /** Focusable tile/button background: glass at rest; focused, a deeper fill and an accent edge (Look). */
    public static StateListDrawable focusable(Context c, float radiusDp, int accent) {
        return Look.focusable(c, radiusDp, accent == CYAN ? Look.accent(c) : accent);
    }

    /** Focus cue: lift with an accent glow (Look.lift), following the Motion setting. */
    public static void growOnFocus(View v) { Look.lift(v, 1.05f); }
    public static TextView button(Context c, String label, int accent) {
        TextView b = text(c, label, 20, TEXT, bodyBold(c));
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setBackground(focusable(c, 14, accent));
        int p = dp(c, 14);
        b.setPadding(dp(c, 26), p, dp(c, 26), p);
        b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{Color.WHITE, TEXT}));
        growOnFocus(b);
        return b;
    }
}

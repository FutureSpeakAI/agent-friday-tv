package org.tvprivacy.adbreak;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Random;

/** FutureSpeak look, same tokens as the Agent Friday TV app (friday-tv/brand/theme.json). */
final class Fs {
    static final int CYAN = 0xFF00D4FF, VIOLET = 0xFF7B61FF, MAGENTA = 0xFFFF00FF;
    static final int SURFACE = 0xFF0A0E1A, GROUND = 0xFF05060C, GLASS = 0xFF0E1322, EDGE = 0xFF283046;
    static final int TEXT = 0xDBFFFFFF, DIM = 0x8FFFFFFF, AMBER = 0xFFF59E0B;   // amber = "needs you" (PIN) only

    private static Typeface display, body;

    static Typeface display(Context c) {
        if (display == null) display = Typeface.create(c.getResources().getFont(R.font.orbitron), 900, false);
        return display;
    }
    static Typeface body(Context c) {
        if (body == null) body = c.getResources().getFont(R.font.inter);
        return body;
    }
    static int dp(Context c, float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()); }

    /** Orbitron headline with a soft glow in its own colour. */
    static TextView title(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(display(c)); t.setFontVariationSettings("'wght' 900"); t.setLetterSpacing(0.06f);
        t.setGravity(Gravity.CENTER); t.setIncludeFontPadding(false);
        t.setShadowLayer(dp(c, 14), 0, 0, (color & 0x00FFFFFF) | 0xAA000000);
        return t;
    }
    static TextView body(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); t.setTextColor(color);
        t.setTypeface(body(c)); t.setGravity(Gravity.CENTER);
        return t;
    }
    static GradientDrawable glass(Context c, float radiusDp, int edge, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(GLASS); g.setCornerRadius(dp(c, radiusDp)); g.setStroke(dp(c, strokeDp), edge);
        return g;
    }
    static GradientDrawable backdrop() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{GROUND, SURFACE});
    }
    static View gradientLine(Context c) {
        View v = new View(c);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{CYAN, VIOLET, MAGENTA}));
        return v;
    }
    /** Remote-friendly button: glass, cyan ring when focused. */
    static Button button(Context c, String s, float sp, boolean displayFont) {
        Button b = new Button(c);
        b.setText(s); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); b.setTextColor(TEXT);
        b.setTypeface(displayFont ? display(c) : body(c));
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, glass(c, 14, CYAN, 3));
        st.addState(new int[]{}, glass(c, 14, EDGE, 1));
        b.setBackground(st);
        b.setPadding(dp(c, 24), dp(c, 12), dp(c, 24), dp(c, 12));
        return b;
    }

    static int grad(float t) {
        int a, b; float u;
        if (t < 0.4f) { a = CYAN; b = VIOLET; u = t / 0.4f; } else { a = VIOLET; b = MAGENTA; u = (t - 0.4f) / 0.6f; }
        int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * u);
        int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * u);
        int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * u);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Faint constellation of nodes and links in the brand gradient. animate=true drifts slowly (screensaver). */
    static final class NodeField extends View {
        private final float[] x, y, vx, vy;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean animate;
        private final float top, bottom, alpha;

        NodeField(Context c, int n, float bandTop, float bandBottom, float alpha, boolean animate, long seed) {
            super(c);
            this.animate = animate; this.top = bandTop; this.bottom = bandBottom; this.alpha = alpha;
            x = new float[n]; y = new float[n]; vx = new float[n]; vy = new float[n];
            Random r = new Random(seed);
            for (int i = 0; i < n; i++) { x[i] = r.nextFloat(); y[i] = bandTop + r.nextFloat() * (bandBottom - bandTop); vx[i] = (r.nextFloat() - 0.5f) * 0.0006f; vy[i] = (r.nextFloat() - 0.5f) * 0.0004f; }
        }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            if (w == 0) return;
            float link = 0.075f * w;
            for (int i = 0; i < x.length; i++) {
                for (int j = i + 1; j < x.length; j++) {
                    float dx = (x[i] - x[j]) * w, dy = (y[i] - y[j]) * h, d = (float) Math.hypot(dx, dy);
                    if (d < link) {
                        p.setColor(grad(Math.max(0, Math.min(1, (x[i] + x[j]) / 2))));
                        p.setAlpha((int) (255 * alpha * 0.45f * (1 - d / link)));
                        p.setStrokeWidth(1.5f);
                        cv.drawLine(x[i] * w, y[i] * h, x[j] * w, y[j] * h, p);
                    }
                }
                p.setColor(grad(Math.max(0, Math.min(1, x[i]))));
                p.setAlpha((int) (255 * alpha));
                cv.drawCircle(x[i] * w, y[i] * h, 3f, p);
            }
            if (animate) {
                for (int i = 0; i < x.length; i++) {
                    x[i] += vx[i]; y[i] += vy[i];
                    if (x[i] < 0 || x[i] > 1) vx[i] = -vx[i];
                    if (y[i] < top || y[i] > bottom) vy[i] = -vy[i];
                }
                postInvalidateDelayed(66);   // ~15 fps: gentle and cheap
            }
        }
    }
}

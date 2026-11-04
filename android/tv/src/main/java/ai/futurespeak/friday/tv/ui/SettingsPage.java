package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Builds one settings page out of FutureSpeak controls. Every control carries a stable tag
 * so the page can be rebuilt after a change without losing the remote's place.
 *
 *   header      eyebrow, title and a sentence of explanation
 *   card        a glass card; the controls that follow go inside it
 *   choice      a label with option pills (one is selected)
 *   toggle      a label with an on/off switch
 *   stepper     a label with −, a level bar and +
 *   action      a label with a button (accent: cyan, amber for "needs PIN", pink for destructive)
 *   tiles       a grid of selectable tiles (voices, looks)
 *   fact        an icon, a title and a sentence (privacy facts)
 */
public final class SettingsPage {
    public interface Builder { void build(SettingsPage p); }

    private final Context c;
    private final LinearLayout col;
    private final SettingsScreen screen;
    /** The accent from Customize Home (cyan unless changed). */
    private final int A;
    private LinearLayout card;
    private boolean nextLocked = false;
    private int rowsInCard = 0;

    SettingsPage(Context c, LinearLayout col, SettingsScreen screen) {
        this.c = c; this.col = col; this.screen = screen;
        this.A = Look.accent(c);
    }

    private int dp(float v) { return Brand.dp(c, v); }

    /** Rebuild the page (call after changing a value). */
    public void refresh() { screen.post(screen::rebuild); }

    // ---- layout ---------------------------------------------------------------------
    public SettingsPage header(String eyebrow, String title, String sub) {
        TextView e = Look.eyebrow(c, eyebrow, 10.5f);
        e.setLetterSpacing(0.3f);
        col.addView(e);
        // Simple mode's type scale: Orbitron for the page title, Inter for everything you read.
        TextView t = Brand.text(c, title, 21, 0xFFFFFFFF, Brand.display(c));
        t.setLetterSpacing(0.02f);
        t.setPadding(0, dp(10), 0, 0);
        t.setShadowLayer(dp(14), 0, 0, Look.alpha(A, 0x40));
        col.addView(t);
        if (sub != null && !sub.isEmpty()) {
            TextView s = Brand.text(c, sub, 13, Brand.DIM, Brand.body(c));
            s.setLineSpacing(dp(3), 1f);
            s.setPadding(0, dp(8), dp(40), 0);
            col.addView(s);
        }
        card = null;
        return this;
    }

    /** Starts a glass card, with an optional small label above it. */
    public SettingsPage card(String label) {
        if (label != null) {
            TextView l = Brand.text(c, label.toUpperCase(java.util.Locale.ROOT), 9.5f, Look.alpha(A, 0xB3), Brand.display(c));
            l.setLetterSpacing(0.24f);
            l.setPadding(dp(4), dp(22), 0, dp(8));
            col.addView(l);
        }
        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Look.glass(c, 16));
        card.setPadding(dp(6), dp(4), dp(6), dp(4));
        card.setClipChildren(false);
        card.setClipToPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        if (label == null) lp.topMargin = dp(18);
        col.addView(card, lp);
        rowsInCard = 0;
        return this;
    }

    /** The next control needs the grown-up PIN (shows a small PIN tag while locked). */
    public SettingsPage locked(boolean needsPin) { nextLocked = needsPin; return this; }

    private LinearLayout parent() { if (card == null) card(null); return card; }

    private LinearLayout row(String label, String hint) {
        LinearLayout p = parent();
        if (rowsInCard++ > 0) {
            View d = new View(c);
            d.setBackgroundColor(0x0FFFFFFF);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            dl.leftMargin = dl.rightMargin = dp(16);
            p.addView(d, dl);
        }
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(18), dp(13), dp(18), dp(13));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Brand.text(c, label, 15, Brand.TEXT, Brand.bodyBold(c)));
        if (nextLocked) {
            TextView pin = Brand.text(c, "🔒 PIN", 9, Brand.WARN, Brand.bodyBold(c));
            pin.setBackground(Brand.pill(c, 0x1FF59E0B, 0x66F59E0B));
            pin.setPadding(dp(7), dp(2), dp(7), dp(2));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.leftMargin = dp(10);
            top.addView(pin, pl);
        }
        nextLocked = false;
        texts.addView(top);
        if (hint != null && !hint.isEmpty()) {
            TextView h = Brand.text(c, hint, 11.5f, Brand.DIM, Brand.body(c));
            h.setLineSpacing(dp(2), 1f);
            h.setPadding(0, dp(4), dp(16), 0);
            texts.addView(h);
        }
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        p.addView(r, new LinearLayout.LayoutParams(-1, -2));
        return r;
    }

    // ---- controls ---------------------------------------------------------------------
    public SettingsPage choice(String id, String label, String hint, String[] options, int selected, IntConsumer pick) {
        LinearLayout r = row(label, hint);
        LinearLayout pills = new LinearLayout(c);
        pills.setClipChildren(false);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < options.length; i++) {
            final int k = i;
            TextView b = pill(options[i], i == selected);
            b.setTag(id + "#" + i);
            b.setOnClickListener(v -> { pick.accept(k); refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            pills.addView(b, lp);
        }
        r.addView(pills);
        return this;
    }

    public SettingsPage toggle(String id, String label, String hint, boolean on, Runnable flip) {
        LinearLayout r = row(label, hint);
        SwitchView s = new SwitchView(c, on);
        s.setTag(id);
        s.setOnClickListener(v -> { flip.run(); refresh(); });
        r.addView(s, new LinearLayout.LayoutParams(dp(58), dp(32)));
        return this;
    }

    public SettingsPage stepper(String id, String label, String hint, String value, float fraction, Runnable minus, Runnable plus) {
        LinearLayout r = row(label, hint);
        LinearLayout box = new LinearLayout(c);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView m = round("−");
        m.setTag(id + "#-");
        m.setOnClickListener(v -> { minus.run(); refresh(); });
        box.addView(m, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LevelBar bar = new LevelBar(c, fraction);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(120), dp(8));
        bl.leftMargin = bl.rightMargin = dp(10);
        box.addView(bar, bl);
        TextView p = round("+");
        p.setTag(id + "#+");
        p.setOnClickListener(v -> { plus.run(); refresh(); });
        box.addView(p, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView val = Brand.text(c, value, 13, A, Brand.bodyBold(c));
        val.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        box.addView(val, new LinearLayout.LayoutParams(dp(64), -2));
        r.addView(box);
        return this;
    }

    /** accent: A normal, Brand.WARN needs the PIN, Brand.DENY destructive. */
    public SettingsPage action(String id, String label, String hint, String button, int accent, Runnable run) {
        LinearLayout r = row(label, hint);
        TextView b = Brand.button(c, button, accent);
        b.setTextSize(13.5f);
        b.setPadding(dp(18), dp(9), dp(18), dp(9));
        if (accent == Brand.DENY) b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFFFFFFFF, 0xFFFF6FB0}));
        b.setTag(id);
        b.setOnClickListener(v -> { run.run(); refresh(); });
        r.addView(b);
        return this;
    }

    /** A label with several buttons (e.g. a channel: Open, Rename, Pin, Delete). */
    public SettingsPage buttons(String id, String label, String hint, String[] labels, int[] accents, Runnable[] runs) {
        LinearLayout r = row(label, hint);
        LinearLayout box = new LinearLayout(c);
        box.setClipChildren(false);
        for (int i = 0; i < labels.length; i++) {
            final int k = i;
            TextView b = Brand.button(c, labels[i], accents[i]);
            b.setTextSize(12.5f);
            b.setPadding(dp(13), dp(8), dp(13), dp(8));
            if (accents[i] == Brand.DENY) b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFFFFFFFF, 0xFFFF6FB0}));
            b.setTag(id + "#" + i);
            b.setOnClickListener(v -> { runs[k].run(); refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = dp(8);
            box.addView(b, lp);
        }
        r.addView(box);
        return this;
    }

    /** A line of read-only status: label on the left, value on the right. */
    public SettingsPage value(String label, String hint, String value, int color) {
        LinearLayout r = row(label, hint);
        TextView v = Brand.text(c, value, 13, color == Brand.CYAN ? A : color, Brand.bodyBold(c));
        v.setGravity(Gravity.END);
        v.setMaxWidth(dp(260));
        r.addView(v);
        return this;
    }

    /** A grid of selectable tiles (title + subtitle), {@code perRow} across. */
    public SettingsPage tiles(String id, String[][] items, int selected, int perRow, IntConsumer pick) {
        LinearLayout p = parent();
        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(12), dp(12), dp(12), dp(8));
        LinearLayout line = null;
        for (int i = 0; i < items.length; i++) {
            if (i % perRow == 0) {
                line = new LinearLayout(c);
                grid.addView(line, new LinearLayout.LayoutParams(-1, -2));
            }
            final int k = i;
            LinearLayout t = new LinearLayout(c);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPadding(dp(14), dp(11), dp(12), dp(11));
            t.setFocusable(true);
            t.setFocusableInTouchMode(true);
            t.setClickable(true);
            t.setTag(id + "#" + i);
            boolean sel = i == selected;
            t.setBackground(tileBg(sel));
            Brand.growOnFocus(t);
            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = Brand.text(c, items[i][0], 14, sel ? 0xFFFFFFFF : Brand.TEXT, Brand.bodyBold(c));
            top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            if (sel) top.addView(Brand.text(c, "●", 10, A, Brand.body(c)));
            t.addView(top);
            TextView sub = Brand.text(c, items[i].length > 1 ? items[i][1] : "", 10.5f, Brand.DIM, Brand.body(c));
            sub.setPadding(0, dp(3), 0, 0);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            t.addView(sub);
            t.setOnClickListener(v -> { pick.accept(k); refresh(); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            line.addView(t, lp);
        }
        if (line != null) for (int pad = items.length % perRow; pad > 0 && pad < perRow; pad++)
            line.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        p.addView(grid);
        rowsInCard++;
        return this;
    }

    /** A plain fact: icon, title, sentence. Not focusable. */
    public SettingsPage fact(String icon, String title, String body) {
        LinearLayout p = parent();
        if (rowsInCard++ > 0) {
            View d = new View(c);
            d.setBackgroundColor(0x0FFFFFFF);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
            dl.leftMargin = dl.rightMargin = dp(16);
            p.addView(d, dl);
        }
        LinearLayout r = new LinearLayout(c);
        r.setPadding(dp(16), dp(13), dp(16), dp(13));
        View i;
        if (icon.matches("^[a-z]+$")) { LineIcon li = new LineIcon(c, icon); li.setColor(Look.alpha(A, 0xE6)); i = li; }
        else { TextView ti = Brand.text(c, icon, 18, A, Brand.body(c)); ti.setGravity(Gravity.CENTER_HORIZONTAL); i = ti; }
        r.addView(i, new LinearLayout.LayoutParams(dp(26), dp(26)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, 0, 0);
        texts.addView(Brand.text(c, title, 14, Brand.TEXT, Brand.bodyBold(c)));
        TextView b = Brand.text(c, body, 11.5f, Brand.DIM, Brand.body(c));
        b.setLineSpacing(dp(2), 1f);
        b.setPadding(0, dp(4), 0, 0);
        texts.addView(b);
        r.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        p.addView(r);
        return this;
    }

    /** A sentence under a card. */
    public SettingsPage note(String text) {
        TextView n = Brand.text(c, text, 11, Brand.FAINT, Brand.body(c));
        n.setLineSpacing(dp(2), 1f);
        n.setPadding(dp(6), dp(10), dp(30), 0);
        col.addView(n);
        card = null;
        return this;
    }

    void finish() {
        View spacer = new View(c);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, dp(24)));
    }

    // ---- pieces -----------------------------------------------------------------------
    private TextView pill(String text, boolean selected) {
        TextView b = Brand.text(c, text, 12.5f, selected ? 0xFF02121A : Brand.TEXT, Brand.bodyBold(c));
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(999));
        rest.setColor(selected ? A : 0x14FFFFFF);
        rest.setStroke(Math.max(1, dp(1)), selected ? A : 0x26FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(999));
        on.setColor(selected ? A : Look.alpha(A, 0x33));
        on.setStroke(dp(2.5f), selected ? 0xFFFFFFFF : A);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, rest);
        b.setBackground(s);
        if (!selected) b.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{0xFFFFFFFF, Brand.TEXT}));
        Brand.growOnFocus(b);
        return b;
    }

    private TextView round(String glyph) {
        TextView b = Brand.text(c, glyph, 18, Brand.TEXT, Brand.bodyBold(c));
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        GradientDrawable rest = new GradientDrawable();
        rest.setShape(GradientDrawable.OVAL);
        rest.setColor(0x14FFFFFF);
        rest.setStroke(Math.max(1, dp(1)), 0x26FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(Look.alpha(A, 0x33));
        on.setStroke(dp(2.5f), A);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, rest);
        b.setBackground(s);
        Brand.growOnFocus(b);
        return b;
    }

    private StateListDrawable tileBg(boolean selected) {
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(dp(12));
        rest.setColor(selected ? Look.alpha(A, 0x26) : 0x0DFFFFFF);
        rest.setStroke(Math.max(1, dp(1.2f)), selected ? Look.alpha(A, 0xB3) : 0x1FFFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setCornerRadius(dp(12));
        on.setColor(Look.alpha(A, 0x33));
        on.setStroke(dp(2.5f), A);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, rest);
        return s;
    }

    /** An on/off switch: cyan track with the knob right when on; white ring when focused. */
    static final class SwitchView extends View {
        private final boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();

        SwitchView(Context c, boolean on) {
            super(c);
            this.on = on;
            setFocusable(true);
            setFocusableInTouchMode(true);
            setClickable(true);
            setDefaultFocusHighlightEnabled(false);
            Brand.growOnFocus(this);
            setContentDescription(on ? "On" : "Off");
        }

        @Override protected void onFocusChanged(boolean f, int d, android.graphics.Rect prev) { super.onFocusChanged(f, d, prev); invalidate(); }

        @Override public boolean onKeyDown(int k, KeyEvent e) {
            if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) { performClick(); return true; }
            return super.onKeyDown(k, e);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f, inset = Brand.dp(getContext(), 3);
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Look.accent(getContext()) : 0x33FFFFFF);
            cv.drawRoundRect(r, rad, rad, p);
            if (isFocused()) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Brand.dp(getContext(), 2.5f));
                p.setColor(0xFFFFFFFF);
                r.inset(p.getStrokeWidth() / 2, p.getStrokeWidth() / 2);
                cv.drawRoundRect(r, rad, rad, p);
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            float kr = rad - inset - Brand.dp(getContext(), 1);
            cv.drawCircle(on ? w - rad : rad, h / 2f, kr, p);
        }
    }

    /** A thin level bar for steppers. */
    static final class LevelBar extends View {
        private final float f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LevelBar(Context c, float fraction) { super(c); f = Math.max(0, Math.min(1, fraction)); }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), rad = h / 2f;
            p.setColor(0x26FFFFFF);
            cv.drawRoundRect(0, 0, w, h, rad, rad, p);
            p.setShader(new android.graphics.LinearGradient(0, 0, w, 0, Look.accent(getContext()), 0xFF7B61FF, android.graphics.Shader.TileMode.CLAMP));
            cv.drawRoundRect(0, 0, Math.max(h, w * f), h, rad, rad, p);
            p.setShader(null);
        }
    }
}

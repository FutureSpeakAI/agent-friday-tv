package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

/**
 * Home v2's look for the rest of the app (desktop Simple mode): one accent (Customize Home),
 * glass with a lit top edge, Orbitron eyebrows over Inter, a lift and an accent glow on focus,
 * staggered reveals and soft page transitions. Motion follows Home's setting (full, calm, off),
 * and "off" also when reduce-motion is on. Everything here is cheap on the 4K TV GPU: no blur,
 * no per-frame work, shadows only on the one focused view.
 */
public final class Look {
    private Look() {}

    public enum Level { FULL, CALM, OFF }

    /** The desktop's --fr-reveal: cubic-bezier(0.2, 0.8, 0.3, 1). */
    public static final PathInterpolator REVEAL = new PathInterpolator(0.2f, 0.8f, 0.3f, 1f);
    static final int[] ACCENTS = {0xFF00D4FF, 0xFF7B61FF, 0xFFFF00FF, 0xFF00FF80, 0xFFF59E0B};

    private static SharedPreferences home(Context c) { return c.getApplicationContext().getSharedPreferences("home", Context.MODE_PRIVATE); }

    /** The accent chosen in Customize Home (cyan by default). */
    public static int accent(Context c) {
        try { return ACCENTS[Math.floorMod(home(c).getInt("accent", 0), ACCENTS.length)]; }
        catch (Throwable e) { return Brand.CYAN; }
    }

    /** Home's Motion setting; OFF whenever reduce-motion is on (in Friday or in Android). */
    public static Level level(Context c) {
        try { if (Motion.reduced(c)) return Level.OFF; } catch (Throwable ignored) { }
        String m = null;
        try { m = home(c).getString("motion", null); } catch (Throwable ignored) { }
        return level(m, false);
    }

    /** Pure, for tests: Home's stored motion name and reduce-motion to a level. */
    public static Level level(String homeMotion, boolean reduced) {
        if (reduced) return Level.OFF;
        if ("OFF".equals(homeMotion)) return Level.OFF;
        if ("CALM".equals(homeMotion)) return Level.CALM;
        return Level.FULL;
    }

    public static int alpha(int color, int a) { return (color & 0x00FFFFFF) | (a << 24); }

    // ---- surfaces -----------------------------------------------------------------------------

    /** Glass: a deep translucent fill, a faint edge, and a lit top (two gradient stops, no blur). */
    public static GradientDrawable glass(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xE0141C30, 0xD90A0E1A, 0xE6080B15});
        g.setCornerRadius(Brand.dp(c, radiusDp));
        g.setStroke(Math.max(1, Brand.dp(c, 1)), 0x1AFFFFFF);
        return g;
    }

    /** A focusable surface: glass at rest; focused, a deeper fill with an accent edge. The glow is the view's shadow (see {@link #lift}). */
    public static StateListDrawable focusable(Context c, float radiusDp, int edge) { return focusable(c, radiusDp, edge, false); }

    /** As {@link #focusable(Context, float, int)}; {@code clearRest}: nothing drawn at rest (items inside a glass column). */
    public static StateListDrawable focusable(Context c, float radiusDp, int edge, boolean clearRest) {
        GradientDrawable on = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xF2182640, 0xF0101A2E});
        on.setCornerRadius(Brand.dp(c, radiusDp));
        on.setStroke(Brand.dp(c, 2), edge);
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(level(c) == Level.OFF ? 0 : 140);
        s.setExitFadeDuration(level(c) == Level.OFF ? 0 : 140);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_pressed}, on);
        s.addState(new int[]{android.R.attr.state_selected}, selected(c, radiusDp));
        s.addState(new int[]{}, clearRest ? new android.graphics.drawable.ColorDrawable(0) : glass(c, radiusDp));
        return s;
    }

    /** The current page in a column: a faint accent wash, no edge. */
    static GradientDrawable selected(Context c, float radiusDp) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{alpha(accent(c), 0x24), alpha(accent(c), 0x08)});
        g.setCornerRadius(Brand.dp(c, radiusDp));
        return g;
    }

    /** A soft accent wash behind a whole screen's top edge (the "stage light" of Simple mode). */
    public static Drawable stageLight(Context c) {
        int a = accent(c);
        GradientDrawable top = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{alpha(a, 0x1F), alpha(a, 0x00)});
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0A1424, 0xFF050812, 0xFF03040A});
        LayerDrawable l = new LayerDrawable(new Drawable[]{base, top});
        l.setLayerHeight(1, Brand.dp(c, 260));
        return l;
    }

    /**
     * Desktop's avatar caption (.friday-avatar-caption): a glass block with an accent hairline on
     * top, the state in small Orbitron caps over one line of Inter.
     */
    public static Drawable captionBox(Context c) {
        GradientDrawable g = glass(c, 14);
        GradientDrawable line = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{alpha(accent(c), 0x00), alpha(accent(c), 0x99), alpha(accent(c), 0x00)});
        LayerDrawable l = new LayerDrawable(new Drawable[]{g, line});
        l.setLayerHeight(1, Math.max(1, Brand.dp(c, 1.5f)));
        l.setLayerGravity(1, android.view.Gravity.TOP | android.view.Gravity.FILL_HORIZONTAL);
        l.setLayerInsetLeft(1, Brand.dp(c, 18));
        l.setLayerInsetRight(1, Brand.dp(c, 18));
        return l;
    }

    /** The caption's words: STATE (Orbitron, small caps, coloured) on the first line, the hint on the second. */
    public static CharSequence caption(Context c, String state, String line, int stateColor) {
        android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(state.toUpperCase(java.util.Locale.ROOT));
        int e = b.length();
        b.setSpan(new android.text.style.TypefaceSpan(Brand.display(c)), 0, e, 0);
        b.setSpan(new android.text.style.ForegroundColorSpan(stateColor), 0, e, 0);
        b.setSpan(new android.text.style.RelativeSizeSpan(0.74f), 0, e, 0);
        if (line != null && !line.isEmpty()) b.append("\n").append(line);
        return b;
    }

    /** Orbitron eyebrow, letter-spaced, in the accent. */
    public static TextView eyebrow(Context c, String s, float sp) {
        TextView t = Brand.text(c, s.toUpperCase(java.util.Locale.ROOT), sp, accent(c), Brand.display(c));
        t.setLetterSpacing(0.24f);
        return t;
    }

    // ---- motion -------------------------------------------------------------------------------

    /**
     * Focus: scale up a little and rise, with an accent-coloured shadow as the glow. Calm keeps the
     * lift without the scale; off changes instantly. Only the focused view ever casts a shadow.
     */
    public static void lift(View v, float scale) {
        Context c = v.getContext();
        int a = accent(c);
        v.setDefaultFocusHighlightEnabled(false);
        v.setOutlineSpotShadowColor(a);
        v.setOutlineAmbientShadowColor(a);
        View.OnFocusChangeListener prev = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((view, has) -> {
            Level l = level(c);
            float s = has && l == Level.FULL ? scale : 1f;
            float z = has ? Brand.dp(c, l == Level.OFF ? 6 : 12) : 0;
            if (l == Level.OFF) { view.setScaleX(s); view.setScaleY(s); view.setTranslationZ(z); }
            else view.animate().scaleX(s).scaleY(s).translationZ(z).setDuration(160).setInterpolator(REVEAL).start();
            if (prev != null) prev.onFocusChange(view, has);
        });
    }

    /** Staggered entry: full rises 18 dp and fades in over 0.35 s, 50 ms apart; calm only fades; off shows at once. */
    public static void reveal(View v, int index) {
        Level l = level(v.getContext());
        if (l == Level.OFF) { v.setAlpha(1f); v.setTranslationY(0); return; }
        v.setAlpha(0f);
        v.setTranslationY(l == Level.FULL ? Brand.dp(v.getContext(), 18) : 0);
        v.animate().alpha(1f).translationY(0).setStartDelay(Math.min(index, 10) * 50L + 40)
                .setDuration(l == Level.FULL ? 350 : 220).setInterpolator(REVEAL).start();
    }

    /** A page or layer arriving: full fades in from slightly smaller and lower; calm fades; off is instant. */
    public static void enter(View v) {
        Level l = level(v.getContext());
        if (l == Level.OFF) return;
        v.setAlpha(0f);
        if (l == Level.FULL) { v.setScaleX(0.97f); v.setScaleY(0.97f); v.setTranslationY(Brand.dp(v.getContext(), 14)); }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0).setDuration(l == Level.FULL ? 300 : 180).setInterpolator(REVEAL).start();
    }

    /** A layer leaving: fades out (full also shrinks a touch), then {@code done}. Off runs {@code done} at once. */
    public static void exit(View v, Runnable done) {
        Level l = level(v.getContext());
        if (l == Level.OFF) { done.run(); return; }
        v.animate().alpha(0f).scaleX(l == Level.FULL ? 0.98f : 1f).scaleY(l == Level.FULL ? 0.98f : 1f)
                .setDuration(l == Level.FULL ? 180 : 140).setInterpolator(REVEAL).withEndAction(done).start();
    }

    /** Reveals each child of a group in turn (page contents, card lists). */
    public static void revealChildren(android.view.ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) reveal(g.getChildAt(i), i);
    }
}

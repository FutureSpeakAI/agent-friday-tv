package ai.futurespeak.friday.tv.live;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The fixed "Google is listening" banner: top edge, above everything (it sits at a
 * higher elevation than any layer, so no card, channel or page can cover it), with the green mic
 * pill, the session timer and "Back to stop". A caption line shows what Google heard; it is never
 * stored.
 */
public final class LiveBanner extends LinearLayout {
    public static final String WORDS = "Google is listening";
    private final TextView timer, caption;

    public LiveBanner(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(false);
        setClickable(false);
        setElevation(dp(40));
        setTranslationZ(dp(40));

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0052A18);
        bg.setCornerRadius(dp(26));
        bg.setStroke(dp(2), 0xFF34D399);
        bar.setBackground(bg);
        bar.setPadding(dp(22), dp(10), dp(22), dp(10));

        TextView mic = text(c, "●  🎙", 22, 0xFF34D399, true);
        TextView words = text(c, "  " + WORDS, 24, 0xFFFFFFFF, true);
        timer = text(c, "   0:00", 22, 0xE6FFFFFF, false);
        timer.setFontFeatureSettings("tnum");
        TextView hint = text(c, "     Back to stop", 20, 0xB3FFFFFF, false);
        bar.addView(mic); bar.addView(words); bar.addView(timer); bar.addView(hint);
        addView(bar, new LayoutParams(-2, -2));

        caption = text(c, "", 20, 0xE6FFFFFF, false);
        caption.setMaxLines(2);
        caption.setEllipsize(TextUtils.TruncateAt.START);
        caption.setGravity(Gravity.CENTER);
        caption.setShadowLayer(dp(4), 0, 0, 0xFF000000);
        LayoutParams cl = new LayoutParams(dp(1100), -2);
        cl.topMargin = dp(10);
        addView(caption, cl);
    }

    public static FrameLayout.LayoutParams params(Context c) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.topMargin = (int) (18 * c.getResources().getDisplayMetrics().density);
        return p;
    }

    public void setElapsed(long ms, int limitMinutes) {
        timer.setText("   " + clock(ms) + (limitMinutes > 0 ? " / " + limitMinutes + ":00" : ""));
    }

    public void setCaption(String s) { caption.setText(s == null ? "" : s); }

    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setIncludeFontPadding(false);
        return t;
    }
}

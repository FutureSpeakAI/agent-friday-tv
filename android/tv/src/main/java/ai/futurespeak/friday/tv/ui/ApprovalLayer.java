package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The approval screen. Fixed app UI: its words and buttons come from the app, never from a
 * brain or a generated page (only the channel's own name and the host names are inserted, as
 * plain text). Pages are hidden while it is up, so nothing generated can draw over it or press it.
 */
public final class ApprovalLayer extends Layer {
    public static final class Choice {
        final String label; final int accent; final Runnable action; final boolean closes;
        public Choice(String label, int accent, boolean closes, Runnable action) {
            this.label = label; this.accent = accent; this.closes = closes; this.action = action;
        }
    }

    private final List<View> buttons = new ArrayList<>();
    private final Runnable onBack;

    public ApprovalLayer(Context c, Host host, String title, String body, List<String> details,
                         List<Choice> choices, Runnable onBack) {
        super(c, host);
        this.onBack = onBack;
        setBackgroundColor(0xEB040610);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        // Glass with an amber hairline on top: amber means "needs you", and only that.
        android.graphics.drawable.GradientDrawable amber = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00F59E0B, 0xCCF59E0B, 0x00F59E0B});
        android.graphics.drawable.LayerDrawable bg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{Look.glass(c, 22), amber});
        bg.setLayerHeight(1, Math.max(1, Brand.dp(c, 2)));
        bg.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
        bg.setLayerInsetLeft(1, Brand.dp(c, 28)); bg.setLayerInsetRight(1, Brand.dp(c, 28));
        card.setBackground(bg);
        int p = Brand.dp(c, 32);
        card.setPadding(p, Brand.dp(c, 24), p, Brand.dp(c, 22));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView needs = Brand.text(c, "NEEDS YOU", 13, 0xFF1A1205, Brand.display(c));
        needs.setLetterSpacing(0.15f);
        needs.setBackground(Brand.pill(c, Brand.WARN, 0));
        needs.setPadding(Brand.dp(c, 14), Brand.dp(c, 6), Brand.dp(c, 14), Brand.dp(c, 6));
        top.addView(needs);
        TextView from = Brand.text(c, "   AGENT FRIDAY™ APP · APPROVAL", 13, Brand.DIM, Brand.display(c));
        from.setLetterSpacing(0.12f);
        top.addView(from);
        card.addView(top);

        TextView t = Brand.text(c, title, 21, Color.WHITE, Brand.display(c));
        t.setLetterSpacing(0.02f);
        t.setLineSpacing(Brand.dp(c, 4), 1f);
        t.setPadding(0, Brand.dp(c, 20), 0, Brand.dp(c, 10));
        card.addView(t);
        if (body != null && !body.isEmpty()) {
            TextView b = Brand.text(c, body, 18, Brand.TEXT, Brand.body(c));
            b.setLineSpacing(0, 1.25f);
            card.addView(b);
        }
        if (details != null) for (String d : details) {
            android.text.SpannableString ds = new android.text.SpannableString("●   " + d);
            ds.setSpan(new android.text.style.ForegroundColorSpan(Look.accent(c)), 0, 1, 0);
            ds.setSpan(new android.text.style.RelativeSizeSpan(0.6f), 0, 1, 0);
            TextView dv = Brand.text(c, "", 17, 0xE6FFFFFF, Brand.body(c));
            dv.setText(ds);
            dv.setPadding(Brand.dp(c, 8), Brand.dp(c, 8), 0, 0);
            card.addView(dv);
        }

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, Brand.dp(c, 22), 0, Brand.dp(c, 4));
        for (Choice ch : choices) {
            TextView btn = Brand.button(c, ch.label, ch.accent);
            btn.setTextSize(16);
            btn.setSingleLine(true);
            btn.setPadding(Brand.dp(c, 18), Brand.dp(c, 11), Brand.dp(c, 18), Brand.dp(c, 11));
            btn.setOnClickListener(v -> {
                if (ch.closes) host.pop(this);
                if (ch.action != null) ch.action.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = Brand.dp(c, 12);
            row.addView(btn, lp);
            buttons.add(btn);
        }
        card.addView(row);

        TextView foot = Brand.text(c, "This screen is part of the Agent Friday™ app. Pages and channels made by Claude cannot show it or press it.",
                13, Brand.FAINT, Brand.body(c));
        foot.setPadding(0, Brand.dp(c, 12), 0, 0);
        card.addView(foot);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Brand.dp(c, 860), -2, Gravity.CENTER);
        addView(card, lp);
        for (int i = 0; i < card.getChildCount(); i++) Look.reveal(card.getChildAt(i), i);
    }

    @Override public void focusFirst() {
        // The safest choice (the last one: "Don't save" / "Cancel") takes focus first.
        if (!buttons.isEmpty()) buttons.get(buttons.size() - 1).requestFocus();
    }

    @Override public void back() {
        host.pop(this);
        if (onBack != null) onBack.run();
    }
}

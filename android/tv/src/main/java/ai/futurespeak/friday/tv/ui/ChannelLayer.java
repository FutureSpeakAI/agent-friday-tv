package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ai.futurespeak.friday.tv.channels.ChannelSpec;
import ai.futurespeak.friday.tv.web.SandboxView;

/** A channel, running in its sandbox. Every key goes to the channel; Back goes to it first, then leaves. */
public final class ChannelLayer extends Layer {
    private final SandboxView view;

    public ChannelLayer(Context c, Host host, ChannelSpec ch, boolean preview) {
        this(c, host, ch, preview ? null : ch.grantedHosts, preview ? "preview" + System.nanoTime() % 100000 : ch.id,
                preview ? "  (preview)" : "");
    }

    /**
     * A channel being checked before it is saved: it runs with the hosts the grown-up just
     * approved, under a throwaway origin, so nothing is kept unless it works.
     */
    public static ChannelLayer trial(Context c, Host host, ChannelSpec ch, List<String> granted) {
        return new ChannelLayer(c, host, ch, granted, "try" + System.nanoTime() % 100000, "");
    }

    private ChannelLayer(Context c, Host host, ChannelSpec ch, List<String> granted, String origin, String suffix) {
        super(c, host);
        setBackgroundColor(0xFF05060C);
        view = new SandboxView(c, origin, ch.html, granted);
        view.allowVoice(true);   // read-aloud through Friday's voice while the channel is open
        // The page gets the whole 1920x1080 it is written for; the frame's bar floats over it and fades.
        addView(view, new LayoutParams(-1, -1));

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Brand.dp(c, 24), Brand.dp(c, 10), Brand.dp(c, 24), Brand.dp(c, 6));
        bar.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF205060C, 0xB305060C, 0x0005060C}));
        bar.addView(Brand.wordmark(c, 13));
        TextView name = Brand.text(c, "   " + ch.emoji + "  " + ch.title + suffix, 16, Brand.TEXT, Brand.bodyBold(c));
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        String net = granted == null || granted.isEmpty()
                ? (ch.requestedHosts.isEmpty() ? "NO INTERNET" : "NO INTERNET IN PREVIEW · LIVE DATA AFTER YOU APPROVE")
                : granted.size() <= 2 ? "CAN REACH: " + String.join(", ", granted)
                : "CAN REACH " + granted.size() + " APPROVED SITES";
        TextView n = Brand.text(c, net + "   ·   BACK TO LEAVE", 11, Brand.DIM, Brand.display(c));
        n.setLetterSpacing(0.1f);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(n);
        addView(bar, new LayoutParams(-1, Brand.dp(c, 56), Gravity.TOP));
        this.bar = bar;
        view.start();
        showBar(4000);
    }

    private final LinearLayout bar;

    /** Shows the frame's bar (name, what it can reach, how to leave), then fades it after {@code ms}. */
    void showBar(long ms) {
        bar.animate().cancel();
        bar.setAlpha(1f);
        bar.animate().alpha(0f).setStartDelay(ms).setDuration(600).start();
    }

    /** See {@link SandboxView#verify}. */
    public void verify(SandboxView.RenderCheck cb) { view.verify(cb); }

    @Override public boolean blocksTalk() { return false; }
    @Override public void focusFirst() { view.requestFocus(); }
    @Override public boolean key(KeyEvent e) { return false; }
    /** Back goes to the channel first (its own screens), then leaves (see SandboxView.offerBack). */
    @Override public void back() { showBar(2500); view.offerBack(() -> host.pop(this)); }
    @Override public void closed() {
        ai.futurespeak.friday.tv.web.ChannelVoice.Speaker s = ai.futurespeak.friday.tv.web.ChannelVoice.speaker;
        if (s != null) s.stop();
        view.shutdown();
    }
}

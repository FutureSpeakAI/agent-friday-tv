package org.tvprivacy.adbreak;

import android.service.dreams.DreamService;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/**
 * FutureSpeak screensaver: a slowly drifting constellation, a clock and a small wordmark.
 * ~15 fps, no network, no data. The clock position drifts every minute to avoid burn-in.
 */
public class FsDreamService extends DreamService {
    private LinearLayout block;
    private final Runnable drift = new Runnable() {
        @Override public void run() {
            if (block == null) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) block.getLayoutParams();
            lp.leftMargin = (int) (Math.random() * Fs.dp(FsDreamService.this, 240));
            lp.topMargin = (int) (Math.random() * Fs.dp(FsDreamService.this, 160));
            block.setLayoutParams(lp);
            block.postDelayed(this, 60_000);
        }
    };

    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);

        FrameLayout f = new FrameLayout(this);
        f.setBackground(Fs.backdrop());
        f.addView(new Fs.NodeField(this, 110, 0.10f, 0.95f, 0.45f, true, 21));

        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setGravity(Gravity.START);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(64);
        clock.setTextColor(Fs.TEXT);
        clock.setTypeface(Fs.display(this));
        clock.setShadowLayer(Fs.dp(this, 14), 0, 0, 0x8800D4FF);
        block.addView(clock);
        TextView mark = Fs.title(this, "FUTURESPEAK.AI", 16, Fs.CYAN);
        mark.setGravity(Gravity.START);
        mark.setPadding(Fs.dp(this, 4), Fs.dp(this, 6), 0, 0);
        block.addView(mark);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = Fs.dp(this, 80); lp.bottomMargin = Fs.dp(this, 70);
        f.addView(block, lp);
        setContentView(f);
        block.postDelayed(drift, 60_000);
    }

    @Override public void onDetachedFromWindow() {
        if (block != null) block.removeCallbacks(drift);
        block = null;
        super.onDetachedFromWindow();
    }
}

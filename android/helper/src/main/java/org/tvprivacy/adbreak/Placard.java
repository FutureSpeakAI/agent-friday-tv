package org.tvprivacy.adbreak;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen FutureSpeak placard and the corner badge, drawn as accessibility overlays. */
final class Placard {
    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());
    private View root, badge;
    private TextView sub, sound;
    private int remaining = -1;
    private boolean muted;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (root == null) return;
            if (remaining > 0) remaining--;
            render();
            h.postDelayed(this, 1000);
        }
    };

    Placard(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);
    }

    private static WindowManager.LayoutParams lp(int w, int h, boolean opaque) {
        return new WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | (opaque ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0),
                opaque ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT);
    }

    void show(boolean muted, int countdownSec) {
        this.muted = muted;
        if (root == null) {
            root = build();
            wm.addView(root, lp(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true));
            h.postDelayed(tick, 1000);
        }
        update(countdownSec);
    }

    void update(int countdownSec) { if (countdownSec >= 0) remaining = countdownSec; render(); }

    void hide() {
        h.removeCallbacks(tick);
        remaining = -1;
        if (root != null) { View r = root; root = null; try { wm.removeViewImmediate(r); } catch (Throwable ignored) { } }
    }

    boolean placardShowing() { return root != null; }
    boolean badgeShowing() { return badge != null; }

    void showBadge(String ignoredText) {
        if (badge != null) return;
        LinearLayout b = new LinearLayout(svc);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(Fs.glass(svc, 18, Fs.CYAN, 2));
        int p = Fs.dp(svc, 16);
        b.setPadding(p + 6, p - 4, p + 6, p - 4);
        LinearLayout top = new LinearLayout(svc);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Fs.title(svc, "AD BREAK?", 18, Fs.CYAN));
        TextView ag = Fs.body(svc, "   audio guess", 15, Fs.DIM);
        top.addView(ag);
        b.addView(top);
        TextView hints = Fs.body(svc, "BACK  no      OK  yes      DOWN  pause 30 min", 15, Fs.TEXT);
        hints.setGravity(Gravity.START);
        hints.setPadding(0, Fs.dp(svc, 6), 0, 0);
        b.addView(hints);
        WindowManager.LayoutParams l = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false);
        l.gravity = Gravity.TOP | Gravity.END;
        l.x = Fs.dp(svc, 32); l.y = Fs.dp(svc, 28);
        wm.addView(b, l);
        badge = b;
    }

    void hideBadge() {
        if (badge != null) { View b = badge; badge = null; try { wm.removeViewImmediate(b); } catch (Throwable ignored) { } }
    }

    private void render() {
        if (root == null) return;
        sub.setText(remaining > 0 ? String.format("Back soon  ·  about %d:%02d left", remaining / 60, remaining % 60) : "Back soon");
        sound.setText(muted ? "Sound muted" : "Sound on");
    }

    private View build() {
        FrameLayout f = new FrameLayout(svc);
        f.setBackground(Fs.backdrop());
        f.addView(new Fs.NodeField(svc, 90, 0.55f, 0.88f, 0.30f, false, 3),
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Fs.title(svc, "AD BREAK", 58, Fs.CYAN));
        sub = Fs.body(svc, "", 26, Fs.TEXT);
        sub.setPadding(0, Fs.dp(svc, 18), 0, 0);
        col.addView(sub);
        sound = Fs.body(svc, "", 18, Fs.DIM);
        sound.setPadding(0, Fs.dp(svc, 8), 0, Fs.dp(svc, 18));
        col.addView(sound);
        col.addView(Fs.gradientLine(svc), new LinearLayout.LayoutParams(Fs.dp(svc, 360), Fs.dp(svc, 2)));

        LinearLayout hints = new LinearLayout(svc);
        hints.setOrientation(LinearLayout.HORIZONTAL);
        hints.setGravity(Gravity.CENTER);
        hints.setPadding(0, Fs.dp(svc, 56), 0, 0);
        addChip(hints, "BACK", "not an ad");
        addChip(hints, "OK", "yes, it's an ad");
        addChip(hints, "DOWN", "pause 30 min");
        col.addView(hints);

        f.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return f;
    }

    private void addChip(LinearLayout row, String key, String label) {
        TextView k = Fs.title(svc, key, 16, Fs.CYAN);
        k.setShadowLayer(Fs.dp(svc, 6), 0, 0, 0x8800D4FF);
        k.setBackground(Fs.glass(svc, 10, Fs.CYAN, 1.5f));
        k.setPadding(Fs.dp(svc, 14), Fs.dp(svc, 8), Fs.dp(svc, 14), Fs.dp(svc, 8));
        TextView l = Fs.body(svc, label, 18, Fs.TEXT);
        l.setPadding(Fs.dp(svc, 12), 0, Fs.dp(svc, 44), 0);
        row.addView(k); row.addView(l);
    }
}

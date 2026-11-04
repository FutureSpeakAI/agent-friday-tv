package org.tvprivacy.adbreak;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Remote-friendly PIN pad (FutureSpeak look; amber = "needs you").
 * MODE_SET: set the grown-up PIN (twice), then enter kids mode.
 * MODE_EXIT: check the PIN, then leave kids mode.
 * MODE_CHANGE: check the current PIN, then set a new one.
 * MODE_VERIFY: check the PIN and return RESULT_OK (Extend, the kids settings page). Internal only.
 * Started through the exported alias (action KIDS_GROWN_UP, signature permission) it is always
 * MODE_EXIT: extras are ignored, so a caller can't pick a mode, minutes or a target.
 * The PIN only ever lives in memory here; Kids stores a salted hash.
 */
public class PinActivity extends Activity {
    static final String MODE = "mode";
    static final int MODE_SET = 1, MODE_EXIT = 2, MODE_CHANGE = 3, MODE_VERIFY = 4;
    static final String ACTION_KIDS_GROWN_UP = "org.tvprivacy.adbreak.action.KIDS_GROWN_UP";

    private int mode;
    private final StringBuilder entry = new StringBuilder();
    private String first;
    private boolean verified;
    private TextView title, hint;
    private Dots dots;
    private Kids kids;

    /** PIN dots drawn by hand (the brand fonts have no bullet glyphs). */
    final class Dots extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        Dots() { super(PinActivity.this); fill.setColor(Fs.AMBER); ring.setColor(Fs.AMBER); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Fs.dp(PinActivity.this, 2)); }
        @Override protected void onDraw(Canvas c) {
            int n = Math.max(4, entry.length());
            float r = Fs.dp(PinActivity.this, 9), gap = Fs.dp(PinActivity.this, 30), w = (n - 1) * gap, x0 = getWidth() / 2f - w / 2, cy = getHeight() / 2f;
            for (int i = 0; i < n; i++) c.drawCircle(x0 + i * gap, cy, r, i < entry.length() ? fill : ring);
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kids = new Kids(this);
        if (ACTION_KIDS_GROWN_UP.equals(getIntent().getAction())) {
            // From Friday's kids home: leave kids mode only. Ignore every extra.
            if (!kids.hasPin()) { finish(); return; }
            mode = MODE_EXIT;
        } else {
            mode = getIntent().getIntExtra(MODE, MODE_EXIT);
            if (mode == MODE_EXIT && !kids.hasPin()) mode = MODE_SET;
            if (mode == MODE_VERIFY && !kids.hasPin()) { setResult(RESULT_CANCELED); finish(); return; }
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackground(Fs.backdrop());
        title = Fs.title(this, "", 40, Fs.AMBER);
        dots = new Dots();
        hint = Fs.body(this, "", 18, Fs.DIM);
        col.addView(title);
        col.addView(dots, new LinearLayout.LayoutParams(Fs.dp(this, 360), Fs.dp(this, 48)));
        col.addView(hint);

        GridLayout g = new GridLayout(this);
        g.setColumnCount(3);
        g.setPadding(0, Fs.dp(this, 24), 0, 0);
        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "OK"};
        Button firstBtn = null;
        for (String k : keys) {
            Button btn = Fs.button(this, k, k.length() > 2 ? 18 : 26, true);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Fs.dp(this, 120); lp.height = Fs.dp(this, 72);
            lp.setMargins(Fs.dp(this, 7), Fs.dp(this, 7), Fs.dp(this, 7), Fs.dp(this, 7));
            btn.setOnClickListener(v -> press(k));
            g.addView(btn, lp);
            if (firstBtn == null) firstBtn = btn;
        }
        col.addView(g);
        setContentView(col);
        firstBtn.requestFocus();
        render(null);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) { press(String.valueOf(keyCode - KeyEvent.KEYCODE_0)); return true; }
        if (keyCode == KeyEvent.KEYCODE_DEL) { press("DEL"); return true; }
        return super.onKeyDown(keyCode, e);
    }

    private void press(String k) {
        if (k.equals("DEL")) { if (entry.length() > 0) entry.setLength(entry.length() - 1); render(null); return; }
        if (k.equals("OK")) { submit(); return; }
        if (entry.length() < 8) entry.append(k);
        render(null);
    }

    private void submit() {
        String pin = entry.toString();
        entry.setLength(0);
        try {
            boolean setting = mode == MODE_SET || (mode == MODE_CHANGE && verified);
            if (setting) {
                if (pin.length() < 4) { render("Use at least 4 digits."); return; }
                if (first == null) { first = pin; render(null); return; }
                if (!first.equals(pin)) { first = null; render("Those didn't match. Try again."); return; }
                kids.setPin(pin);
                first = null;
                if (mode == MODE_SET) {
                    kids.setActive(true);
                    Events.log(this, "kids: on (first PIN set)");
                    startActivity(new Intent(this, KidsHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
                }
                finish();
                return;
            }
            if (kids.lockedForMs() > 0) { render("Too many tries. Wait a minute."); return; }
            if (!kids.checkPin(pin)) {
                if (kids.lockedForMs() > 0) Events.log(this, "pin: 5 wrong tries, locked 60 s (kids screen)");
                render("Not quite. Try again.");
                return;
            }
            if (mode == MODE_CHANGE) { verified = true; render(null); return; }
            if (mode == MODE_VERIFY) { setResult(RESULT_OK); finish(); return; }
            boolean atLockout = kids.active() && kids.lockedOut();
            kids.openGrownUp();   // one PIN for both apps: Friday sees the grown-up as unlocked too
            Events.log(this, "grown-up: unlocked (kids exit)");
            kids.setActive(false);
            Events.log(this, atLockout ? "kids: off (grown-up PIN at lockout)" : "kids: off (grown-up PIN)");
            AdGuardService s = AdGuardService.instance;
            if (s != null) s.kidsExited();
            finish();
        } catch (Exception ex) {
            render("Something went wrong. Try again.");
        }
    }

    private void render(String msg) {
        boolean setting = mode == MODE_SET || (mode == MODE_CHANGE && verified);
        if (setting) title.setText(first == null ? "CHOOSE A PIN" : "ONCE MORE");
        else title.setText(mode == MODE_CHANGE ? "CURRENT PIN" : "GROWN-UP PIN");
        dots.invalidate();
        hint.setText(msg != null ? msg : (setting ? "4 to 8 digits. Number keys or the pad, then OK." : "Then press OK."));
    }
}

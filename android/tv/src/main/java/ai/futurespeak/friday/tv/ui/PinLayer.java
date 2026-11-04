package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The grown-up PIN pad: an on-screen keypad for the D-pad, and the remote's number keys if
 * it has them. Two modes: check a PIN, or set a new one (typed twice).
 */
public final class PinLayer extends Layer {
    public interface Check {
        /** Returns null if accepted, otherwise the message to show (wrong PIN, locked for N s). */
        String check(String pin);
    }
    public interface Done { void done(String pin); }

    private final boolean setting;
    private final Check check;
    private final Done done;
    private final Runnable cancelled;
    private final StringBuilder entry = new StringBuilder();
    private String first = null;
    private final TextView dots, msg, title;
    private final List<TextView> keys = new ArrayList<>();

    private PinLayer(Context c, Host host, boolean setting, String heading, String why, Check check, Done done, Runnable cancelled) {
        super(c, host);
        this.setting = setting;
        this.check = check;
        this.done = done;
        this.cancelled = cancelled;
        setBackgroundColor(0xF2040610);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setBackground(Brand.glass(c, 22));
        int p = Brand.dp(c, 20);
        col.setPadding(p, p, p, p);

        TextView lock = Brand.text(c, "GROWN-UP PIN", 14, Brand.WARN, Brand.display(c));
        lock.setLetterSpacing(0.15f);
        col.addView(lock);
        title = Brand.text(c, heading, 22, Color.WHITE, Brand.bodyBold(c));
        title.setPadding(0, Brand.dp(c, 12), 0, Brand.dp(c, 4));
        col.addView(title);
        TextView w = Brand.text(c, why == null ? "" : why, 15, Brand.DIM, Brand.body(c));
        w.setGravity(Gravity.CENTER);
        col.addView(w);
        dots = Brand.text(c, "", 28, Look.accent(c), Brand.display(c));
        dots.setPadding(0, Brand.dp(c, 8), 0, Brand.dp(c, 2));
        dots.setLetterSpacing(0.3f);
        col.addView(dots);
        msg = Brand.text(c, " ", 15, Brand.DENY, Brand.body(c));
        msg.setPadding(0, 0, 0, Brand.dp(c, 6));
        col.addView(msg);

        GridLayout grid = new GridLayout(c);
        grid.setColumnCount(3);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK"};
        for (String l : labels) {
            TextView k = Brand.button(c, l, "OK".equals(l) ? Brand.OK : Look.accent(c));
            k.setTextSize(22);
            k.setPadding(0, 0, 0, 0);
            k.setTypeface(Brand.mono(c));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = Brand.dp(c, 84);
            lp.height = Brand.dp(c, 50);
            lp.setMargins(Brand.dp(c, 5), Brand.dp(c, 5), Brand.dp(c, 5), Brand.dp(c, 5));
            k.setOnClickListener(v -> press(l));
            grid.addView(k, lp);
            keys.add(k);
        }
        col.addView(grid);
        TextView hint = Brand.text(c, "Back to cancel", 13, Brand.FAINT, Brand.body(c));
        hint.setPadding(0, Brand.dp(c, 6), 0, 0);
        col.addView(hint);
        addView(col, new FrameLayout.LayoutParams(Brand.dp(c, 640), -2, Gravity.CENTER));
        w.setMaxWidth(Brand.dp(c, 600));
        render();
    }

    public static PinLayer check(Context c, Host h, String why, Check check, Done ok, Runnable cancelled) {
        return new PinLayer(c, h, false, "Enter the grown-up PIN", why, check, ok, cancelled);
    }

    public static PinLayer create(Context c, Host h, String why, Done ok, Runnable cancelled) {
        return new PinLayer(c, h, true, "Choose a grown-up PIN (4 to 8 digits)", why, null, ok, cancelled);
    }

    @Override public void focusFirst() { keys.get(4).requestFocus(); }   // "5", the middle

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        int k = e.getKeyCode();
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) { press(String.valueOf(k - KeyEvent.KEYCODE_0)); return true; }
        if (k == KeyEvent.KEYCODE_DEL) { press("⌫"); return true; }
        return false;
    }

    private void press(String l) {
        msg.setText(" ");
        if ("⌫".equals(l)) { if (entry.length() > 0) entry.deleteCharAt(entry.length() - 1); render(); return; }
        if ("OK".equals(l)) { submit(); return; }
        if (entry.length() < 8) entry.append(l);
        render();
    }

    private void submit() {
        String pin = entry.toString();
        if (!pin.matches("^[0-9]{4,8}$")) { msg.setText("Use 4 to 8 digits."); return; }
        if (setting) {
            if (first == null) {
                first = pin;
                entry.setLength(0);
                title.setText("Type the same PIN again");
                render();
                return;
            }
            if (!first.equals(pin)) {
                first = null;
                entry.setLength(0);
                title.setText("Those didn't match. Choose a PIN");
                msg.setText("Try again.");
                render();
                return;
            }
            host.pop(this);
            done.done(pin);
            return;
        }
        String err = check.check(pin);
        entry.setLength(0);
        render();
        if (err == null) { host.pop(this); done.done(pin); }
        else msg.setText(err);
    }

    private void render() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < entry.length(); i++) s.append('●');
        for (int i = entry.length(); i < 4; i++) s.append('○');
        dots.setText(s.toString());
    }

    @Override public void back() {
        host.pop(this);
        if (cancelled != null) cancelled.run();
    }
}

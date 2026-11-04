package org.tvprivacy.adbreak;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Kids page, behind the PIN (kids slice 1): daily limit, Count everything, and the weekday and
 * weekend wind-down, bedtime and morning. Nothing is pre-selected (K-D5). Internal, not exported.
 * The schedule can only be changed here (KM-E01); no provider method can change it.
 */
public class KidsSettingsActivity extends Activity {
    static final int REQ_PIN = 9;
    private Kids kids;
    private boolean unlocked;
    private LinearLayout box;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kids = new Kids(this);
        if (!kids.hasPin()) { finish(); return; }
        startActivityForResult(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_VERIFY), REQ_PIN);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PIN) return;
        if (res != RESULT_OK) { finish(); return; }
        unlocked = true;
        build();
    }

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackground(Fs.backdrop());
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Fs.dp(this, 80), Fs.dp(this, 40), Fs.dp(this, 80), Fs.dp(this, 40));
        sv.addView(box);
        setContentView(sv);
        render();
    }

    private void render() {
        box.removeAllViews();
        box.addView(Fs.title(this, "KIDS · CLOCK AND BEDTIME", 34, Fs.AMBER));
        TextView n = Fs.body(this, "Changes here need the grown-up PIN. Nothing is set until you choose it. "
                + "Weekend means Friday night and Saturday night (bedtime that evening, and the next morning).", 16, Fs.DIM);
        n.setPadding(0, Fs.dp(this, 10), 0, Fs.dp(this, 20));
        box.addView(n);

        Button first = row("Daily limit", limitLabel(), () -> {
            int[] opts = {60, 90, 120, 150, 180, 0};
            String[] labels = {"1 hour", "1 h 30 min", "2 hours", "2 h 30 min", "3 hours", "No limit"};
            pick("Daily limit", labels, i -> { kids.setLimitMin(opts[i]); Events.log(this, "settings: daily limit " + labels[i] + " (PIN)"); });
        });
        row("Count everything", kids.countEverything() ? "On: Friday time counts too" : "Off: only watching counts", () -> {
            boolean on = !kids.countEverything();
            kids.setCountEverything(on);
            Events.log(this, "settings: count everything " + (on ? "on" : "off") + " (PIN)");
            render();
        });
        for (int w = 0; w < 2; w++) {
            boolean weekend = w == 1;
            TextView h = Fs.title(this, weekend ? "WEEKEND (FRI AND SAT NIGHTS)" : "WEEKDAY (SUN TO THU NIGHTS)", 20, Fs.CYAN);
            h.setGravity(Gravity.START);
            h.setPadding(0, Fs.dp(this, 22), 0, Fs.dp(this, 6));
            box.addView(h);
            KidsSchedule.Times t = kids.times(weekend);
            timeRow(weekend, "wind", "Wind-down", t.wind, 17 * 60, 22 * 60);
            timeRow(weekend, "bed", "Bedtime (lockout)", t.bed, 18 * 60, 23 * 60 + 30);
            timeRow(weekend, "morning", "Morning (TV wakes up)", t.morning, 5 * 60, 10 * 60);
        }
        Button done = Fs.button(this, "Done", 18, true);
        done.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dl.setMargins(0, Fs.dp(this, 30), 0, 0);
        box.addView(done, dl);
        if (first != null) first.requestFocus();
    }

    private void timeRow(boolean weekend, String which, String label, int cur, int from, int to) {
        row(label, cur < 0 ? "Not set" : fmt(cur), () -> {
            List<String> labels = new ArrayList<>();
            List<Integer> vals = new ArrayList<>();
            labels.add("Not set"); vals.add(-1);
            for (int m = from; m <= to; m += 15) { labels.add(fmt(m)); vals.add(m); }
            pick(label, labels.toArray(new String[0]), i -> {
                kids.setTime(weekend, which, vals.get(i));
                Events.log(this, "settings: " + (weekend ? "weekend " : "weekday ") + which + " " + labels.get(i) + " (PIN)");
            });
        });
    }

    interface Picked { void at(int i); }
    private void pick(String title, String[] labels, Picked p) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title)
                .setItems(labels, (d, i) -> { p.at(i); render(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private Button row(String label, String value, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Fs.body(this, label, 20, Fs.TEXT);
        l.setGravity(Gravity.START);
        r.addView(l, new LinearLayout.LayoutParams(Fs.dp(this, 360), LinearLayout.LayoutParams.WRAP_CONTENT));
        Button b = Fs.button(this, value, 18, false);
        b.setOnClickListener(v -> onClick.run());
        r.addView(b, new LinearLayout.LayoutParams(Fs.dp(this, 380), LinearLayout.LayoutParams.WRAP_CONTENT));
        r.setPadding(0, Fs.dp(this, 4), 0, Fs.dp(this, 4));
        box.addView(r);
        return b;
    }

    private String limitLabel() { int m = kids.limitMin(); return m == 0 ? "No limit" : (m % 60 == 0 ? (m / 60) + (m == 60 ? " hour" : " hours") : (m / 60) + " h " + (m % 60) + " min"); }

    static String fmt(int m) {
        int h = m / 60, mm = m % 60;
        String ap = h < 12 ? "am" : "pm";
        int h12 = h % 12 == 0 ? 12 : h % 12;
        return h12 + ":" + (mm < 10 ? "0" : "") + mm + " " + ap;
    }

    @Override protected void onPause() {
        super.onPause();
        // Leaving the page locks it again; coming back asks for the PIN.
        if (unlocked && isFinishing()) unlocked = false;
    }
}

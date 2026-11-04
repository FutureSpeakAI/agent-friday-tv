package org.tvprivacy.adbreak;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The bedtime lockout (kids slice 1, AC-K6/AC-K7): the night look, "Goodnight. The TV wakes up at 7:00."
 * Grown-up: PIN, then kids mode ends. Extend: PIN, then 10/20/30 minutes (none pre-selected).
 * Extend exists only here and on the time's-up screen; no provider method can do it (KM-E01).
 */
public class BedtimeActivity extends Activity {
    static final int REQ_EXTEND = 7;
    private Kids kids;
    private TextView sub;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kids = new Kids(this);
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(0xFF03040A);
        f.addView(new Fs.NodeField(this, 60, 0.05f, 0.25f, 0.18f, false, 13));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Fs.title(this, "GOODNIGHT", 54, 0xFF7B61FF));
        sub = Fs.body(this, "", 26, Fs.TEXT);
        sub.setPadding(0, Fs.dp(this, 20), 0, Fs.dp(this, 26));
        col.addView(sub);
        col.addView(new TimeUpActivity.Stars(this), new LinearLayout.LayoutParams(Fs.dp(this, 320), Fs.dp(this, 64)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button grown = Fs.button(this, "Grown-up", 16, false);
        grown.setTextColor(Fs.DIM);
        grown.setOnClickListener(v -> startActivity(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_EXIT)));
        Button extend = Fs.button(this, "Extend", 16, false);
        extend.setTextColor(Fs.DIM);
        extend.setOnClickListener(v -> startActivityForResult(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_VERIFY), REQ_EXTEND));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.setMargins(Fs.dp(this, 14), 0, Fs.dp(this, 14), 0);
        row.addView(grown, bl);
        row.addView(extend, bl);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rl.setMargins(0, Fs.dp(this, 80), 0, 0);
        col.addView(row, rl);
        f.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(f);
        grown.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!kids.active() || !kids.lockedOut()) { finish(); return; }
        long m = kids.schedule().morningAt(System.currentTimeMillis());
        if (m > 0) {
            java.text.DateFormat df = android.text.format.DateFormat.getTimeFormat(this);
            sub.setText("The TV wakes up at " + df.format(new java.util.Date(m)) + ".");
        } else sub.setText("Time to sleep. See you in the morning.");
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXTEND || res != RESULT_OK) return;
        ExtendChoice.show(this, minutes -> {
            kids.extendBedtime(minutes);
            Events.log(this, "time: extended " + minutes + " min by PIN (bedtime)");
            finish();
        });
    }

    @Override public void onBackPressed() { }

    /** 10 / 20 / 30 minutes, none pre-selected (focus starts on Cancel). */
    static final class ExtendChoice {
        interface Pick { void minutes(int m); }
        static void show(Activity a, Pick pick) {
            String[] labels = {"Cancel", "10 more minutes", "20 more minutes", "30 more minutes"};
            int[] mins = {0, 10, 20, 30};
            new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("How much longer?")
                    .setItems(labels, (d, i) -> { if (mins[i] > 0) pick.minutes(mins[i]); })
                    .show();
        }
    }
}

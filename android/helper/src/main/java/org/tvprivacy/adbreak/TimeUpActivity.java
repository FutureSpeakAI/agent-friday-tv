package org.tvprivacy.adbreak;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Friendly "All done for today" (FutureSpeak look). A grown-up PIN unlocks. */
public class TimeUpActivity extends Activity {
    static final int REQ_EXTEND = 7;

    static final class Stars extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Stars(android.content.Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), w = getWidth(), r = h * 0.42f;
            for (int k = 0; k < 3; k++) {
                float cx = w / 2 + (k - 1) * r * 3.0f, cy = h / 2;
                Path path = new Path();
                for (int j = 0; j < 10; j++) {
                    float rr = j % 2 == 0 ? r : r * 0.45f; double t = -Math.PI / 2 + j * Math.PI / 5;
                    float x = cx + (float) Math.cos(t) * rr, y = cy + (float) Math.sin(t) * rr;
                    if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                path.close();
                p.setColor(Fs.grad(k / 2f));
                cv.drawPath(path, p);
            }
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Fs.backdrop());
        f.addView(new Fs.NodeField(this, 80, 0.06f, 0.32f, 0.30f, false, 11));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Fs.title(this, "ALL DONE FOR TODAY", 50, Fs.CYAN));
        android.widget.TextView s = Fs.body(this, "Great watching! The TV will be ready again tomorrow.", 26, Fs.TEXT);
        s.setPadding(0, Fs.dp(this, 20), 0, Fs.dp(this, 26));
        col.addView(s);
        col.addView(new Stars(this), new LinearLayout.LayoutParams(Fs.dp(this, 320), Fs.dp(this, 64)));
        Button grown = Fs.button(this, "Grown-up", 16, false);
        grown.setTextColor(Fs.DIM);
        grown.setOnClickListener(v -> startActivity(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_EXIT)));
        Button extend = Fs.button(this, "Extend", 16, false);
        extend.setTextColor(Fs.DIM);
        extend.setOnClickListener(v -> startActivityForResult(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_VERIFY), REQ_EXTEND));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.setMargins(Fs.dp(this, 14), 0, Fs.dp(this, 14), 0);
        row.addView(grown, bl);
        row.addView(extend, bl);
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gl.setMargins(0, Fs.dp(this, 80), 0, 0);
        col.addView(row, gl);
        f.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(f);
        grown.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        Kids k = new Kids(this);
        if (!k.active() || !k.timeUp()) finish();
    }

    /** Extend (PIN, then 10/20/30): the only way to more time today (KM-E01). */
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_EXTEND || res != RESULT_OK) return;
        BedtimeActivity.ExtendChoice.show(this, minutes -> {
            new Kids(this).extendToday(minutes);
            Events.log(this, "time: extended " + minutes + " min by PIN (daily limit)");
            finish();
        });
    }

    @Override public void onBackPressed() { }
}

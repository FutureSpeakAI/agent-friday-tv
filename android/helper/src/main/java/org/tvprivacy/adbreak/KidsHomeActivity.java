package org.tvprivacy.adbreak;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Kids-mode home (FutureSpeak look): one big PBS KIDS tile, time left today, a small grown-up button. */
public class KidsHomeActivity extends Activity {
    private TextView left;
    private Kids kids;

    /** Simple sun glyph so the tile needs no third-party logo. */
    static final class Sun extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Sun(android.content.Context c) { super(c); p.setColor(0xFFFFD640); p.setStrokeWidth(Fs.dp(c, 6)); p.setStrokeCap(Paint.Cap.ROUND); }
        @Override protected void onDraw(Canvas cv) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = Math.min(cx, cy) * 0.55f;
            cv.drawCircle(cx, cy, r * 0.6f, p);
            for (int k = 0; k < 8; k++) {
                double t = k * Math.PI / 4;
                cv.drawLine(cx + (float) Math.cos(t) * r * 0.85f, cy + (float) Math.sin(t) * r * 0.85f,
                        cx + (float) Math.cos(t) * r * 1.3f, cy + (float) Math.sin(t) * r * 1.3f, p);
            }
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        kids = new Kids(this);
        FrameLayout f = new FrameLayout(this);
        f.setBackground(Fs.backdrop());
        f.addView(new Fs.NodeField(this, 90, 0.72f, 0.97f, 0.35f, false, 7));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(Fs.title(this, "KIDS TIME", 54, Fs.CYAN));

        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setFocusable(true);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_focused}, Fs.glass(this, 28, Fs.CYAN, 4));
        st.addState(new int[]{}, Fs.glass(this, 28, Fs.EDGE, 2));
        tile.setBackground(st);
        tile.addView(new Sun(this), new LinearLayout.LayoutParams(Fs.dp(this, 120), Fs.dp(this, 110)));
        tile.addView(Fs.title(this, "PBS KIDS", 36, 0xFFFFFFFF));
        tile.setPadding(Fs.dp(this, 90), Fs.dp(this, 26), Fs.dp(this, 90), Fs.dp(this, 30));
        tile.setOnClickListener(v -> openPbs());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.setMargins(0, Fs.dp(this, 40), 0, Fs.dp(this, 30));
        col.addView(tile, tl);

        left = Fs.body(this, "", 24, 0xFFFFE082);
        col.addView(left);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(Fs.dp(this, 280), Fs.dp(this, 2));
        ll.setMargins(0, Fs.dp(this, 14), 0, Fs.dp(this, 40));
        col.addView(Fs.gradientLine(this), ll);

        Button grown = Fs.button(this, "Grown-up", 16, false);
        grown.setTextColor(Fs.DIM);
        grown.setOnClickListener(v -> startActivity(new Intent(this, PinActivity.class).putExtra(PinActivity.MODE, PinActivity.MODE_EXIT)));
        col.addView(grown);

        f.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(f);
        tile.requestFocus();
    }

    private void openPbs() {
        if (kids.timeUp()) { startActivity(new Intent(this, TimeUpActivity.class)); return; }
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(Kids.PBS_KIDS);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(Kids.PBS_KIDS);
        if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); }
    }

    @Override protected void onResume() {
        super.onResume();
        if (!kids.active()) { finish(); return; }
        if (kids.timeUp()) { startActivity(new Intent(this, TimeUpActivity.class)); return; }
        long ms = kids.remainingMs();
        if (ms == Long.MAX_VALUE) { left.setText(""); return; }
        long min = (ms + 59_999) / 60_000, hh = min / 60, mm = min % 60;
        left.setText((hh > 0 ? hh + (hh == 1 ? " hour " : " hours ") : "") + mm + " min of TV left today");
    }

    @Override public void onBackPressed() { }
}

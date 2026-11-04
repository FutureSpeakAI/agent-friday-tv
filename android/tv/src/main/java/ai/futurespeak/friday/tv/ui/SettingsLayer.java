package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Settings: a list of rows. Rows marked locked ask for the grown-up PIN (handled by the caller). */
public final class SettingsLayer extends Layer {
    public static final class Row {
        final String label; final Supplier<String> value; final boolean locked; final Runnable action; final boolean enabled;
        public Row(String label, Supplier<String> value, boolean locked, Runnable action) { this(label, value, locked, action, true); }
        public Row(String label, Supplier<String> value, boolean locked, Runnable action, boolean enabled) {
            this.label = label; this.value = value; this.locked = locked; this.action = action; this.enabled = enabled;
        }
    }

    private final LinearLayout list;
    private final List<Row> rows;
    private final List<TextView> values = new ArrayList<>();
    private View first;

    public SettingsLayer(Context c, Host host, List<Row> rows, String footer) {
        super(c, host);
        this.rows = rows;
        setBackgroundColor(0xF0040610);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = Brand.dp(c, 36);
        col.setPadding(Brand.dp(c, 80), p, Brand.dp(c, 80), Brand.dp(c, 16));
        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Brand.wordmark(c, 22));
        TextView s = Brand.text(c, "   SETTINGS", 18, Brand.DIM, Brand.display(c));
        s.setLetterSpacing(0.12f);
        head.addView(s);
        col.addView(head);

        ScrollView sv = new ScrollView(c);
        sv.setClipToPadding(false);
        sv.setPadding(0, Brand.dp(c, 18), 0, Brand.dp(c, 18));
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView foot = Brand.text(c, footer, 14, Brand.FAINT, Brand.body(c));
        col.addView(foot);
        addView(col, new LayoutParams(-1, -1));

        for (Row r : rows) {
            LinearLayout line = new LinearLayout(c);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setFocusable(r.enabled);
            line.setClickable(r.enabled);
            line.setBackground(Brand.focusable(c, 14, Look.accent(c)));
            line.setPadding(Brand.dp(c, 24), Brand.dp(c, 16), Brand.dp(c, 24), Brand.dp(c, 16));
            TextView l = Brand.text(c, r.label + (r.locked ? "  🔒" : ""), 21, r.enabled ? Brand.TEXT : Brand.FAINT, Brand.body(c));
            line.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            TextView v = Brand.text(c, "", 19, r.enabled ? Look.accent(c) : Brand.FAINT, Brand.body(c));
            line.addView(v);
            values.add(v);
            if (r.enabled && r.action != null) line.setOnClickListener(x -> r.action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = Brand.dp(c, 8);
            list.addView(line, lp);
            if (first == null && r.enabled) first = line;
        }
        refresh();
    }

    public void refresh() {
        for (int i = 0; i < rows.size(); i++) {
            Supplier<String> s = rows.get(i).value;
            values.get(i).setText(s == null ? "" : s.get());
        }
    }

    @Override public void focusFirst() { if (first != null) first.requestFocus(); }
}

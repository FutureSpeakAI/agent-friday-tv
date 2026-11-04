package ai.futurespeak.friday.tv.studio;

import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ai.futurespeak.friday.tv.ui.Brand;

/**
 * The "Made by Friday" row for Friday Home (spec §8.6), self-contained so Home adds it with
 * one line: {@code new LibraryRow(context)}, then {@link #refresh()} on
 * resume. It reads the sealed index only; it is GONE when there is nothing to show. OK on a
 * tile plays a finished piece, or opens Studio for one still being made.
 */
public final class LibraryRow extends LinearLayout {
    private final LinearLayout tiles;

    public LibraryRow(Context c) {
        super(c);
        setOrientation(VERTICAL);
        TextView label = Brand.text(c, "MADE BY FRIDAY", 14, Brand.DIM, Brand.bodyBold(c));
        label.setPadding(0, 0, 0, Brand.dp(c, 8));
        addView(label);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        tiles = new LinearLayout(c);
        tiles.setClipChildren(false);
        hs.addView(tiles);
        addView(hs, new LayoutParams(-1, -2));
        setClipChildren(false);
        refresh();
    }

    /** Re-reads the Library (call on resume). */
    public void refresh() {
        tiles.removeAllViews();
        Studio s = Studio.get();
        List<Library.Item> items = s == null ? java.util.Collections.<Library.Item>emptyList() : s.library().items();
        for (Library.Item it : items) {
            View t = tile(getContext(), it, () -> open(it));
            LayoutParams lp = new LayoutParams(Brand.dp(getContext(), 320), -2);
            lp.rightMargin = Brand.dp(getContext(), 16);
            tiles.addView(t, lp);
        }
        setVisibility(items.isEmpty() ? GONE : VISIBLE);
    }

    private void open(Library.Item it) {
        Context c = getContext();
        if (it.playable()) c.startActivity(new Intent(c, StudioPlayerActivity.class).putExtra(StudioPlayerActivity.EXTRA_JOB, it.id));
        else c.startActivity(new Intent(c, StudioActivity.class).setAction(StudioActivity.ACTION_LIBRARY));
    }

    /** One piece: a waveform glyph, the title, kind and length, a "new" dot, and what's happening to it. */
    static View tile(Context c, Library.Item it, Runnable onOk) {
        LinearLayout t = new LinearLayout(c);
        t.setOrientation(VERTICAL);
        t.setBackground(Brand.focusable(c, 18, Brand.CYAN));
        t.setPadding(Brand.dp(c, 20), Brand.dp(c, 14), Brand.dp(c, 20), Brand.dp(c, 14));   // after the background, which resets padding
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(v -> onOk.run());
        Brand.growOnFocus(t);
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Brand.text(c, "▁▃▆▃▅▂", 18, Brand.CYAN, Brand.body(c)));
        TextView kind = Brand.text(c, "  PODCAST · " + it.minutes + " MIN", 13, Brand.DIM, Brand.bodyBold(c));
        top.addView(kind);
        if (it.fresh) top.addView(Brand.text(c, "  ●", 14, Brand.MAGENTA, Brand.body(c)));
        t.addView(top);
        TextView title = Brand.text(c, it.title, 20, 0xFFFFFFFF, Brand.bodyBold(c));
        title.setMaxLines(2);
        title.setPadding(0, Brand.dp(c, 6), 0, 0);
        t.addView(title);
        String status;
        switch (it.state) {
            case Job.DONE: status = it.note.isEmpty() ? (it.fresh ? "New" : "Ready") : it.note; break;
            case Job.BUILDING: status = it.milestone.isEmpty() ? "Making…" : "Making… " + lowerFirst(it.milestone); break;
            case Job.PAUSED: status = "Waiting for your OK"; break;
            case Job.STOPPED: status = "Stopped"; break;
            default: status = it.note.isEmpty() ? "Didn't finish" : it.note;
        }
        TextView st = Brand.text(c, status, 15, Job.PAUSED.equals(it.state) ? Brand.WARN : Brand.DIM, Brand.body(c));
        st.setMaxLines(2);
        t.addView(st);
        return t;
    }

    static String lowerFirst(String s) { return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1); }
}

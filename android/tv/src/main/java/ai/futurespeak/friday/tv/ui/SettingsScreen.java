package ai.futurespeak.friday.tv.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Friday's settings, in the FutureSpeak look: a glass navigation column on the left (one
 * entry per area, with a one-line summary) and the chosen area's page on the right, built
 * from real controls ({@link SettingsPage}): option pills, switches, steppers and cards.
 * Moving through the left column previews each page; Right (or OK) goes into it; Back
 * returns to the column, and Back again closes.
 */
public final class SettingsScreen extends Layer {
    /** One area of settings. */
    public static final class Section {
        final String id, icon, title;
        final Supplier<String> summary;
        final SettingsPage.Builder builder;
        public Section(String id, String icon, String title, Supplier<String> summary, SettingsPage.Builder builder) {
            this.id = id; this.icon = icon; this.title = title; this.summary = summary; this.builder = builder;
        }
    }

    private final List<Section> sections;
    private final List<View> navItems = new ArrayList<>();
    private final List<TextView> navSummaries = new ArrayList<>();
    private final ScrollView pageScroll;
    private final LinearLayout pageCol;
    private int current = -1;
    private boolean inPage = false;
    /** True while a page is being rebuilt: focus parks on its section without switching pages. */
    private boolean rebuilding = false;
    /** A specific page was asked for (open): the column must not take focus back. */
    private boolean openPending = false;

    public SettingsScreen(Context c, Host host, List<Section> sections, String footer) {
        super(c, host);
        this.sections = sections;
        setBackground(Look.stageLight(c));

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(-1, -1));

        // ---- navigation column ----
        LinearLayout nav = new LinearLayout(c);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setPadding(dp(34), dp(30), dp(18), dp(20));
        LinearLayout brand = new LinearLayout(c);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(Brand.wordmark(c, 17));
        TextView eyebrow = Brand.text(c, "SETTINGS", 10.5f, Brand.DIM, Brand.display(c));
        eyebrow.setLetterSpacing(0.32f);
        eyebrow.setPadding(dp(2), dp(6), 0, dp(16));
        brand.addView(eyebrow);
        nav.addView(brand);

        ScrollView navScroll = new ScrollView(c);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.setClipToPadding(false);
        LinearLayout navList = new LinearLayout(c);
        navList.setOrientation(LinearLayout.VERTICAL);
        navList.setPadding(dp(6), dp(6), dp(6), dp(8));   // room for the focused entry's lift
        navList.setClipChildren(false);
        navScroll.setClipChildren(false);
        nav.setClipChildren(false);
        navScroll.addView(navList);
        nav.addView(navScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView foot = Brand.text(c, footer, 9.5f, Brand.FAINT, Brand.body(c));
        foot.setPadding(dp(2), dp(10), 0, 0);
        foot.setLineSpacing(dp(2), 1f);
        nav.addView(foot);
        row.addView(nav, new LinearLayout.LayoutParams(dp(300), -1));

        for (int i = 0; i < sections.size(); i++) {
            final int idx = i;
            Section s = sections.get(i);
            LinearLayout item = new LinearLayout(c);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(10), dp(12), dp(10));
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClickable(true);
            item.setDefaultFocusHighlightEnabled(false);
            item.setBackground(Look.focusable(c, 12, Look.accent(c), true));
            LineIcon icon = new LineIcon(c, s.id);
            icon.setColor(Look.alpha(Look.accent(c), 0xB3));
            item.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(26)));
            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(14), 0, 0, 0);
            TextView t = Brand.text(c, s.title, 14.5f, Brand.TEXT, Brand.bodyBold(c));
            texts.addView(t);
            TextView sum = Brand.text(c, "", 10.5f, Brand.DIM, Brand.body(c));
            sum.setSingleLine(true);
            sum.setEllipsize(TextUtils.TruncateAt.END);
            sum.setPadding(0, dp(3), 0, 0);
            texts.addView(sum);
            navSummaries.add(sum);
            item.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
            item.setOnFocusChangeListener((v, has) -> {
                t.setTextColor(has ? 0xFFFFFFFF : Brand.TEXT);
                icon.setColor(has ? Look.accent(getContext()) : Look.alpha(Look.accent(getContext()), 0xB3));
                if (has && !rebuilding && !(openPending && idx != current)) { inPage = false; show(idx); }
            });
            Look.lift(item, 1.03f);
            Look.reveal(item, i);
            item.setOnClickListener(v -> enterPage());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            navList.addView(item, lp);
            navItems.add(item);
        }

        // ---- page ----
        View rule = new View(c);
        rule.setBackgroundColor(Look.alpha(Look.accent(c), 0x1F));
        row.addView(rule, new LinearLayout.LayoutParams(Math.max(1, dp(1)), -1));
        pageScroll = new ScrollView(c);
        pageScroll.setClipToPadding(false);
        pageScroll.setVerticalScrollBarEnabled(false);
        pageScroll.setSmoothScrollingEnabled(true);
        pageCol = new LinearLayout(c);
        pageCol.setOrientation(LinearLayout.VERTICAL);
        pageCol.setPadding(dp(40), dp(32), dp(48), dp(48));
        pageCol.setClipChildren(false);
        pageScroll.addView(pageCol, new FrameLayout.LayoutParams(-1, -2));
        row.addView(pageScroll, new LinearLayout.LayoutParams(0, -1, 1));
        refreshSummaries();
    }

    private int dp(float v) { return Brand.dp(getContext(), v); }

    private GradientDrawable backdrop() {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0A1424, 0xFF050812, 0xFF03040A});
        return g;
    }

    private android.graphics.drawable.StateListDrawable navBackground() {
        GradientDrawable on = new GradientDrawable();
        on.setColor(0x2600D4FF);
        on.setCornerRadius(dp(12));
        on.setStroke(Math.max(1, dp(1.5f)), 0xCC00D4FF);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0x0FFFFFFF);
        sel.setCornerRadius(dp(12));
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{android.R.attr.state_selected}, sel);
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        return s;
    }

    private void show(int i) {
        if (i == current) return;
        for (int k = 0; k < navItems.size(); k++) navItems.get(k).setSelected(k == i);
        current = i;
        build(null);
        pageScroll.scrollTo(0, 0);
        Look.revealChildren(pageCol);   // a new page arrives row by row; rebuilds after a change don't
    }

    /** Rebuilds the current page (after a change), keeping focus on the same control. */
    public void rebuild() {
        View f = findFocus();
        Object tag = f == null ? null : f.getTag();
        build(tag instanceof String && inPage ? (String) tag : null);
        refreshSummaries();
    }

    public void refreshSummaries() {
        for (int i = 0; i < sections.size(); i++) {
            Supplier<String> s = sections.get(i).summary;
            navSummaries.get(i).setText(s == null ? "" : s.get());
        }
    }

    private void build(String focusTag) {
        int y = pageScroll.getScrollY();
        if (focusTag != null && current >= 0) {
            // Removing the focused control would hand focus to the first section (and switch
            // pages); park it on this page's own section while the page is rebuilt.
            rebuilding = true;
            navItems.get(current).requestFocus();
        }
        pageCol.removeAllViews();
        if (current < 0) return;
        Section s = sections.get(current);
        SettingsPage page = new SettingsPage(getContext(), pageCol, this);
        s.builder.build(page);
        page.finish();
        if (focusTag != null) {
            pageScroll.scrollTo(0, y);
            pageCol.post(() -> {
                View v = pageCol.findViewWithTag(focusTag);
                if (v != null) { inPage = true; v.requestFocus(); pageScroll.scrollTo(0, y); } else enterPage();
                rebuilding = false;
            });
        }
    }

    /** Opens a page directly (e.g. from the screensaver's settings link). */
    public void open(String id) {
        for (int i = 0; i < sections.size(); i++) if (sections.get(i).id.equals(id)) {
            openPending = true;
            navItems.get(i).requestFocus();
            show(i);
            // Views can't take focus before their first layout: wait for a real layout pass.
            afterLayout(() -> { enterPage(); pageScroll.scrollTo(0, 0); openPending = false; });
            return;
        }
    }

    /** Runs once the page has been laid out with a real size (focus needs that). */
    private void afterLayout(Runnable r) {
        android.view.ViewTreeObserver vto = pageCol.getViewTreeObserver();
        vto.addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                if (pageCol.getHeight() == 0 || firstFocusable(pageCol) == null || firstFocusable(pageCol).getWidth() == 0) return;
                pageCol.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                pageCol.post(r);
            }
        });
        pageCol.requestLayout();
    }

    private void enterPage() {
        View first = firstFocusable(pageCol);
        if (first != null) { inPage = true; first.requestFocus(); }
    }

    private static View firstFocusable(ViewGroup g) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != VISIBLE) continue;
            if (v.isFocusable() && v.isEnabled()) return v;
            if (v instanceof ViewGroup) { View f = firstFocusable((ViewGroup) v); if (f != null) return f; }
        }
        return null;
    }

    @Override public boolean key(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
        View f = findFocus();
        boolean onNav = f != null && navItems.contains(f);
        if (onNav && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) { enterPage(); return true; }
        if (!onNav && inPage && e.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
            // Left from the leftmost control in a row goes back to the column.
            View next = f == null ? null : f.focusSearch(View.FOCUS_LEFT);
            if (next == null || !isInPage(next)) { backToNav(); return true; }
        }
        return false;
    }

    private boolean isInPage(View v) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == pageCol) return true;
        return false;
    }

    private void backToNav() {
        inPage = false;
        if (current >= 0) navItems.get(current).requestFocus();
    }

    @Override public void back() {
        View f = findFocus();
        if (f != null && !navItems.contains(f) && current >= 0) { backToNav(); return; }
        super.back();
    }

    @Override public void focusFirst() {
        if (navItems.isEmpty()) return;
        View n = navItems.get(Math.max(0, current));
        if (!n.requestFocus()) afterLayout(() -> { if (!inPage && !openPending) n.requestFocus(); });
    }
}

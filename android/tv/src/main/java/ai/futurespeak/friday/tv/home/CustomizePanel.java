package ai.futurespeak.friday.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import ai.futurespeak.friday.tv.ui.Brand;

/**
 * Customize Home, by remote: a glass panel over Home. Up and Down move between rows, Left and
 * Right change a value, OK toggles, and Back closes. Changes apply live and are kept on the TV.
 */
final class CustomizePanel extends FrameLayout {
    private final HomeActivity home;
    private final LinearLayout list;
    private final LinearLayout card;
    private final List<Row> rows = new ArrayList<>();
    private int focusIndex = 0;

    private abstract class Row {
        final String label;
        TextView value;
        View view;
        Row(String label) { this.label = label; }
        abstract String value();
        abstract void change(int dir);   // dir: -1, +1, or 0 for OK
        String hint() { return null; }
    }

    CustomizePanel(HomeActivity h) {
        super(h);
        home = h;
        setBackgroundColor(0x99060B19);
        setClickable(true);
        card = new LinearLayout(h);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF20A0E1A);
        g.setCornerRadius(h.dp(14));
        g.setStroke(1, 0x1FFFFFFF);
        card.setBackground(g);
        card.setPadding(h.dp(26), h.dp(22), h.dp(26), h.dp(18));
        card.setElevation(h.dp(24));

        TextView eyebrow = Brand.text(h, "CUSTOMIZE HOME", 10, h.accent(), Brand.display(h));
        eyebrow.setLetterSpacing(0.13f);
        card.addView(eyebrow);
        TextView title = Brand.text(h, "Make Home yours", 20, 0xF2FFFFFF, Brand.display(h));
        title.setPadding(0, h.dp(4), 0, h.dp(2));
        card.addView(title);
        card.addView(Brand.text(h, "◀ ▶ change  ·  OK toggles  ·  Back closes. To pin or move an app, focus it on Home and press Menu (or hold OK).", 11, Brand.DIM, Brand.body(h)));

        ScrollView sv = new ScrollView(h);
        sv.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(h);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, h.dp(12), 0, 0);
        sv.addView(list);
        card.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));

        LayoutParams cl = new LayoutParams(h.dp(430), -1, Gravity.END);
        cl.topMargin = h.dp(22); cl.bottomMargin = h.dp(22); cl.rightMargin = h.dp(22);
        addView(card, cl);
        populate();
    }

    private void populate() {
        HomeLayout l = home.layout;
        list.removeAllViews();
        rows.clear();
        section("Look");
        add(new Row("Accent") {
            String value() { return HomeLayout.ACCENT_NAMES[Math.floorMod(l.accent, HomeLayout.ACCENTS.length)]; }
            void change(int d) { l.accent = Math.floorMod(l.accent + (d == 0 ? 1 : d), HomeLayout.ACCENTS.length); home.changed("board"); populateKeepFocus(); }
        });
        add(new Row("Wallpaper") {
            String value() { return nice(l.wallpaper.name()); }
            void change(int d) { l.wallpaper = cycle(HomeLayout.Wallpaper.values(), l.wallpaper, d); home.changed("wallpaper"); }
            String hint() { return "Ambient stars, the FutureSpeak set, or solid"; }
        });
        add(new Row("Motion") {
            String value() { return nice(l.motion.name()); }
            void change(int d) { l.motion = cycle(HomeLayout.Motion.values(), l.motion, d); home.changed("motion"); }
            String hint() { return "Full: drift and parallax · Calm: gentle fades · Off: still"; }
        });
        section("Friday");
        add(new Row("Friday's avatar") {
            String value() { return l.avatar ? "On" : "Off"; }
            void change(int d) { l.avatar = !l.avatar; home.changed("structure"); }
        });
        add(new Row("Avatar size") {
            String value() { return HomeLayout.STAGE_NAMES[Math.max(0, Math.min(2, l.avatarSize))]; }
            void change(int d) { l.avatarSize = d == 0 ? (l.avatarSize + 1) % 3 : Math.max(0, Math.min(2, l.avatarSize + d)); home.changed("size"); }
        });
        add(new Row("Avatar side") {
            String value() { return l.side == HomeLayout.Side.RIGHT ? "Right" : "Left"; }
            void change(int d) { l.side = l.side == HomeLayout.Side.RIGHT ? HomeLayout.Side.LEFT : HomeLayout.Side.RIGHT; home.changed("structure"); }
        });
        section("Board");
        add(new Row("Clock") {
            String value() { return l.clock ? "On" : "Off"; }
            void change(int d) { l.clock = !l.clock; home.changed("board"); }
        });
        add(new Row("Weather") {
            String value() { return l.weather ? "On" : "Off"; }
            void change(int d) { l.weather = !l.weather; home.changed("board"); }
        });
        List<String> order = l.rowOrder();
        for (String r : order) {
            String name = HomeLayout.ROW_NAMES[HomeLayout.ROWS.indexOf(r)];
            add(new Row(name) {
                String value() { return (l.hiddenRows.contains(r) ? "Hidden" : "Shown") + "  ·  " + (order.indexOf(r) + 1); }
                void change(int d) {
                    if (d == 0) { if (!l.hiddenRows.remove(r)) l.hiddenRows.add(r); }
                    else l.moveRow(r, d);
                    home.changed("board");
                    populateKeepFocus(r);
                }
                String hint() { return "OK shows or hides · ◀ ▶ moves it up or down"; }
            });
        }
        section("Apps");
        add(new Row("Hidden apps") {
            String value() { return l.hiddenApps.isEmpty() ? "None" : l.hiddenApps.size() + " · OK shows all"; }
            void change(int d) { if (d == 0) { l.unhideAll(); home.changed("board"); populateKeepFocus(); } }
        });
        add(new Row("Reset Home") {
            String value() { return "OK"; }
            void change(int d) {
                if (d != 0) return;
                HomeLayout fresh = new HomeLayout();
                home.layout.accent = fresh.accent; home.layout.wallpaper = fresh.wallpaper; home.layout.avatar = true;
                home.layout.avatarSize = fresh.avatarSize; home.layout.side = fresh.side; home.layout.rows = fresh.rows;
                home.layout.hiddenRows = fresh.hiddenRows; home.layout.clock = true; home.layout.weather = true;
                home.layout.motion = fresh.motion; home.layout.pins = fresh.pins; home.layout.hiddenApps = fresh.hiddenApps;
                home.changed("structure");
                populate();
            }
            String hint() { return "Everything back to how Friday Home started"; }
        });
    }

    private void populateKeepFocus() { populateKeepFocus(null); }
    private void populateKeepFocus(String rowId) {
        int keep = focusIndex;
        populate();
        if (rowId != null) {
            String name = HomeLayout.ROW_NAMES[HomeLayout.ROWS.indexOf(rowId)];
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).label.equals(name)) keep = i;
        }
        focusIndex = Math.max(0, Math.min(rows.size() - 1, keep));
        rows.get(focusIndex).view.requestFocus();
    }

    private static String nice(String e) { return e.charAt(0) + e.substring(1).toLowerCase(java.util.Locale.ROOT); }

    private static <T> T cycle(T[] all, T now, int d) {
        int i = java.util.Arrays.asList(all).indexOf(now);
        return all[Math.floorMod(i + (d == 0 ? 1 : d), all.length)];
    }

    private void section(String s) {
        TextView t = Brand.text(home, s.toUpperCase(java.util.Locale.ROOT), 10, home.accent(), Brand.display(home));
        t.setLetterSpacing(0.13f);
        t.setPadding(home.dp(4), home.dp(14), 0, home.dp(4));
        list.addView(t);
    }

    private void add(Row r) {
        LinearLayout v = new LinearLayout(home);
        v.setOrientation(LinearLayout.HORIZONTAL);
        v.setGravity(Gravity.CENTER_VERTICAL);
        v.setPadding(home.dp(14), home.dp(9), home.dp(14), home.dp(9));
        LinearLayout words = new LinearLayout(home);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(Brand.text(home, r.label, 14, 0xF2FFFFFF, Brand.bodyBold(home)));
        if (r.hint() != null) words.addView(Brand.text(home, r.hint(), 10, Brand.FAINT, Brand.body(home)));
        v.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        r.value = Brand.text(home, "‹  " + r.value() + "  ›", 13, home.accent(), Brand.bodyBold(home));
        v.addView(r.value);
        GradientDrawable on = new GradientDrawable();
        on.setColor((home.accent() & 0x00FFFFFF) | 0x1F000000);
        on.setCornerRadius(home.dp(8));
        on.setStroke(home.dp(1.5f), home.accent());
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(120);
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(0));
        v.setBackground(s);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setDefaultFocusHighlightEnabled(false);
        int index = rows.size();
        v.setOnFocusChangeListener((x, has) -> { if (has) focusIndex = index; });
        v.setOnClickListener(x -> { r.change(0); refreshValues(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = home.dp(2);
        list.addView(v, lp);
        r.view = v;
        rows.add(r);
    }

    private void refreshValues() { for (Row r : rows) r.value.setText("‹  " + r.value() + "  ›"); }

    /** Keys while the panel is open. True when handled. */
    boolean key(KeyEvent e) {
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_MENU) {
            if (down) home.closePanel();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (down && focusIndex < rows.size()) { rows.get(focusIndex).change(k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1); refreshValues(); }
            return true;   // never let Left/Right leave the panel
        }
        return false;
    }

    void refocus() {
        if (getParent() == null) return;
        bringToFront();
        refreshValues();
        if (focusIndex < rows.size()) rows.get(focusIndex).view.requestFocus();
    }

    void show() {
        card.setTranslationX(home.dp(60));
        card.setAlpha(0f);
        setAlpha(0f);
        animate().alpha(1f).setDuration(180).start();
        card.animate().translationX(0).alpha(1f).setDuration(home.motion() == HomeLayout.Motion.OFF ? 0 : 350).setInterpolator(HomeActivity.REVEAL).start();
        post(() -> rows.get(0).view.requestFocus());
    }

    void hide(Runnable after) {
        animate().alpha(0f).setDuration(160).withEndAction(after).start();
    }
}

package ai.futurespeak.friday.tv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import ai.futurespeak.friday.tv.actions.Catalogue;
import ai.futurespeak.friday.tv.ui.Brand;

/** Menu (or a long press) on an app tile: pin it first, move it, or hide it from Home. Back cancels. */
final class AppMenu extends FrameLayout {
    private final HomeActivity home;
    private final LinearLayout card;
    private final View first;

    AppMenu(HomeActivity h, Catalogue.App app, View anchor, List<Catalogue.App> allowed) {
        super(h);
        home = h;
        setBackgroundColor(0x80060B19);
        setClickable(true);
        card = new LinearLayout(h);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xF20A0E1A);
        g.setCornerRadius(h.dp(14));
        g.setStroke(1, 0x1FFFFFFF);
        card.setBackground(g);
        card.setPadding(h.dp(22), h.dp(18), h.dp(22), h.dp(16));
        card.setElevation(h.dp(20));
        TextView eb = Brand.text(h, "ON HOME", 10, h.accent(), Brand.display(h));
        eb.setLetterSpacing(0.13f);
        card.addView(eb);
        TextView t = Brand.text(h, app.label, 18, 0xF2FFFFFF, Brand.display(h));
        t.setPadding(0, h.dp(4), 0, h.dp(10));
        card.addView(t);
        LinearLayout row = new LinearLayout(h);
        row.setOrientation(LinearLayout.HORIZONTAL);
        HomeLayout l = h.layout;
        first = button("★  Pin first", () -> { l.pinFirst(app.pkg); done(app.pkg); });
        row.addView(first);
        row.addView(button("◀  Move left", () -> { l.move(allowed, app.pkg, -1); done(app.pkg); }));
        row.addView(button("Move right  ▶", () -> { l.move(allowed, app.pkg, 1); done(app.pkg); }));
        row.addView(button("Hide from Home", () -> { l.hide(app.pkg); h.prefs().save(l); h.closeMenuAndRefocus(null); h.show(app.label + " is hidden. Customize Home shows it again."); }));
        row.addView(button("Cancel", h::closeMenu));
        card.addView(row);
        addView(card, new LayoutParams(-2, -2, Gravity.CENTER));
    }

    private void done(String pkg) {
        home.prefs().save(home.layout);
        home.closeMenuAndRefocus(pkg);
    }

    private View button(String label, Runnable run) {
        TextView b = Brand.text(home, label, 13, Brand.TEXT, Brand.bodyBold(home));
        b.setGravity(Gravity.CENTER);
        b.setPadding(home.dp(16), home.dp(10), home.dp(16), home.dp(10));
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0x14FFFFFF);
        rest.setCornerRadius(home.dp(8));
        GradientDrawable on = new GradientDrawable();
        on.setColor((home.accent() & 0x00FFFFFF) | 0x33000000);
        on.setCornerRadius(home.dp(8));
        on.setStroke(home.dp(1.5f), home.accent());
        android.graphics.drawable.StateListDrawable s = new android.graphics.drawable.StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, on);
        s.addState(new int[]{}, rest);
        b.setBackground(s);
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setDefaultFocusHighlightEnabled(false);
        b.setOnClickListener(x -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = home.dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    boolean key(KeyEvent e) {
        int k = e.getKeyCode();
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) home.closeMenu();
            return true;
        }
        return false;
    }

    void show() {
        card.setScaleX(0.94f); card.setScaleY(0.94f); card.setAlpha(0f);
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(home.motion() == HomeLayout.Motion.OFF ? 0 : 220).setInterpolator(HomeActivity.REVEAL).start();
        post(first::requestFocus);
    }
}

package ai.futurespeak.friday.tv.addon;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import ai.futurespeak.friday.tv.FridayApp;
import ai.futurespeak.friday.tv.actions.TvActions;
import ai.futurespeak.friday.tv.ui.Brand;

/**
 * Friday's approval card for an add-on (Contract.ACTION_APPROVE, started for a result). Only an
 * enabled add-on gets a card; "Not now" has the focus, so a stray OK never says yes. In kids
 * mode a card is shown only while a grown-up has unlocked; otherwise the answer is no.
 * Every answer is written to the receipts.
 */
public final class ApproveActivity extends Activity {
    private AddonSpec spec;
    private TvActions actions;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setResult(RESULT_CANCELED);
        spec = AddonRegistry.enabledSpec(this, getCallingPackage());
        if (spec == null) { finish(); return; }
        FridayApp app = (FridayApp) getApplication();
        actions = app.actions();
        boolean kids = actions == null || actions.kids();
        boolean grownUp = false;
        try { grownUp = app.grownUp().status().unlocked; } catch (Throwable ignored) { }
        if (kids && !grownUp) { answer(false, "kids mode, no grown-up unlock"); return; }

        String q = clip(getIntent().getStringExtra(Contract.EXTRA_QUESTION), 160);
        String d = clip(getIntent().getStringExtra(Contract.EXTRA_DETAIL), 400);
        if (q.isEmpty()) { finish(); return; }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Brand.glass(this, 24));
        int pad = Brand.dp(this, 40);
        card.setPadding(pad, pad, pad, pad);
        card.addView(Brand.text(this, spec.icon + "  " + spec.title.toUpperCase(java.util.Locale.ROOT) + " ASKS", 14, Brand.DIM, Brand.display(this)));
        TextView qv = Brand.text(this, q, 30, Brand.TEXT, Brand.bodyBold(this));
        qv.setPadding(0, Brand.dp(this, 16), 0, Brand.dp(this, 8));
        card.addView(qv);
        if (!d.isEmpty()) card.addView(Brand.text(this, d, 20, Brand.DIM, Brand.body(this)));
        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, Brand.dp(this, 28), 0, 0);
        TextView no = Brand.button(this, "Not now", Brand.CYAN);
        TextView yes = Brand.button(this, "Yes", Brand.CYAN);
        no.setOnClickListener(v -> answer(false, "the person said not now"));
        yes.setOnClickListener(v -> answer(true, "the person said yes"));
        row.addView(no);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.leftMargin = Brand.dp(this, 20);
        row.addView(yes, gap);
        card.addView(row);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xCC03040A);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Brand.dp(this, 760), -2, Gravity.CENTER);
        root.addView(card, lp);
        setContentView(root);
        no.requestFocus();
    }

    private void answer(boolean ok, String why) {
        CoreAddonProvider.note(actions, spec, "approve", (ok ? "yes: " : "no: ") + why);
        setResult(ok ? RESULT_OK : RESULT_CANCELED, new android.content.Intent().putExtra(Contract.K_WHY, why));
        finish();
    }

    @Override public void onBackPressed() { answer(false, "the person pressed Back"); }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
